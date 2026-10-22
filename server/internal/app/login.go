package app

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	linkTTL    = 5 * time.Minute
	codeTTL    = 10 * time.Minute
	maxHistory = 200 << 20
	maxBackup  = 4096
)

// notifyOwn puts a text into the system chat of every active device of userID except skip.
func notifyOwn(ctx context.Context, tx pgx.Tx, userID, skip, text string) ([]string, error) {
	rows, err := tx.Query(ctx, `SELECT id FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND id::text<>$2`, userID, skip)
	if err != nil {
		return nil, err
	}
	devices, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return nil, err
	}
	return emitToDevices(ctx, tx, devices, "notice", map[string]string{"text": text})
}

func platformName(p string) string {
	switch p {
	case "windows":
		return "Windows"
	case "android":
		return "Android"
	case "ios":
		return "iOS"
	case "macos":
		return "macOS"
	}
	return "Linux"
}

// ---- password sign-in on a new device ----

// handleNewDeviceStart checks the password (and the two-factor code when enabled) and
// returns the password-sealed account key backup with a short-lived start token.
func (s *Server) handleNewDeviceStart(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Code     string `json:"code"`
	}
	if err := readJSON(w, r, 8<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	login := normalizeLogin(req.Username)
	if !s.limits.allow("login-ip:"+clientIP(r), 30, 15*time.Minute) || !s.limits.allow("login-user:"+login, 10, 15*time.Minute) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	invalid := errf(401, "invalid_credentials", "Неверное имя пользователя или пароль")
	userID, _, pwHash, found, err := s.lookupVerifiedLogin(r, login)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !found {
		s.checkPassword(r.Context(), dummyHash, req.Password)
		s.fail(w, r, invalid)
		return
	}
	var twoFactor bool
	var backup, accountKey, blob, sig []byte
	var rosterRev int64
	err = s.db.QueryRow(r.Context(), `SELECT two_factor, key_backup, account_identity_key, roster_revision, roster_blob, roster_sig FROM users WHERE id=$1`,
		userID).Scan(&twoFactor, &backup, &accountKey, &rosterRev, &blob, &sig)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	ok, err := s.checkPassword(r.Context(), pwHash, req.Password)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !ok {
		s.fail(w, r, invalid)
		return
	}
	if len(backup) == 0 {
		s.fail(w, r, errf(409, "no_backup", "Для этого аккаунта нет копии ключей. Войдите по QR-коду с другого устройства"))
		return
	}
	var n int
	if err := s.db.QueryRow(r.Context(), `SELECT count(*) FROM devices WHERE user_id=$1 AND revoked_at IS NULL`, userID).Scan(&n); err != nil {
		s.fail(w, r, err)
		return
	}
	if n >= maxDevices {
		s.fail(w, r, errDeviceLimit())
		return
	}
	// Without other devices there is nowhere to deliver the code, so the password suffices.
	if twoFactor && n > 0 {
		if err := s.checkLoginCode(r, userID, strings.TrimSpace(req.Code)); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	id, token := newUUID(), randomToken()
	if _, err := s.db.Exec(r.Context(), `INSERT INTO device_links(id, kind, user_id, secret_hash, device, expires_at) VALUES ($1,'start',$2,$3,'{}',$4)`,
		id, userID, tokenHash(token), time.Now().Add(linkTTL)); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{
		"user_id": userID, "start_token": id + "." + token, "key_backup": backup, "account_identity_key": accountKey,
		"roster": map[string]any{"revision": fmt.Sprint(rosterRev), "blob": blob, "signature": sig},
	})
}

