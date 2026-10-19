package app

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const linkTTL = 5 * time.Minute

type deviceOut struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	Platform   string    `json:"platform"`
	Primary    bool      `json:"primary"`
	CreatedAt  time.Time `json:"created_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
	Current    bool      `json:"current"`
}

func (s *Server) handleListDevices(w http.ResponseWriter, r *http.Request, a *authCtx) {
	rows, err := s.db.Query(r.Context(), `SELECT id, name, platform, is_primary, created_at, last_seen_at FROM devices
		WHERE user_id=$1 AND revoked_at IS NULL ORDER BY created_at`, a.UserID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (deviceOut, error) {
		var d deviceOut
		err := row.Scan(&d.ID, &d.Name, &d.Platform, &d.Primary, &d.CreatedAt, &d.LastSeenAt)
		d.Current = d.ID == a.DeviceID
		return d, err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"devices": out})
}

// handleRevokeDevice: any device may revoke itself; only the primary revokes others and
// must publish the new roster in the same request. The commit happens before the WS is cut.
func (s *Server) handleRevokeDevice(w http.ResponseWriter, r *http.Request, a *authCtx) {
	target := r.PathValue("id")
	var body struct {
		Roster *rosterIn `json:"roster"`
	}
	if r.ContentLength != 0 {
		if err := readJSON(w, r, 16<<10, &body); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	if target != a.DeviceID && !a.Primary {
		s.fail(w, r, errForbidden)
		return
	}
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		var owner string
		var primary bool
		err := tx.QueryRow(r.Context(), `SELECT user_id, is_primary FROM devices WHERE id=$1 AND revoked_at IS NULL`, nullUUID(target)).Scan(&owner, &primary)
		if errors.Is(err, pgx.ErrNoRows) || (err == nil && owner != a.UserID) {
			return errNotFound
		}
		if err != nil {
			return err
		}
		if primary && target != a.DeviceID {
			return errForbidden
		}
		if target != a.DeviceID {
			if body.Roster == nil {
				return errf(400, "roster_required", "Нужен новый подписанный список устройств")
			}
			if err := updateRoster(r, tx, a.UserID, body.Roster); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(r.Context(), `UPDATE devices SET revoked_at=now() WHERE id=$1`, target); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE device_id=$1 AND revoked_at IS NULL`, target); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `DELETE FROM one_time_prekeys WHERE device_id=$1`, target); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `DELETE FROM push_tokens WHERE device_id=$1`, target); err != nil {
			return err
		}
		notify, err = s.emitToRelated(r.Context(), tx, a.UserID, "roster", map[string]string{"user_id": a.UserID})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.disconnect(target)
	s.hub.notify(notify)
	w.WriteHeader(204)
}

func updateRoster(r *http.Request, tx pgx.Tx, userID string, ro *rosterIn) error {
	rev, err := validateRoster(ro)
	if err != nil {
		return err
	}
	tag, err := tx.Exec(r.Context(), `UPDATE users SET roster_revision=$2, roster_blob=$3, roster_sig=$4 WHERE id=$1 AND roster_revision<$2`,
		userID, rev, ro.Blob, ro.Signature)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errf(409, "roster_revision_conflict", "Список устройств изменился, обновите его")
	}
	return nil
}

func (s *Server) handleUpdateRoster(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !a.Primary {
		s.fail(w, r, errForbidden)
		return
	}
	var body struct {
		Roster rosterIn `json:"roster"`
	}
	if err := readJSON(w, r, 16<<10, &body); err != nil {
		s.fail(w, r, err)
		return
	}
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		if _, err := tx.Exec(r.Context(), `SELECT 1 FROM users WHERE id=$1 FOR UPDATE`, a.UserID); err != nil {
			return err
		}
		if err := updateRoster(r, tx, a.UserID, &body.Roster); err != nil {
			return err
		}
		var err error
		notify, err = s.emitToRelated(r.Context(), tx, a.UserID, "roster", map[string]string{"user_id": a.UserID})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	w.WriteHeader(204)
}

type prekeyUpload struct {
	OneTimePrekeys      []prekeyIn `json:"one_time_prekeys"`
	KyberOneTimePrekeys []prekeyIn `json:"kyber_one_time_prekeys"`
	SignedPrekey        *prekeyIn  `json:"signed_prekey,omitempty"`
	KyberPrekey         *prekeyIn  `json:"kyber_prekey,omitempty"`
}

