package app

import (
	"context"
	"crypto/ed25519"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestLoginByNicknameAndVerifiedEmail(t *testing.T) {
	s, ts, _ := emailTestServer(t)
	a := register(t, s, ts, "login_compat")
	var email string
	if err := s.db.QueryRow(context.Background(), `SELECT email FROM users WHERE id=$1`, a.userID).Scan(&email); err != nil {
		t.Fatal(err)
	}
	c := &client{t: t, base: ts.URL}
	for _, login := range []string{"Login_Compat", strings.ToUpper(email)} {
		for _, request := range []map[string]string{{"username": login}, {"username": login, "login": login}} {
			var salt map[string]any
			if st, _ := c.do("POST", "/v1/accounts/password-salt", request, &salt); st != 200 || salt["username"] != "login_compat" || salt["salt"] != "login_compat" {
				t.Fatalf("password salt compatibility: status=%d", st)
			}
		}
		stamp := strconv.FormatInt(time.Now().Unix(), 10)
		sig := ed25519.Sign(a.authPriv, LoginChallenge("login_compat", a.deviceID, stamp))
		for _, password := range []string{"correct horse battery", "wrong password!"} {
			want := 200
			if password == "wrong password!" {
				want = 401
			}
			if st, _ := c.do("POST", "/v1/sessions", loginReq{Username: login, Password: password, DeviceID: a.deviceID, Timestamp: stamp, Signature: sig}, nil); st != want {
				t.Fatalf("existing-device login: got=%d want=%d", st, want)
			}
			if st, _ := c.do("POST", "/v1/devices/new/start", map[string]string{"username": login, "password": password}, nil); st != want {
				t.Fatalf("new-device login: got=%d want=%d", st, want)
			}
		}
	}
}

func TestPasswordResetAuthOnly(t *testing.T) {
	s, ts, mailbox := emailTestServer(t)
	a := register(t, s, ts, "reset_alice")
	var email string
	var identity []byte
	var epoch int64
	var backup []byte
	if err := s.db.QueryRow(context.Background(), `SELECT email, account_identity_key, identity_epoch, key_backup FROM users WHERE id=$1`, a.userID).
		Scan(&email, &identity, &epoch, &backup); err != nil || email == "" {
		t.Fatal("registered account missing verified email")
	}
	c := &client{t: t, base: ts.URL}
	if st, body := c.do("POST", "/v1/accounts/password-reset/request", map[string]string{"login": "reset_alice"}, nil); st != 200 || body["status"] != "ok" {
		t.Fatalf("reset request by nick: %d %v", st, body)
	}
	link := mailbox.latest()
	if !mailbox.reset {
		t.Fatal("password reset used verification email template")
	}
	if !strings.Contains(link, "/v1/accounts/password-reset/confirm?t=") {
		t.Fatal("reset mail did not use password-reset continueUrl")
	}
	if strings.Contains(link, "/v1/accounts/email/confirm") {
		t.Fatal("reset reused email-confirm path")
	}
	token := link[strings.LastIndex(link, "t=")+2:]
	token, _ = url.QueryUnescape(token)
	resp, err := http.Get(link)
	if err != nil || resp.StatusCode != 200 {
		t.Fatal("reset landing page failed")
	}
	resp.Body.Close()

	var prep map[string]any
	if st, _ := c.do("POST", "/v1/accounts/password-reset/prepare", map[string]string{"token": token}, &prep); st != 200 {
		t.Fatal("prepare failed")
	}
	if prep["username"] != "reset_alice" || prep["salt"] == "" {
		t.Fatalf("prepare missing canonical username/salt: %v", prep)
	}
	if st, _ := c.do("POST", "/v1/accounts/password-reset/complete", map[string]string{
		"token": token, "new_password": "brand-new-auth-secret-00",
	}, nil); st != 200 {
		t.Fatal("complete failed")
	}
	var afterEmail string
	var afterIdentity []byte
	var afterEpoch int64
	var afterBackup []byte
	var revoked int
	_ = s.db.QueryRow(context.Background(), `SELECT email, account_identity_key, identity_epoch, key_backup FROM users WHERE id=$1`, a.userID).
		Scan(&afterEmail, &afterIdentity, &afterEpoch, &afterBackup)
	_ = s.db.QueryRow(context.Background(), `SELECT count(*) FROM sessions WHERE device_id=$1 AND revoked_at IS NULL`, a.deviceID).Scan(&revoked)
	if afterEmail != email || string(afterIdentity) != string(identity) || afterEpoch != epoch || string(afterBackup) != string(backup) {
		t.Fatal("password reset mutated identity, email, epoch or key_backup")
	}
	if revoked != 0 {
		t.Fatal("sessions were not revoked after password reset")
	}
	if st, _ := c.do("POST", "/v1/accounts/password-reset/complete", map[string]string{
		"token": token, "new_password": "another-password-xx",
	}, nil); st != 401 {
		t.Fatal("reset token was reusable")
	}
}

func TestPasswordResetByVerifiedEmailAndSalt(t *testing.T) {
	s, ts, mailbox := emailTestServer(t)
	a := register(t, s, ts, "reset_mail")
	var email string
	_ = s.db.QueryRow(context.Background(), `SELECT email FROM users WHERE id=$1`, a.userID).Scan(&email)
	c := &client{t: t, base: ts.URL}
	var salt map[string]any
	if st, _ := c.do("POST", "/v1/accounts/password-salt", map[string]string{"login": email}, &salt); st != 200 {
		t.Fatal("salt by email failed")
	}
	if salt["username"] != "reset_mail" || salt["salt"] == "" {
		t.Fatalf("salt response missing username: %v", salt)
	}
	if st, _ := c.do("POST", "/v1/accounts/password-reset/request", map[string]string{"login": strings.ToUpper(email)}, nil); st != 200 {
		t.Fatal("reset request by email failed")
	}
	if mailbox.latest() == "" {
		t.Fatal("no reset mail delivered")
	}
	// Unknown login still returns the generic ack — no account enumeration.
	if st, body := c.do("POST", "/v1/accounts/password-reset/request", map[string]string{"login": "nobody@example.test"}, nil); st != 200 || body["status"] != "ok" {
		t.Fatal("unknown login must still acknowledge")
	}
}

func TestPasswordResetRequiresVerifiedEmail(t *testing.T) {
	s, ts, mailbox := emailTestServer(t)
	a := register(t, s, ts, "reset_unbound")
	_, _ = s.db.Exec(context.Background(), `UPDATE users SET email=NULL, email_verified_at=NULL WHERE id=$1`, a.userID)
	before := len(mailbox.links)
	c := &client{t: t, base: ts.URL}
	if st, _ := c.do("POST", "/v1/accounts/password-reset/request", map[string]string{"login": "reset_unbound"}, nil); st != 200 {
		t.Fatal("unbound request should still ack")
	}
	if len(mailbox.links) != before {
		t.Fatal("reset mailed an account without verified email")
	}
}
