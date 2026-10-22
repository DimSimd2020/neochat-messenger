package app

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"golang.org/x/crypto/argon2"
)

const (
	accessTTL    = 15 * time.Minute
	refreshTTL   = 30 * 24 * time.Hour
	refreshGrace = 60 * time.Second
	// Per-account active devices. Keep in sync with core MAX_DEVICES.
	maxDevices    = 10
	loginSkew     = 5 * time.Minute
	identityLen   = 33   // libsignal serialized Curve25519 public key
	kyberKeyLen   = 1569 // libsignal serialized Kyber1024 public key
	signatureLen  = 64
	maxPrekeysReq = 200
	// 10 devices × (16 uuid + 33 identity + 32 auth) plus roster header stays well under this.
	maxRosterBlob = 4096
)

var usernameRe = regexp.MustCompile(`^[a-z0-9_]{3,32}$`)

// nicknameRe is the username as typed: letter case is kept for display only.
var nicknameRe = regexp.MustCompile(`^[A-Za-z0-9_]{3,32}$`)

type prekeyIn struct {
	ID        uint32 `json:"id"`
	PublicKey []byte `json:"public_key"`
	Signature []byte `json:"signature,omitempty"`
}

type deviceIn struct {
	DeviceID            string     `json:"device_id"`
	Name                string     `json:"name"`
	Platform            string     `json:"platform"`
	RegistrationID      int32      `json:"registration_id"`
	IdentityKey         []byte     `json:"identity_key"`
	AuthKey             []byte     `json:"auth_key"`
	SignedPrekey        prekeyIn   `json:"signed_prekey"`
	KyberPrekey         prekeyIn   `json:"kyber_prekey"`
	OneTimePrekeys      []prekeyIn `json:"one_time_prekeys"`
	KyberOneTimePrekeys []prekeyIn `json:"kyber_one_time_prekeys"`
}

// rosterIn is the account-signed device list. The server stores it opaquely;
// clients verify the signature against the pinned account identity.
type rosterIn struct {
	Revision  string `json:"revision"`
	Blob      []byte `json:"blob"`
	Signature []byte `json:"signature"`
}

type tokensOut struct {
	UserID          string `json:"user_id"`
	DeviceID        string `json:"device_id"`
	AccessToken     string `json:"access_token"`
	RefreshToken    string `json:"refresh_token"`
	AccessExpiresIn int64  `json:"access_expires_in"`
}

func validateDevice(d *deviceIn) error {
	bad := func(field string) error { return errf(400, "bad_request", "Некорректное поле "+field) }
	switch {
	case !isUUID(d.DeviceID):
		return bad("device_id")
	case len(d.Name) == 0 || len(d.Name) > 64:
		return bad("name")
	case d.Platform != "windows" && d.Platform != "linux" && d.Platform != "macos" && d.Platform != "android" && d.Platform != "ios":
		return bad("platform")
	case d.RegistrationID <= 0 || d.RegistrationID > 16380:
		return bad("registration_id")
	case len(d.IdentityKey) != identityLen:
		return bad("identity_key")
	case len(d.AuthKey) != ed25519.PublicKeySize:
		return bad("auth_key")
	case len(d.SignedPrekey.PublicKey) != identityLen || len(d.SignedPrekey.Signature) != signatureLen:
		return bad("signed_prekey")
	case len(d.KyberPrekey.PublicKey) != kyberKeyLen || len(d.KyberPrekey.Signature) != signatureLen:
		return bad("kyber_prekey")
	case len(d.OneTimePrekeys) > maxPrekeysReq || len(d.KyberOneTimePrekeys) > maxPrekeysReq:
		return bad("prekeys")
	}
	return validatePrekeys(d.OneTimePrekeys, d.KyberOneTimePrekeys)
}

