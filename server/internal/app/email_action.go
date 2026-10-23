package app

import (
	"encoding/base64"
	"html/template"
	"net/http"
	"net/url"
	"time"
)

// Firebase templates send users straight here. Only local continuation paths
// are accepted; neither continueUrl nor apiKey controls an outbound request.
func (s *Server) handleEmailAction(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	fail := func() {
		nonce := randomToken()
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'nonce-"+nonce+"'; img-src data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
		w.WriteHeader(http.StatusBadRequest)
		_ = emailPage.Execute(w, emailPageData{Title: "Не удалось открыть ссылку", Message: "Ссылка повреждена, уже использована или срок её действия истёк. Вернитесь в NeoChat и запросите новое письмо.", Nonce: nonce, Logo: template.URL("data:image/png;base64," + base64.StdEncoding.EncodeToString(emailLogo))})
	}
	if s.cfg.Email == nil || !s.limits.allow("email-action:"+clientIP(r), 60, time.Minute) {
		fail()
		return
	}
	values := r.URL.Query()
	if r.Method == http.MethodPost {
		r.Body = http.MaxBytesReader(w, r.Body, 8<<10)
		if r.ParseForm() != nil {
			fail()
			return
		}
		values = r.PostForm
	}
	mode, code, token := values.Get("mode"), values.Get("oobCode"), values.Get("t")
	if mode != "verifyEmail" && mode != "resetPassword" || len(code) == 0 || len(code) > 2048 || r.Method == http.MethodPost && mode != "verifyEmail" {
		fail()
		return
	}
	if r.Method == http.MethodGet {
		continuation, err := url.Parse(values.Get("continueUrl"))
		base, baseErr := url.Parse(s.cfg.EmailBaseURL)
		path := "/v1/accounts/email/confirm"
		if mode == "resetPassword" {
			path = "/v1/accounts/password-reset/confirm"
		}
		if err != nil || baseErr != nil || continuation.Scheme != base.Scheme || continuation.Host != base.Host || continuation.User != nil || continuation.Fragment != "" || continuation.Path != path {
			fail()
			return
		}
		token = continuation.Query().Get("t")
	}
	if len(token) != 43 {
		fail()
		return
	}
	r.URL.RawQuery = url.Values{"t": {token}}.Encode()
	if mode == "verifyEmail" {
		s.emailConfirm(w, r, code)
		return
	}
	var email string
	if err := s.db.QueryRow(r.Context(), `SELECT email FROM password_resets WHERE link_hash=$1 AND sent_at IS NOT NULL AND consumed_at IS NULL AND expires_at>now()`, emailHash(token)).Scan(&email); err != nil {
		fail()
		return
	}
	address, err := s.cfg.Email.CompleteAction(r.Context(), mode, code)
	if err != nil || address != email {
		fail()
		return
	}
	s.handlePasswordResetConfirm(w, r)
}