func (s *Server) handleUploadPrekeys(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.limits.allow("prekeys:"+a.DeviceID, 60, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var req prekeyUpload
	if err := readJSON(w, r, 512<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(req.OneTimePrekeys) > maxPrekeysReq || len(req.KyberOneTimePrekeys) > maxPrekeysReq {
		s.fail(w, r, errBadRequest)
		return
	}
	if err := validatePrekeys(req.OneTimePrekeys, req.KyberOneTimePrekeys); err != nil {
		s.fail(w, r, err)
		return
	}
	if (req.SignedPrekey != nil && (len(req.SignedPrekey.PublicKey) != identityLen || len(req.SignedPrekey.Signature) != signatureLen)) ||
		(req.KyberPrekey != nil && (len(req.KyberPrekey.PublicKey) != kyberKeyLen || len(req.KyberPrekey.Signature) != signatureLen)) {
		s.fail(w, r, errBadRequest)
		return
	}
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		if req.SignedPrekey != nil {
			if _, err := tx.Exec(r.Context(), `UPDATE devices SET signed_prekey_id=$2, signed_prekey=$3, signed_prekey_sig=$4 WHERE id=$1`,
				a.DeviceID, int64(req.SignedPrekey.ID), req.SignedPrekey.PublicKey, req.SignedPrekey.Signature); err != nil {
				return err
			}
		}
		if req.KyberPrekey != nil {
			if _, err := tx.Exec(r.Context(), `UPDATE devices SET kyber_prekey_id=$2, kyber_prekey=$3, kyber_prekey_sig=$4 WHERE id=$1`,
				a.DeviceID, int64(req.KyberPrekey.ID), req.KyberPrekey.PublicKey, req.KyberPrekey.Signature); err != nil {
				return err
			}
		}
		return insertPrekeys(r.Context(), tx, a.DeviceID, req.OneTimePrekeys, req.KyberOneTimePrekeys)
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) handlePrekeyCount(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var ec, ky int
	err := s.db.QueryRow(r.Context(), `SELECT count(*) FILTER (WHERE kind=0), count(*) FILTER (WHERE kind=1) FROM one_time_prekeys WHERE device_id=$1`, a.DeviceID).Scan(&ec, &ky)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]int{"one_time_prekeys": ec, "kyber_one_time_prekeys": ky})
}

func (s *Server) handlePushToken(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		Provider    string `json:"provider"`
		Token       string `json:"token"`
		Environment string `json:"environment"`
	}
	if err := readJSON(w, r, 8<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if (req.Provider != "fcm" && req.Provider != "apns") || len(req.Token) == 0 || len(req.Token) > 4096 ||
		(req.Provider == "apns" && req.Environment != "production" && req.Environment != "development") {
		s.fail(w, r, errBadRequest)
		return
	}
	_, err := s.db.Exec(r.Context(), `INSERT INTO push_tokens(device_id, provider, token, environment) VALUES ($1,$2,$3,$4)
		ON CONFLICT (device_id) DO UPDATE SET provider=EXCLUDED.provider, token=EXCLUDED.token, environment=EXCLUDED.environment, updated_at=now()`,
		a.DeviceID, req.Provider, req.Token, req.Environment)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(204)
}

// ---- linking a new device: password gives only a pending link, the primary approves ----