func validatePrekeys(ec, kyber []prekeyIn) error {
	for _, p := range ec {
		if len(p.PublicKey) != identityLen {
			return errf(400, "bad_request", "Некорректный одноразовый ключ")
		}
	}
	for _, p := range kyber {
		if len(p.PublicKey) != kyberKeyLen || len(p.Signature) != signatureLen {
			return errf(400, "bad_request", "Некорректный постквантовый ключ")
		}
	}
	return nil
}

func validateRoster(r *rosterIn) (int64, error) {
	rev, err := strconv.ParseInt(r.Revision, 10, 64)
	if err != nil || rev < 1 || len(r.Blob) == 0 || len(r.Blob) > maxRosterBlob || len(r.Signature) != signatureLen {
		return 0, errf(400, "bad_request", "Некорректный список устройств")
	}
	return rev, nil
}

func errDeviceLimit() *apiError {
	return errf(409, "device_limit", fmt.Sprintf("У аккаунта уже %d устройств. Отзовите одно из них", maxDevices))
}

func validatePassword(p string) error {
	if len(p) < 8 || len(p) > 1024 {
		return errf(400, "weak_password", "Пароль должен содержать не меньше 8 символов")
	}
	return nil
}

// ---- password hashing ----

func (s *Server) hashPassword(ctx context.Context, password string) (string, error) {
	salt := make([]byte, 16)
	rand.Read(salt)
	key, err := s.argonKey(ctx, []byte(password), salt, s.cfg.ArgonTime, s.cfg.ArgonMemoryKiB)
	if err != nil {
		return "", err
	}
	enc := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=19$m=%d,t=%d,p=1$%s$%s", s.cfg.ArgonMemoryKiB, s.cfg.ArgonTime, enc.EncodeToString(salt), enc.EncodeToString(key)), nil
}

func (s *Server) checkPassword(ctx context.Context, encoded, password string) (bool, error) {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false, errors.New("bad hash format")
	}
	var m, t uint32
	var p int
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &m, &t, &p); err != nil {
		return false, err
	}
	enc := base64.RawStdEncoding
	salt, err1 := enc.DecodeString(parts[4])
	want, err2 := enc.DecodeString(parts[5])
	if err1 != nil || err2 != nil {
		return false, errors.New("bad hash encoding")
	}
	got, err := s.argonKey(ctx, []byte(password), salt, t, m)
	if err != nil {
		return false, err
	}
	return subtle.ConstantTimeCompare(got, want) == 1, nil
}

// argonKey bounds concurrent hashing so login floods cannot exhaust RAM.
func (s *Server) argonKey(ctx context.Context, pw, salt []byte, t, m uint32) ([]byte, error) {
	select {
	case s.argon <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-time.After(10 * time.Second):
		return nil, &apiError{Status: 503, Code: "busy", Message: "Сервер занят, повторите позже", RetryAfterMs: 2000}
	}
	defer func() { <-s.argon }()
	return argon2.IDKey(pw, salt, t, m, 1, 32), nil
}

var dummyHash string

// ---- tokens ----