// checkLoginCode sends a fresh code to the system chat when none is given, otherwise verifies it.
func (s *Server) checkLoginCode(r *http.Request, userID, code string) error {
	required := errf(403, "code_required", "Введите код из чата «NeoChat уведомления» на другом устройстве")
	if code == "" {
		n, _ := rand.Int(rand.Reader, big.NewInt(1_000_000))
		fresh := fmt.Sprintf("%06d", n.Int64())
		var notify []string
		err := s.inTx(r.Context(), func(tx pgx.Tx) error {
			_, err := tx.Exec(r.Context(), `INSERT INTO login_codes(user_id, code_hash, expires_at) VALUES ($1,$2,$3)
				ON CONFLICT (user_id) DO UPDATE SET code_hash=EXCLUDED.code_hash, expires_at=EXCLUDED.expires_at, attempts=0`,
				userID, tokenHash(fresh), time.Now().Add(codeTTL))
			if err != nil {
				return err
			}
			notify, err = notifyOwn(r.Context(), tx, userID, "", "Код для входа: "+fresh+"\nНикому его не сообщайте. Если это не вы, смените пароль.")
			return err
		})
		if err != nil {
			return err
		}
		s.hub.notify(notify)
		return required
	}
	var hash []byte
	var expires time.Time
	var attempts int
	err := s.db.QueryRow(r.Context(), `UPDATE login_codes SET attempts=attempts+1 WHERE user_id=$1 RETURNING code_hash, expires_at, attempts`, userID).Scan(&hash, &expires, &attempts)
	if errors.Is(err, pgx.ErrNoRows) {
		return required
	}
	if err != nil {
		return err
	}
	if attempts > 5 || time.Now().After(expires) {
		s.db.Exec(r.Context(), `DELETE FROM login_codes WHERE user_id=$1`, userID)
		return errf(403, "code_expired", "Код устарел. Запросите новый")
	}
	if subtle.ConstantTimeCompare(hash, tokenHash(code)) != 1 {
		return errf(403, "invalid_code", "Неверный код")
	}
	_, err = s.db.Exec(r.Context(), `DELETE FROM login_codes WHERE user_id=$1`, userID)
	return err
}