func (s *Server) handleLinkStart(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string   `json:"username"`
		Password string   `json:"password"`
		Device   deviceIn `json:"device"`
	}
	if err := readJSON(w, r, 256<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	req.Username = strings.ToLower(strings.TrimSpace(req.Username))
	if !s.limits.allow("login-ip:"+clientIP(r), 30, 15*time.Minute) || !s.limits.allow("login-user:"+req.Username, 10, 15*time.Minute) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	if err := validateDevice(&req.Device); err != nil {
		s.fail(w, r, err)
		return
	}
	invalid := errf(401, "invalid_credentials", "Неверное имя пользователя или пароль")
	var userID, pwHash string
	err := s.db.QueryRow(r.Context(), `SELECT id, password_hash FROM users WHERE username=$1`, req.Username).Scan(&userID, &pwHash)
	if errors.Is(err, pgx.ErrNoRows) {
		s.checkPassword(r.Context(), dummyHash, req.Password)
		s.fail(w, r, invalid)
		return
	}
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
	var n int
	if err := s.db.QueryRow(r.Context(), `SELECT count(*) FROM devices WHERE user_id=$1 AND revoked_at IS NULL`, userID).Scan(&n); err != nil {
		s.fail(w, r, err)
		return
	}
	if n >= maxDevices {
		s.fail(w, r, errf(409, "device_limit", "У аккаунта уже 3 устройства. Отзовите одно из них"))
		return
	}
	dev, _ := json.Marshal(req.Device)
	id, secret := newUUID(), randomToken()
	if _, err := s.db.Exec(r.Context(), `INSERT INTO device_links(id, user_id, secret_hash, device, expires_at) VALUES ($1,$2,$3,$4,$5)`,
		id, userID, tokenHash(secret), dev, time.Now().Add(linkTTL)); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 201, map[string]any{"link_id": id, "link_secret": secret, "user_id": userID, "expires_in": int(linkTTL.Seconds())})
}

// handleLinkPoll is called by the new device. After approval it receives its first session once.
func (s *Server) handleLinkPoll(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	secret := r.Header.Get("X-Link-Secret")
	var out map[string]any
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		var userID string
		var hash, dev []byte
		var expires time.Time
		var approved *time.Time
		err := tx.QueryRow(r.Context(), `SELECT user_id, secret_hash, device, expires_at, approved_at FROM device_links WHERE id=$1 FOR UPDATE`, nullUUID(id)).
			Scan(&userID, &hash, &dev, &expires, &approved)
		if errors.Is(err, pgx.ErrNoRows) || (err == nil && subtle.ConstantTimeCompare(hash, tokenHash(secret)) != 1) {
			return errNotFound
		}
		if err != nil {
			return err
		}
		if approved == nil {
			if time.Now().After(expires) {
				out = map[string]any{"status": "expired"}
			} else {
				out = map[string]any{"status": "pending"}
			}
			return nil
		}
		var d deviceIn
		json.Unmarshal(dev, &d)
		tokens, _, err := newSession(r.Context(), tx, userID, d.DeviceID, "")
		if err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `DELETE FROM device_links WHERE id=$1`, id); err != nil {
			return err
		}
		out = map[string]any{"status": "approved", "session": tokens}
		return nil
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, out)
}

func (s *Server) handleLinkGet(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !a.Primary {
		s.fail(w, r, errForbidden)
		return
	}
	var dev []byte
	err := s.db.QueryRow(r.Context(), `SELECT device FROM device_links WHERE id=$1 AND user_id=$2 AND approved_at IS NULL AND expires_at>now()`,
		nullUUID(r.PathValue("id")), a.UserID).Scan(&dev)
	if errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, errNotFound)
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

func (s *Server) handleLinkApprove(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !a.Primary {
		s.fail(w, r, errForbidden)
		return
	}
	var body struct {
		Roster rosterIn `json:"roster"`
	}
	if err := readJSON(w, r, 16<<10, &body); err != nil {
		s.fail(w, r, err)
		return
	}
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		if _, err := tx.Exec(r.Context(), `SELECT 1 FROM users WHERE id=$1 FOR UPDATE`, a.UserID); err != nil {
			return err
		}
		var dev []byte
		err := tx.QueryRow(r.Context(), `SELECT device FROM device_links WHERE id=$1 AND user_id=$2 AND approved_at IS NULL AND expires_at>now() FOR UPDATE`,
			nullUUID(r.PathValue("id")), a.UserID).Scan(&dev)
		if errors.Is(err, pgx.ErrNoRows) {
			return errNotFound
		}
		if err != nil {
			return err
		}
		var n int
		if err := tx.QueryRow(r.Context(), `SELECT count(*) FROM devices WHERE user_id=$1 AND revoked_at IS NULL`, a.UserID).Scan(&n); err != nil {
			return err
		}
		if n >= maxDevices {
			return errf(409, "device_limit", "У аккаунта уже 3 устройства")
		}
		var d deviceIn
		if err := json.Unmarshal(dev, &d); err != nil {
			return err
		}
		if err := updateRoster(r, tx, a.UserID, &body.Roster); err != nil {
			return err
		}
		if err := insertDevice(r.Context(), tx, a.UserID, &d, false); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `UPDATE device_links SET approved_at=now() WHERE id=$1`, r.PathValue("id")); err != nil {
			return err
		}
		notify, err = s.emitToRelated(r.Context(), tx, a.UserID, "roster", map[string]string{"user_id": a.UserID})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	w.WriteHeader(204)
}