func randomToken() string {
	b := make([]byte, 32)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func tokenHash(t string) []byte {
	h := sha256.Sum256([]byte(t))
	return h[:]
}

func newSession(ctx context.Context, tx pgx.Tx, userID, deviceID, family string) (tokensOut, string, error) {
	id := newUUID()
	if family == "" {
		family = newUUID()
	}
	access, refresh := randomToken(), randomToken()
	now := time.Now()
	_, err := tx.Exec(ctx, `INSERT INTO sessions(id, device_id, family, access_hash, access_expires, refresh_hash, refresh_expires)
		VALUES ($1,$2,$3,$4,$5,$6,$7)`, id, deviceID, family, tokenHash(access), now.Add(accessTTL), tokenHash(refresh), now.Add(refreshTTL))
	return tokensOut{UserID: userID, DeviceID: deviceID, AccessToken: access, RefreshToken: refresh, AccessExpiresIn: int64(accessTTL.Seconds())}, id, err
}

func insertDevice(ctx context.Context, tx pgx.Tx, userID string, d *deviceIn, primary bool) error {
	_, err := tx.Exec(ctx, `INSERT INTO devices(id, user_id, name, platform, is_primary, registration_id, identity_key, auth_key,
		signed_prekey_id, signed_prekey, signed_prekey_sig, kyber_prekey_id, kyber_prekey, kyber_prekey_sig)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)`,
		d.DeviceID, userID, d.Name, d.Platform, primary, d.RegistrationID, d.IdentityKey, d.AuthKey,
		int64(d.SignedPrekey.ID), d.SignedPrekey.PublicKey, d.SignedPrekey.Signature,
		int64(d.KyberPrekey.ID), d.KyberPrekey.PublicKey, d.KyberPrekey.Signature)
	if isUniqueViolation(err) {
		return errf(409, "device_exists", "Устройство уже зарегистрировано")
	}
	if err != nil {
		return err
	}
	return insertPrekeys(ctx, tx, d.DeviceID, d.OneTimePrekeys, d.KyberOneTimePrekeys)
}

func insertPrekeys(ctx context.Context, tx pgx.Tx, deviceID string, ec, kyber []prekeyIn) error {
	rows := make([][]any, 0, len(ec)+len(kyber))
	for _, p := range ec {
		rows = append(rows, []any{deviceID, int16(0), int64(p.ID), p.PublicKey, nil})
	}
	for _, p := range kyber {
		rows = append(rows, []any{deviceID, int16(1), int64(p.ID), p.PublicKey, p.Signature})
	}
	if len(rows) == 0 {
		return nil
	}
	// Re-uploading an existing id replaces it; ids are allocated by the device.
	for _, r := range rows {
		if _, err := tx.Exec(ctx, `INSERT INTO one_time_prekeys(device_id, kind, key_id, public_key, signature) VALUES ($1,$2,$3,$4,$5)
			ON CONFLICT (device_id, kind, key_id) DO UPDATE SET public_key=EXCLUDED.public_key, signature=EXCLUDED.signature`, r...); err != nil {
			return err
		}
	}
	return nil
}

// ---- handlers ----

type registerReq struct {
	EmailTicket        string   `json:"email_ticket"`
	Username           string   `json:"username"`
	DisplayName        string   `json:"display_name"`
	Password           string   `json:"password"`
	AccountIdentityKey []byte   `json:"account_identity_key"`
	RecoveryHash       []byte   `json:"recovery_hash"`
	KeyBackup          []byte   `json:"key_backup"`
	Device             deviceIn `json:"device"`
	Roster             rosterIn `json:"roster"`
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if !s.limits.allow("register:"+clientIP(r), 10, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var req registerReq
	if err := readJSON(w, r, 256<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	nickname := strings.TrimPrefix(strings.TrimSpace(req.Username), "@")
	if !nicknameRe.MatchString(nickname) || strings.EqualFold(nickname, "everyone") || strings.EqualFold(nickname, "here") {
		s.fail(w, r, errf(400, "bad_username", "Имя пользователя: 3–32 латинские буквы, цифры или подчёркивание; everyone и here заняты"))
		return
	}
	req.Username = strings.ToLower(nickname)
	displayName, err := validateDisplayName(req.DisplayName)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if err := validatePassword(req.Password); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(req.AccountIdentityKey) != identityLen || len(req.RecoveryHash) != sha256.Size || len(req.KeyBackup) == 0 || len(req.KeyBackup) > maxBackup {
		s.fail(w, r, errBadRequest)
		return
	}
	if err := validateDevice(&req.Device); err != nil {
		s.fail(w, r, err)
		return
	}
	rev, err := validateRoster(&req.Roster)
	if err != nil || rev != 1 {
		s.fail(w, r, errf(400, "bad_request", "Некорректный список устройств"))
		return
	}
	pwHash, err := s.hashPassword(r.Context(), req.Password)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	userID := newUUID()
	var out tokensOut
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		email, err := registrationEmail(tx, r, req.EmailTicket)
		if err != nil {
			return err
		}
		_, err = tx.Exec(r.Context(), `INSERT INTO users(id, username, password_hash, account_identity_key, identity_epoch, roster_revision, roster_blob, roster_sig, recovery_hash, key_backup, nickname, display_name, email, email_verified_at)
			VALUES ($1,$2,$3,$4,1,1,$5,$6,$7,$8,$9,$10,$11,now())`, userID, req.Username, pwHash, req.AccountIdentityKey, req.Roster.Blob, req.Roster.Signature, req.RecoveryHash, req.KeyBackup,
			nickname, displayName, email)
		if isUniqueViolation(err) {
			if constraintName(err) == "users_email" {
				return errf(409, "email_taken", "Этот адрес уже привязан к аккаунту NeoChat")
			}
			return errf(409, "username_taken", "Это имя пользователя уже занято")
		}
		if err != nil {
			return err
		}
		if err := insertDevice(r.Context(), tx, userID, &req.Device, true); err != nil {
			return err
		}
		out, _, err = newSession(r.Context(), tx, userID, req.Device.DeviceID, "")
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 201, out)
}

type loginReq struct {
	Username  string `json:"username"`
	Password  string `json:"password"`
	DeviceID  string `json:"device_id"`
	Timestamp string `json:"timestamp"`
	Signature []byte `json:"signature"`
}

// LoginChallenge is the byte string a device signs with its Ed25519 auth key.
func LoginChallenge(username, deviceID, ts string) []byte {
	return []byte("NeoChat-login-v1\n" + username + "\n" + deviceID + "\n" + ts)
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginReq
	if err := readJSON(w, r, 8<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	login := normalizeLogin(req.Username)
	if !s.limits.allow("login-ip:"+clientIP(r), 30, 15*time.Minute) || !s.limits.allow("login-user:"+login, 10, 15*time.Minute) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	invalid := errf(401, "invalid_credentials", "Неверное имя пользователя, пароль или устройство")
	userID, username, pwHash, found, err := s.lookupVerifiedLogin(r, login)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var authKey []byte
	if found {
		err = s.db.QueryRow(r.Context(), `SELECT auth_key FROM devices WHERE user_id=$1 AND id=$2 AND revoked_at IS NULL`,
			userID, nullUUID(req.DeviceID)).Scan(&authKey)
		if errors.Is(err, pgx.ErrNoRows) {
			found = false
			err = nil
		}
	}
	if !found || err != nil {
		if err != nil {
			s.fail(w, r, err)
			return
		}
		s.checkPassword(r.Context(), dummyHash, req.Password)
		s.fail(w, r, invalid)
		return
	}
	ok, err := s.checkPassword(r.Context(), pwHash, req.Password)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	ts, tsErr := strconv.ParseInt(req.Timestamp, 10, 64)
	// Challenge always uses the canonical username, even when the client typed an email.
	if !ok || tsErr != nil || absDur(time.Since(time.Unix(ts, 0))) > loginSkew ||
		!ed25519.Verify(authKey, LoginChallenge(username, req.DeviceID, req.Timestamp), req.Signature) {
		s.fail(w, r, invalid)
		return
	}
	var out tokensOut
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		var e error
		out, _, e = newSession(r.Context(), tx, userID, req.DeviceID, "")
		return e
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, out)
}

func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := readJSON(w, r, 4<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	invalid := errf(401, "invalid_refresh", "Сессия истекла, войдите снова")
	var out tokensOut
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		var id, deviceID, userID, family string
		var replacedBy *string
		var replacedAt, revokedAt, devRevoked *time.Time
		var expires time.Time
		err := tx.QueryRow(r.Context(), `SELECT s.id, s.device_id, d.user_id, s.family, s.replaced_by, s.replaced_at, s.revoked_at, s.refresh_expires, d.revoked_at
			FROM sessions s JOIN devices d ON d.id=s.device_id WHERE s.refresh_hash=$1 FOR UPDATE OF s`, tokenHash(req.RefreshToken)).
			Scan(&id, &deviceID, &userID, &family, &replacedBy, &replacedAt, &revokedAt, &expires, &devRevoked)
		if errors.Is(err, pgx.ErrNoRows) {
			return invalid
		}
		if err != nil {
			return err
		}
		if revokedAt != nil || devRevoked != nil || time.Now().After(expires) {
			return invalid
		}
		if replacedBy != nil {
			// The previous response may have been lost: allow one retry within the grace window
			// by rotating again from the successor; later reuse revokes the whole family.
			if time.Since(*replacedAt) > refreshGrace {
				_, err := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE family=$1 AND revoked_at IS NULL`, family)
				if err != nil {
					return err
				}
				return errCommitThen(invalid)
			}
			if _, err := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE id=$1`, *replacedBy); err != nil {
				return err
			}
		}
		var newID string
		out, newID, err = newSession(r.Context(), tx, userID, deviceID, family)
		if err != nil {
			return err
		}
		_, err = tx.Exec(r.Context(), `UPDATE sessions SET replaced_by=$2, replaced_at=COALESCE(replaced_at, now()), access_expires=LEAST(access_expires, now()) WHERE id=$1`, id, newID)
		return err
	})
	var ct *commitThen
	if errors.As(err, &ct) {
		err = ct.err
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, out)
}