func (s *Server) handleNewDeviceFinish(w http.ResponseWriter, r *http.Request) {
	var req struct {
		StartToken string   `json:"start_token"`
		Device     deviceIn `json:"device"`
		Roster     rosterIn `json:"roster"`
	}
	if err := readJSON(w, r, 256<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if err := validateDevice(&req.Device); err != nil {
		s.fail(w, r, err)
		return
	}
	linkID, token, _ := strings.Cut(req.StartToken, ".")
	var out tokensOut
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		var userID string
		var hash []byte
		err := tx.QueryRow(r.Context(), `DELETE FROM device_links WHERE id=$1 AND kind='start' AND expires_at>now() RETURNING user_id, secret_hash`, nullUUID(linkID)).
			Scan(&userID, &hash)
		if errors.Is(err, pgx.ErrNoRows) || (err == nil && subtle.ConstantTimeCompare(hash, tokenHash(token)) != 1) {
			return errf(401, "start_expired", "Вход занял слишком много времени. Повторите")
		}
		if err != nil {
			return err
		}
		if notify, err = s.addDevice(r, tx, userID, &req.Device, &req.Roster); err != nil {
			return err
		}
		out, _, err = newSession(r.Context(), tx, userID, req.Device.DeviceID, "")
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	writeJSON(w, 200, out)
}

// addDevice publishes the roster that lists the new device and tells the account's other devices.
func (s *Server) addDevice(r *http.Request, tx pgx.Tx, userID string, d *deviceIn, ro *rosterIn) ([]string, error) {
	if _, err := tx.Exec(r.Context(), `SELECT 1 FROM users WHERE id=$1 FOR UPDATE`, userID); err != nil {
		return nil, err
	}
	var n int
	if err := tx.QueryRow(r.Context(), `SELECT count(*) FROM devices WHERE user_id=$1 AND revoked_at IS NULL`, userID).Scan(&n); err != nil {
		return nil, err
	}
	if n >= maxDevices {
		return nil, errDeviceLimit()
	}
	if err := updateRoster(r, tx, userID, ro); err != nil {
		return nil, err
	}
	if err := insertDevice(r.Context(), tx, userID, d, false); err != nil {
		return nil, err
	}
	own, err := notifyOwn(r.Context(), tx, userID, "", fmt.Sprintf("Вход на новом устройстве: %s (%s). Если это не вы, отзовите его в настройках и смените пароль.", d.Name, platformName(d.Platform)))
	if err != nil {
		return nil, err
	}
	related, err := s.emitToRelated(r.Context(), tx, userID, "roster", map[string]string{"user_id": userID})
	return append(own, related...), err
}

// ---- QR sign-in: the computer shows a code, a signed-in phone approves it ----

func (s *Server) handleQRStart(w http.ResponseWriter, r *http.Request) {
	if !s.limits.allow("qr-ip:"+clientIP(r), 60, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var req struct {
		Device deviceIn `json:"device"`
	}
	if err := readJSON(w, r, 256<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if err := validateDevice(&req.Device); err != nil {
		s.fail(w, r, err)
		return
	}
	dev, _ := json.Marshal(req.Device)
	id, secret := newUUID(), randomToken()
	if _, err := s.db.Exec(r.Context(), `INSERT INTO device_links(id, kind, secret_hash, device, expires_at) VALUES ($1,'qr',$2,$3,$4)`,
		id, tokenHash(secret), dev, time.Now().Add(linkTTL)); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 201, map[string]any{"link_id": id, "link_secret": secret, "expires_in": int(linkTTL.Seconds())})
}

// handleQRPoll hands the computer its first session and the sealed keys exactly once.
func (s *Server) handleQRPoll(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	secret := r.Header.Get("X-Link-Secret")
	var out map[string]any
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		var userID *string
		var hash, dev, sealed []byte
		var expires time.Time
		var approved *time.Time
		err := tx.QueryRow(r.Context(), `SELECT user_id, secret_hash, device, sealed, expires_at, approved_at FROM device_links WHERE id=$1 AND kind='qr' FOR UPDATE`, nullUUID(id)).
			Scan(&userID, &hash, &dev, &sealed, &expires, &approved)
		if errors.Is(err, pgx.ErrNoRows) || (err == nil && subtle.ConstantTimeCompare(hash, tokenHash(secret)) != 1) {
			return errNotFound
		}
		if err != nil {
			return err
		}
		if approved == nil || userID == nil {
			out = map[string]any{"status": "pending"}
			if time.Now().After(expires) {
				out["status"] = "expired"
			}
			return nil
		}
		var d deviceIn
		json.Unmarshal(dev, &d)
		var username string
		if err := tx.QueryRow(r.Context(), `SELECT `+nicknameSQL+` FROM users WHERE id=$1`, *userID).Scan(&username); err != nil {
			return err
		}
		tokens, _, err := newSession(r.Context(), tx, *userID, d.DeviceID, "")
		if err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `DELETE FROM device_links WHERE id=$1`, id); err != nil {
			return err
		}
		out = map[string]any{"status": "approved", "session": tokens, "user_id": *userID, "username": username, "sealed": sealed}
		return nil
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, out)
}

func (s *Server) handleQRDevice(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var dev []byte
	err := s.db.QueryRow(r.Context(), `SELECT device FROM device_links WHERE id=$1 AND kind='qr' AND approved_at IS NULL AND expires_at>now()`,
		nullUUID(r.PathValue("id"))).Scan(&dev)
	if errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, errf(404, "qr_expired", "QR-код устарел. Обновите его на компьютере"))
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var d deviceIn
	json.Unmarshal(dev, &d)
	writeJSON(w, 200, map[string]any{"device_id": d.DeviceID, "name": d.Name, "platform": d.Platform, "identity_key": d.IdentityKey, "auth_key": d.AuthKey})
}