// ---- bundles ----

type bundleOut struct {
	DeviceID       string    `json:"device_id"`
	RegistrationID int32     `json:"registration_id"`
	IdentityKey    []byte    `json:"identity_key"`
	SignedPrekey   prekeyIn  `json:"signed_prekey"`
	KyberPrekey    prekeyIn  `json:"kyber_prekey"`
	OneTimePrekey  *prekeyIn `json:"one_time_prekey,omitempty"`
}

// related reports whether viewer may read target's keys: self, a resolved contact
// or a co-member of a conversation. Strangers get the same 404 as missing users.
func (s *Server) related(r *http.Request, viewer, target string) (bool, error) {
	if viewer == target {
		return true, nil
	}
	var ok bool
	err := s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM contacts WHERE owner_id=$1 AND contact_id=$2)
		OR EXISTS(SELECT 1 FROM memberships m1 JOIN memberships m2 ON m1.conversation_id=m2.conversation_id WHERE m1.user_id=$1 AND m2.user_id=$2)`,
		viewer, nullUUID(target)).Scan(&ok)
	return ok, err
}

func (s *Server) handleClaimBundles(w http.ResponseWriter, r *http.Request, a *authCtx) {
	target := r.PathValue("id")
	if !s.limits.allow("claim:"+a.DeviceID, 300, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var req struct {
		DeviceIDs []string `json:"device_ids"`
	}
	if r.ContentLength != 0 {
		if err := readJSON(w, r, 8<<10, &req); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	ok, err := s.related(r, a.UserID, target)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !ok {
		s.fail(w, r, errNotFound)
		return
	}
	var out []bundleOut
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		out = nil
		rows, err := tx.Query(r.Context(), `SELECT id, registration_id, identity_key, signed_prekey_id, signed_prekey, signed_prekey_sig, kyber_prekey_id, kyber_prekey, kyber_prekey_sig
			FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND id<>$2 ORDER BY id`, target, a.DeviceID)
		if err != nil {
			return err
		}
		for rows.Next() {
			var b bundleOut
			var spid, kpid int64
			if err := rows.Scan(&b.DeviceID, &b.RegistrationID, &b.IdentityKey, &spid, &b.SignedPrekey.PublicKey, &b.SignedPrekey.Signature,
				&kpid, &b.KyberPrekey.PublicKey, &b.KyberPrekey.Signature); err != nil {
				rows.Close()
				return err
			}
			b.SignedPrekey.ID, b.KyberPrekey.ID = uint32(spid), uint32(kpid)
			if len(req.DeviceIDs) == 0 || contains(req.DeviceIDs, b.DeviceID) {
				out = append(out, b)
			}
		}
		rows.Close()
		for i := range out {
			// One-time prekeys are handed out exactly once.
			var id int64
			var pub, sig []byte
			err := tx.QueryRow(r.Context(), `DELETE FROM one_time_prekeys WHERE (device_id, kind, key_id) = (
				SELECT device_id, kind, key_id FROM one_time_prekeys WHERE device_id=$1 AND kind=0 ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED)
				RETURNING key_id, public_key`, out[i].DeviceID).Scan(&id, &pub)
			if err == nil {
				out[i].OneTimePrekey = &prekeyIn{ID: uint32(id), PublicKey: pub}
			} else if !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
			err = tx.QueryRow(r.Context(), `DELETE FROM one_time_prekeys WHERE (device_id, kind, key_id) = (
				SELECT device_id, kind, key_id FROM one_time_prekeys WHERE device_id=$1 AND kind=1 ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED)
				RETURNING key_id, public_key, signature`, out[i].DeviceID).Scan(&id, &pub, &sig)
			if err == nil {
				out[i].KyberPrekey = prekeyIn{ID: uint32(id), PublicKey: pub, Signature: sig}
			} else if !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
		}
		return nil
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if out == nil {
		out = []bundleOut{}
	}
	writeJSON(w, 200, map[string]any{"bundles": out})
}

func contains(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}
