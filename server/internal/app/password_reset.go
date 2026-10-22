package app

import (
	_ "embed"
	"encoding/base64"
	"errors"
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const passwordResetTTL = 30 * time.Minute

//go:embed password_reset_page.html
var passwordResetPageHTML string

var passwordResetPage = template.Must(template.New("password_reset").Parse(passwordResetPageHTML))

type passwordResetPageData struct {
	Title, Message, Token, Address, Nonce string
	Logo                                  template.URL
	Success                               bool
}

func looksLikeEmail(s string) bool { return strings.Contains(s, "@") }

func normalizeLogin(raw string) string {
	s := strings.TrimSpace(raw)
	if looksLikeEmail(s) {
		if email, err := normalizeEmail(s); err == nil {
			return email
		}
		return strings.ToLower(s)
	}
	return strings.ToLower(strings.TrimPrefix(s, "@"))
}

// lookupVerifiedLogin resolves a nickname or verified email to the account row.
func (s *Server) lookupVerifiedLogin(r *http.Request, login string) (userID, username, pwHash string, ok bool, err error) {
	login = normalizeLogin(login)
	if login == "" {
		return "", "", "", false, nil
	}
	if looksLikeEmail(login) {
		err = s.db.QueryRow(r.Context(), `SELECT id, username, password_hash FROM users WHERE email=$1 AND email_verified_at IS NOT NULL`, login).
			Scan(&userID, &username, &pwHash)
	} else {
		err = s.db.QueryRow(r.Context(), `SELECT id, username, password_hash FROM users WHERE username=$1`, login).
			Scan(&userID, &username, &pwHash)
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return "", "", "", false, nil
	}
	if err != nil {
		return "", "", "", false, err
	}
	return userID, username, pwHash, true, nil
}

func (s *Server) handlePasswordResetRequest(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Login string `json:"login"`
	}
	if err := readJSON(w, r, 2<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	login := normalizeLogin(req.Login)
	ack := map[string]string{"status": "ok", "message": "Если аккаунт с этой почтой или ником существует и почта привязана, мы отправили ссылку для сброса"}
	if !s.limits.allow("reset-ip:"+clientIP(r), 6, time.Hour) || !s.limits.allow("reset-login:"+login, 4, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	defer writeJSON(w, 200, ack)
	if login == "" || s.cfg.Email == nil || s.cfg.EmailBaseURL == "" {
		return
	}
	var userID, email string
	var err error
	if looksLikeEmail(login) {
		err = s.db.QueryRow(r.Context(), `SELECT id, email FROM users WHERE email=$1 AND email_verified_at IS NOT NULL`, login).Scan(&userID, &email)
	} else if usernameRe.MatchString(login) {
		err = s.db.QueryRow(r.Context(), `SELECT id, email FROM users WHERE username=$1 AND email IS NOT NULL AND email_verified_at IS NOT NULL`, login).Scan(&userID, &email)
	} else {
		return
	}
	if errors.Is(err, pgx.ErrNoRows) || err != nil || email == "" {
		return
	}
	id, link := newUUID(), randomToken()
	expires := time.Now().Add(passwordResetTTL)
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		if _, err := tx.Exec(r.Context(), `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, "reset:"+email); err != nil {
			return err
		}
		var recent int
		var cooling bool
		if err := tx.QueryRow(r.Context(), `SELECT count(*),COALESCE(max(created_at)>now()-interval '60 seconds',false) FROM password_resets WHERE email=$1 AND created_at>now()-interval '1 hour'`, email).Scan(&recent, &cooling); err != nil {
			return err
		}
		if recent >= 4 || cooling {
			return errRateLimited(time.Minute)
		}
		if _, err := tx.Exec(r.Context(), `UPDATE password_resets SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL`, userID); err != nil {
			return err
		}
		_, err := tx.Exec(r.Context(), `INSERT INTO password_resets(id,user_id,email,link_hash,expires_at) VALUES($1,$2,$3,$4,$5)`,
			id, userID, email, emailHash(link), expires)
		return err
	})
	if err != nil {
		// Still return the generic ack; rate limits are the only client-visible failure above.
		return
	}
	continuation := strings.TrimRight(s.cfg.EmailBaseURL, "/") + "/v1/accounts/password-reset/confirm?t=" + url.QueryEscape(link)
	if err := s.cfg.Email.SendVerification(r.Context(), email, continuation); err != nil {
		_, _ = s.db.Exec(r.Context(), `DELETE FROM password_resets WHERE id=$1`, id)
		s.log.Error("password reset mail", "err", err)
		return
	}
	_, _ = s.db.Exec(r.Context(), `UPDATE password_resets SET sent_at=now() WHERE id=$1`, id)
}

func (s *Server) handlePasswordResetPrepare(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token string `json:"token"`
	}
	if err := readJSON(w, r, 2<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if !s.limits.allow("reset-prepare-ip:"+clientIP(r), 60, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	token := strings.TrimSpace(req.Token)
	if len(token) != 43 {
		s.fail(w, r, errf(401, "invalid_token", "Неверный или устаревший код"))
		return
	}
	var username, salt string
	err := s.db.QueryRow(r.Context(), `SELECT u.username, COALESCE(NULLIF(u.password_salt,''),u.username)
		FROM password_resets p JOIN users u ON u.id=p.user_id
		WHERE p.link_hash=$1 AND p.sent_at IS NOT NULL AND p.consumed_at IS NULL AND p.expires_at>now()`,
		emailHash(token)).Scan(&username, &salt)
	if errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, errf(401, "invalid_token", "Неверный или устаревший код"))
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]string{"username": username, "salt": salt})
}

// handlePasswordResetConfirm is the Firebase continueUrl page: shows the one-time
// token for the app. Setting the password happens in the client (Argon2/HKDF stay local).
func (s *Server) handlePasswordResetConfirm(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Cache-Control", "no-store")
	nonce := randomToken()
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'nonce-"+nonce+"'; img-src data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
	token := r.URL.Query().Get("t")
	data := passwordResetPageData{
		Title: "Ссылка больше не действует",
		Message: "Срок ссылки истёк или пароль уже сменён. Вернитесь в NeoChat и запросите сброс снова.",
		Nonce: nonce,
		Logo:  template.URL("data:image/png;base64," + base64.StdEncoding.EncodeToString(emailLogo)),
	}
	if len(token) != 43 {
		w.WriteHeader(400)
		_ = passwordResetPage.Execute(w, data)
		return
	}
	var email string
	err := s.db.QueryRow(r.Context(), `SELECT email FROM password_resets WHERE link_hash=$1 AND sent_at IS NOT NULL AND consumed_at IS NULL AND expires_at>now()`,
		emailHash(token)).Scan(&email)
	if err != nil {
		w.WriteHeader(400)
		_ = passwordResetPage.Execute(w, data)
		return
	}
	data.Title = "Сброс пароля"
	data.Message = "Откройте NeoChat → «Забыли пароль?» и введите код ниже вместе с новым паролем. Аккаунт, ключи и переписка на ваших устройствах сохраняются."
	data.Address = email
	data.Token = token
	_ = passwordResetPage.Execute(w, data)
}

// handlePasswordResetComplete changes only password_hash. Identity, roster, devices,
// key_backup and history are untouched. Sessions are revoked so devices re-auth.
func (s *Server) handlePasswordResetComplete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token       string `json:"token"`
		NewPassword string `json:"new_password"`
	}
	if err := readJSON(w, r, 8<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if !s.limits.allow("reset-complete-ip:"+clientIP(r), 20, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	token := strings.TrimSpace(req.Token)
	if len(token) != 43 {
		s.fail(w, r, errf(401, "invalid_token", "Неверный или устаревший код"))
		return
	}
	if err := validatePassword(req.NewPassword); err != nil {
		s.fail(w, r, err)
		return
	}
	pwHash, err := s.hashPassword(r.Context(), req.NewPassword)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		var id, userID string
		var attempts int
		err := tx.QueryRow(r.Context(), `SELECT id, user_id, attempts FROM password_resets
			WHERE link_hash=$1 AND sent_at IS NOT NULL AND consumed_at IS NULL AND expires_at>now() FOR UPDATE`,
			emailHash(token)).Scan(&id, &userID, &attempts)
		if errors.Is(err, pgx.ErrNoRows) {
			return errf(401, "invalid_token", "Неверный или устаревший код")
		}
		if err != nil {
			return err
		}
		if attempts >= 8 {
			_, _ = tx.Exec(r.Context(), `UPDATE password_resets SET consumed_at=now() WHERE id=$1`, id)
			return errf(401, "invalid_token", "Неверный или устаревший код")
		}
		if _, err := tx.Exec(r.Context(), `UPDATE password_resets SET attempts=attempts+1 WHERE id=$1`, id); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `UPDATE users SET password_hash=$2 WHERE id=$1`, userID, pwHash); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE revoked_at IS NULL AND device_id IN (SELECT id FROM devices WHERE user_id=$1)`, userID); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `UPDATE password_resets SET consumed_at=now() WHERE id=$1`, id); err != nil {
			return err
		}
		notify, err = notifyOwn(r.Context(), tx, userID, "", "Пароль аккаунта изменён. Если это не вы — восстановите доступ по коду восстановления и отзовите чужие устройства.")
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	writeJSON(w, 200, map[string]string{"status": "ok"})
}

func (s *Server) handlePutKeyBackup(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		KeyBackup []byte `json:"key_backup"`
	}
	if err := readJSON(w, r, 16<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(req.KeyBackup) == 0 || len(req.KeyBackup) > maxBackup {
		s.fail(w, r, errBadRequest)
		return
	}
	if _, err := s.db.Exec(r.Context(), `UPDATE users SET key_backup=$2 WHERE id=$1`, a.UserID, req.KeyBackup); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(204)
}
