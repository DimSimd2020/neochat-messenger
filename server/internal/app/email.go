package app

import (
	"crypto/sha256"
	_ "embed"
	"encoding/base64"
	"errors"
	"html/template"
	"net/http"
	"net/mail"
	"net/url"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

func emailHash(value string) []byte { sum := sha256.Sum256([]byte(value)); return sum[:] }

func normalizeEmail(value string) (string, error) {
	value = strings.TrimSpace(value)
	parsed, err := mail.ParseAddress(value)
	if err != nil || parsed.Name != "" || parsed.Address != value || len(value) > 254 || strings.ContainsAny(value, "\r\n") {
		return "", errf(400, "bad_email", "Введите корректный адрес электронной почты")
	}
	for _, c := range value {
		if c < 33 || c > 126 {
			return "", errf(400, "bad_email", "Используйте адрес почты латиницей")
		}
	}
	return strings.ToLower(value), nil
}

func (s *Server) handleRegistrationEmail(w http.ResponseWriter, r *http.Request) {
	s.startEmail(w, r, nil)
}
func (s *Server) handleLinkEmail(w http.ResponseWriter, r *http.Request, a *authCtx) {
	s.startEmail(w, r, a)
}

func (s *Server) startEmail(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if s.cfg.Email == nil || s.cfg.EmailBaseURL == "" {
		s.fail(w, r, errf(503, "email_unavailable", "Подтверждение почты временно недоступно"))
		return
	}
	var req struct {
		Email string `json:"email"`
	}
	if err := readJSON(w, r, 2<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	email, err := normalizeEmail(req.Email)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !s.limits.allow("email-ip:"+clientIP(r), 6, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var owner, device any
	if a != nil {
		owner, device = a.UserID, a.DeviceID
	}
	id, ticket, link := newUUID(), randomToken(), randomToken()
	expires := time.Now().Add(30 * time.Minute)
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		// The database cooldown survives restarts and serializes parallel requests.
		if _, err := tx.Exec(r.Context(), `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, "email:"+email); err != nil {
			return err
		}
		var recent int
		var cooling bool
		if err := tx.QueryRow(r.Context(), `SELECT count(*),COALESCE(max(created_at)>now()-interval '60 seconds',false) FROM email_links WHERE email=$1 AND created_at>now()-interval '1 hour'`, email).Scan(&recent, &cooling); err != nil {
			return err
		}
		if recent >= 4 || cooling {
			return errRateLimited(time.Minute)
		}
		if a != nil {
			var linked bool
			if err := tx.QueryRow(r.Context(), `SELECT email IS NOT NULL FROM users WHERE id=$1 FOR UPDATE`, a.UserID).Scan(&linked); err != nil {
				return err
			}
			if linked {
				return errf(409, "email_already_linked", "Почта уже привязана")
			}
			if _, err := tx.Exec(r.Context(), `UPDATE email_links SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL`, a.UserID); err != nil {
				return err
			}
		}
		_, err := tx.Exec(r.Context(), `INSERT INTO email_links(id,email,user_id,device_id,ticket_hash,link_hash,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7)`, id, email, owner, device, emailHash(ticket), emailHash(link), expires)
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	continuation := strings.TrimRight(s.cfg.EmailBaseURL, "/") + "/v1/accounts/email/confirm?t=" + url.QueryEscape(link)
	if err := s.cfg.Email.SendVerification(r.Context(), email, continuation); err != nil {
		_, _ = s.db.Exec(r.Context(), `DELETE FROM email_links WHERE id=$1`, id)
		s.fail(w, r, errf(503, "email_send_failed", "Не удалось отправить письмо. Попробуйте позже"))
		return
	}
	if _, err := s.db.Exec(r.Context(), `UPDATE email_links SET sent_at=now() WHERE id=$1`, id); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"ticket": ticket, "email": email, "expires_in": 1800, "resend_after": 60})
}

func (s *Server) handleEmailTicket(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Ticket string `json:"ticket"`
	}
	if err := readJSON(w, r, 2<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(req.Ticket) != 43 {
		s.fail(w, r, errBadRequest)
		return
	}
	if !s.limits.allow("email-poll:"+clientIP(r), 120, time.Minute) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var email string
	var confirmed bool
	err := s.db.QueryRow(r.Context(), `SELECT email,confirmed_at IS NOT NULL FROM email_links WHERE ticket_hash=$1 AND sent_at IS NOT NULL AND consumed_at IS NULL AND expires_at>now()`, emailHash(req.Ticket)).Scan(&email, &confirmed)
	if errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, errf(410, "email_link_expired", "Срок подтверждения истёк. Отправьте новое письмо"))
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"email": email, "verified": confirmed})
}

func (s *Server) handleEmailStatus(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var email *string
	if err := s.db.QueryRow(r.Context(), `SELECT email FROM users WHERE id=$1`, a.UserID).Scan(&email); err != nil {
		s.fail(w, r, err)
		return
	}
	value := ""
	if email != nil {
		value = *email
	}
	writeJSON(w, 200, map[string]any{"email": value, "verified": email != nil})
}

//go:embed email_page.html
var emailPageHTML string

//go:embed email_logo.png
var emailLogo []byte

var emailPage = template.Must(template.New("email").Parse(emailPageHTML))

type emailPageData struct {
	Title, Message, Token, Address, Nonce string
	FirebaseCode                          string
	Logo                                  template.URL
	Success                               bool
}

func (s *Server) handleEmailConfirm(w http.ResponseWriter, r *http.Request) {
	s.emailConfirm(w, r, "")
}

func (s *Server) emailConfirm(w http.ResponseWriter, r *http.Request, firebaseCode string) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Referrer-Policy", "no-referrer")
	nonce := randomToken()
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'nonce-"+nonce+"'; img-src data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
	token := r.URL.Query().Get("t")
	if r.Method == http.MethodPost {
		r.Body = http.MaxBytesReader(w, r.Body, 2<<10)
		if r.ParseForm() != nil {
			w.WriteHeader(400)
			return
		}
		token = r.PostForm.Get("t")
	}
	data := emailPageData{Title: "Ссылка больше не действует", Message: "Возможно, вы уже подтвердили почту или срок ссылки истёк. Вернитесь в NeoChat, проверьте подтверждение или запросите новое письмо.", Nonce: nonce, Logo: template.URL("data:image/png;base64," + base64.StdEncoding.EncodeToString(emailLogo))}
	if len(token) != 43 {
		w.WriteHeader(400)
		_ = emailPage.Execute(w, data)
		return
	}
	var owner, device *string
	var id, email, nickname string
	err := s.db.QueryRow(r.Context(), `SELECT e.id,e.email,e.user_id,e.device_id,COALESCE(u.nickname,'') FROM email_links e LEFT JOIN users u ON u.id=e.user_id WHERE link_hash=$1 AND sent_at IS NOT NULL AND consumed_at IS NULL AND expires_at>now()`, emailHash(token)).Scan(&id, &email, &owner, &device, &nickname)
	if err != nil {
		w.WriteHeader(400)
		_ = emailPage.Execute(w, data)
		return
	}
	data.Title = "Подтвердите почту"
	data.Message = "После подтверждения вернитесь в NeoChat, чтобы продолжить создание аккаунта."
	data.Address = email
	if owner != nil {
		data.Message = "Привяжем этот адрес к вашему аккаунту @" + nickname + "."
	}
	data.Token = token
	data.FirebaseCode = firebaseCode
	if r.Method == http.MethodPost {
		if firebaseCode != "" {
			var address string
			address, err = s.cfg.Email.CompleteAction(r.Context(), "verifyEmail", firebaseCode)
			if err == nil && address != email {
				err = errBadRequest
			}
		}
		if err == nil {
			err = s.inTx(r.Context(), func(tx pgx.Tx) error {
				var consumed, confirmed bool
				if err := tx.QueryRow(r.Context(), `SELECT consumed_at IS NOT NULL,confirmed_at IS NOT NULL FROM email_links WHERE id=$1 AND expires_at>now() FOR UPDATE`, id).Scan(&consumed, &confirmed); err != nil {
					return err
				}
				if consumed {
					return errBadRequest
				}
				if owner != nil {
					var active bool
					if err := tx.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM devices WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL)`, device, owner).Scan(&active); err != nil {
						return err
					}
					if !active {
						return errBadRequest
					}
					result, err := tx.Exec(r.Context(), `UPDATE users SET email=$2,email_verified_at=now() WHERE id=$1 AND email IS NULL`, owner, email)
					if err != nil {
						return err
					}
					if result.RowsAffected() != 1 {
						return errBadRequest
					}
				}
				_, err := tx.Exec(r.Context(), `UPDATE email_links SET confirmed_at=now(),consumed_at=CASE WHEN user_id IS NULL THEN NULL ELSE now() END WHERE id=$1`, id)
				return err
			})
		}
		data.Token = ""
		if err != nil {
			data.Title = "Не удалось привязать почту"
			data.Message = "Ссылка истекла, устройство отозвано или адрес уже используется. Вернитесь в NeoChat и повторите действие."
			w.WriteHeader(409)
		} else {
			data.Success = true
			data.Title = "Почта подтверждена"
			data.Message = "Вернитесь в приложение и нажмите «Я подтвердил почту», чтобы продолжить."
		}
	}
	_ = emailPage.Execute(w, data)
}

func registrationEmail(tx pgx.Tx, r *http.Request, ticket string) (string, error) {
	if len(ticket) != 43 {
		return "", errf(400, "email_required", "Для регистрации подтвердите электронную почту")
	}
	var id, email string
	err := tx.QueryRow(r.Context(), `SELECT id,email FROM email_links WHERE ticket_hash=$1 AND user_id IS NULL AND sent_at IS NOT NULL AND confirmed_at IS NOT NULL AND consumed_at IS NULL AND expires_at>now() FOR UPDATE`, emailHash(ticket)).Scan(&id, &email)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", errf(400, "email_required", "Для регистрации подтвердите электронную почту")
	}
	if err != nil {
		return "", err
	}
	_, err = tx.Exec(r.Context(), `UPDATE email_links SET consumed_at=now() WHERE id=$1`, id)
	return email, err
}