func (s *Server) handleQRApprove(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var body struct {
		Roster rosterIn `json:"roster"`
		Sealed []byte   `json:"sealed"`
	}
	if err := readJSON(w, r, 32<<10, &body); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(body.Sealed) == 0 || len(body.Sealed) > maxBackup {
		s.fail(w, r, errBadRequest)
		return
	}
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		var dev []byte
		err := tx.QueryRow(r.Context(), `SELECT device FROM device_links WHERE id=$1 AND kind='qr' AND approved_at IS NULL AND expires_at>now() FOR UPDATE`,
			nullUUID(r.PathValue("id"))).Scan(&dev)
		if errors.Is(err, pgx.ErrNoRows) {
			return errf(404, "qr_expired", "QR-код устарел. Обновите его на компьютере")
		}
		if err != nil {
			return err
		}
		var d deviceIn
		if err := json.Unmarshal(dev, &d); err != nil {
			return err
		}
		if notify, err = s.addDevice(r, tx, a.UserID, &d, &body.Roster); err != nil {
			return err
		}
		_, err = tx.Exec(r.Context(), `UPDATE device_links SET approved_at=now(), user_id=$2, sealed=$3 WHERE id=$1`, r.PathValue("id"), a.UserID, body.Sealed)
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	w.WriteHeader(204)
}

// ---- account settings ----

func (s *Server) handleGetSettings(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var twoFactor, showLastSeen bool
	if err := s.db.QueryRow(r.Context(), `SELECT two_factor, show_last_seen FROM users WHERE id=$1`, a.UserID).Scan(&twoFactor, &showLastSeen); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"two_factor": twoFactor, "show_last_seen": showLastSeen})
}

func (s *Server) handlePutSettings(w http.ResponseWriter, r *http.Request, a *authCtx) {
	// Absent fields keep their value.
	var req struct {
		TwoFactor    *bool `json:"two_factor"`
		ShowLastSeen *bool `json:"show_last_seen"`
	}
	if err := readJSON(w, r, 4<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if _, err := s.db.Exec(r.Context(), `UPDATE users SET two_factor=COALESCE($2, two_factor), show_last_seen=COALESCE($3, show_last_seen) WHERE id=$1`,
		a.UserID, req.TwoFactor, req.ShowLastSeen); err != nil {
		s.fail(w, r, err)
		return
	}
	s.handleGetSettings(w, r, a)
}

// ---- encrypted history copy: an opaque blob the server cannot read ----

func (s *Server) historyPath(userID string) string {
	return filepath.Join(s.cfg.DataDir, "backups", userID)
}

func (s *Server) handlePutHistory(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.limits.allow("history:"+a.UserID, 30, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	if r.ContentLength > maxHistory {
		s.fail(w, r, errf(413, "too_large", "Переписка слишком большая для копии"))
		return
	}
	dir := filepath.Join(s.cfg.DataDir, "backups")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		s.fail(w, r, err)
		return
	}
	tmp, err := os.CreateTemp(dir, a.UserID+".*.tmp")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	defer os.Remove(tmp.Name())
	_, err = io.Copy(tmp, http.MaxBytesReader(w, r.Body, maxHistory))
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			err = errf(413, "too_large", "Переписка слишком большая для копии")
		}
		s.fail(w, r, err)
		return
	}
	if err := os.Rename(tmp.Name(), s.historyPath(a.UserID)); err != nil {
		s.fail(w, r, err)
		return
	}
	// A device that has just signed in picks up messages newer than the copy it restored.
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		rows, err := tx.Query(r.Context(), `SELECT id FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND id<>$2`, a.UserID, a.DeviceID)
		if err != nil {
			return err
		}
		devices, err := pgx.CollectRows(rows, pgx.RowTo[string])
		if err != nil {
			return err
		}
		notify, err = emitToDevices(r.Context(), tx, devices, "history", nil)
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	w.WriteHeader(204)
}

func (s *Server) handleGetHistory(w http.ResponseWriter, r *http.Request, a *authCtx) {
	f, err := os.Open(s.historyPath(a.UserID))
	if errors.Is(err, os.ErrNotExist) {
		s.fail(w, r, errNotFound)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	io.Copy(w, f)
}