// commitThen lets a transaction commit its side effects and still return an error.
type commitThen struct{ err error }

func (c *commitThen) Error() string { return c.err.Error() }

func errCommitThen(err error) error { return &commitThen{err} }

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request, a *authCtx) {
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		if _, err := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE id=$1`, a.SessionID); err != nil {
			return err
		}
		_, err := tx.Exec(r.Context(), `DELETE FROM push_tokens WHERE device_id=$1`, a.DeviceID)
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(204)
}

type recoverReq struct {
	Username           string   `json:"username"`
	RecoverySecret     []byte   `json:"recovery_secret"`
	NewPassword        string   `json:"new_password"`
	RecoveryHash       []byte   `json:"recovery_hash"`
	AccountIdentityKey []byte   `json:"account_identity_key"`
	KeyBackup          []byte   `json:"key_backup"`
	Device             deviceIn `json:"device"`
	Roster             rosterIn `json:"roster"`
}

// handleRecover replaces the account identity: all old devices and sessions are revoked
// and every related user receives an identity change event. History is not restored.
func (s *Server) handleRecover(w http.ResponseWriter, r *http.Request) {
	var req recoverReq
	if err := readJSON(w, r, 256<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	req.Username = strings.ToLower(strings.TrimSpace(req.Username))
	if !s.limits.allow("recover-ip:"+clientIP(r), 10, time.Hour) || !s.limits.allow("recover-user:"+req.Username, 5, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	if err := validatePassword(req.NewPassword); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(req.AccountIdentityKey) != identityLen || len(req.RecoveryHash) != sha256.Size || len(req.RecoverySecret) != 32 || len(req.KeyBackup) == 0 || len(req.KeyBackup) > maxBackup {
		s.fail(w, r, errBadRequest)
		return
	}
	if err := validateDevice(&req.Device); err != nil {
		s.fail(w, r, err)
		return
	}
	rev, err := validateRoster(&req.Roster)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	pwHash, err := s.hashPassword(r.Context(), req.NewPassword)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	invalid := errf(401, "invalid_recovery", "Неверное имя пользователя или код восстановления")
	var out tokensOut
	var oldDevices, notify []string
	var recovered string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		var userID string
		var stored []byte
		var epoch, rosterRev int64
		err := tx.QueryRow(r.Context(), `SELECT id, recovery_hash, identity_epoch, roster_revision FROM users WHERE username=$1 FOR UPDATE`, req.Username).
			Scan(&userID, &stored, &epoch, &rosterRev)
		if errors.Is(err, pgx.ErrNoRows) {
			return invalid
		}
		if err != nil {
			return err
		}
		got := sha256.Sum256(req.RecoverySecret)
		if subtle.ConstantTimeCompare(got[:], stored) != 1 {
			return invalid
		}
		if rev <= rosterRev {
			return errf(409, "roster_revision_conflict", "Список устройств устарел, обновите его")
		}
		rows, err := tx.Query(r.Context(), `UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL RETURNING id`, userID)
		if err != nil {
			return err
		}
		for rows.Next() {
			var id string
			rows.Scan(&id)
			oldDevices = append(oldDevices, id)
		}
		rows.Close()
		if _, err := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE revoked_at IS NULL AND device_id IN (SELECT id FROM devices WHERE user_id=$1)`, userID); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `DELETE FROM one_time_prekeys WHERE device_id IN (SELECT id FROM devices WHERE user_id=$1)`, userID); err != nil {
			return err
		}
		// The account state was sealed with keys that no longer exist.
		if _, err := tx.Exec(r.Context(), `DELETE FROM account_state WHERE user_id=$1`, userID); err != nil {
			return err
		}
		_, err = tx.Exec(r.Context(), `UPDATE users SET password_salt=username, password_hash=$2, account_identity_key=$3, identity_epoch=$4, roster_revision=$5, roster_blob=$6, roster_sig=$7, recovery_hash=$8, key_backup=$9 WHERE id=$1`,
			userID, pwHash, req.AccountIdentityKey, epoch+1, rev, req.Roster.Blob, req.Roster.Signature, req.RecoveryHash, req.KeyBackup)
		if err != nil {
			return err
		}
		if err := insertDevice(r.Context(), tx, userID, &req.Device, true); err != nil {
			return err
		}
		out, _, err = newSession(r.Context(), tx, userID, req.Device.DeviceID, "")
		if err != nil {
			return err
		}
		notify, err = s.emitToRelated(r.Context(), tx, userID, "identity", map[string]any{"user_id": userID, "identity_epoch": strconv.FormatInt(epoch+1, 10)})
		recovered = userID
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	// The old history copy was sealed with keys that no longer exist.
	os.Remove(s.historyPath(recovered))
	for _, d := range oldDevices {
		s.hub.disconnect(d)
	}
	s.hub.notify(notify)
	writeJSON(w, 200, out)
}

// ---- middleware ----

type authCtx struct {
	UserID    string
	DeviceID  string
	SessionID string
	Primary   bool
}

func (s *Server) authed(h func(http.ResponseWriter, *http.Request, *authCtx)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || tok == "" {
			s.fail(w, r, errUnauthorized)
			return
		}
		a := &authCtx{}
		var lastSeen time.Time
		err := s.db.QueryRow(r.Context(), `SELECT s.id, d.id, d.user_id, d.is_primary, d.last_seen_at FROM sessions s JOIN devices d ON d.id=s.device_id
			WHERE s.access_hash=$1 AND s.access_expires>now() AND s.revoked_at IS NULL AND d.revoked_at IS NULL`, tokenHash(tok)).
			Scan(&a.SessionID, &a.DeviceID, &a.UserID, &a.Primary, &lastSeen)
		if errors.Is(err, pgx.ErrNoRows) {
			s.fail(w, r, errUnauthorized)
			return
		}
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if time.Since(lastSeen) > 5*time.Minute {
			s.db.Exec(r.Context(), `UPDATE devices SET last_seen_at=now() WHERE id=$1`, a.DeviceID)
		}
		h(w, r, a)
	}
}

func absDur(d time.Duration) time.Duration {
	if d < 0 {
		return -d
	}
	return d
}

func errRateLimited(after time.Duration) *apiError {
	return &apiError{Status: 429, Code: "rate_limited", Message: "Слишком много попыток, повторите позже", RetryAfterMs: after.Milliseconds()}
}
