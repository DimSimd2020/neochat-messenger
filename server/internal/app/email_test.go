package app

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
)

// Unrelated protocol tests get a completed mail fixture, not a production bypass.
func fixtureEmailTicket(t *testing.T, s *Server) string {
	t.Helper()
	ticket := randomToken()
	_, err := s.db.Exec(context.Background(), `INSERT INTO email_links(id,email,ticket_hash,link_hash,expires_at,sent_at,confirmed_at) VALUES($1,$2,$3,$4,now()+interval '30 minutes',now(),now())`, newUUID(), newUUID()+"@example.test", emailHash(ticket), emailHash(randomToken()))
	if err != nil {
		t.Fatal("cannot create email fixture")
	}
	return ticket
}

type testMailbox struct {
	mu          sync.Mutex
	links       []string
	fail        bool
	reset       bool
	actionEmail string
}

func (m *testMailbox) SendPasswordReset(ctx context.Context, email, link string) error {
	m.reset = true
	return m.SendVerification(ctx, email, link)
}

func (m *testMailbox) CompleteAction(_ context.Context, mode, code string) (string, error) {
	if code != mode+"-valid" {
		return "", errors.New("invalid test action")
	}
	return m.actionEmail, nil
}

func (m *testMailbox) SendVerification(_ context.Context, _ string, link string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.fail {
		return errors.New("fictional delivery failure")
	}
	m.links = append(m.links, link)
	return nil
}
func (m *testMailbox) latest() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.links[len(m.links)-1]
}

func emailTestServer(t *testing.T) (*Server, *httptest.Server, *testMailbox) {
	s, ts := testServer(t)
	m := &testMailbox{}
	s.cfg.Email = m
	s.cfg.EmailBaseURL = ts.URL
	return s, ts, m
}

func confirmTestEmail(t *testing.T, link string, post bool) int {
	t.Helper()
	var response *http.Response
	var err error
	if post {
		u, _ := url.Parse(link)
		response, err = http.PostForm(u.Scheme+"://"+u.Host+u.Path, url.Values{"t": {u.Query().Get("t")}})
	} else {
		response, err = http.Get(link)
	}
	if err != nil {
		t.Fatal("email confirmation request failed")
	}
	defer response.Body.Close()
	if response.Header.Get("Referrer-Policy") != "no-referrer" {
		t.Fatal("email link could leak in referrer")
	}
	return response.StatusCode
}

func TestEmailRegistrationProof(t *testing.T) {
	s, ts, mailbox := emailTestServer(t)
	c := &client{t: t, base: ts.URL}
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	req := registerReq{Username: "mail_alice", DisplayName: "Тест", Password: "long enough password", AccountIdentityKey: rnd(33), RecoveryHash: rnd(32), KeyBackup: rnd(80), Device: fakeDevice(newUUID(), pub), Roster: rosterIn{Revision: "1", Blob: []byte("roster"), Signature: rnd(64)}}
	if st, body := c.do("POST", "/v1/accounts", req, nil); st != 400 || body["code"] != "email_required" {
		t.Fatal("registration accepted without verified email")
	}
	var start struct {
		Ticket string `json:"ticket"`
	}
	if st, _ := c.do("POST", "/v1/accounts/email", map[string]string{"email": "Alice@example.test"}, &start); st != 200 {
		t.Fatal("cannot start email verification")
	}
	req.EmailTicket = start.Ticket
	if st, _ := c.do("POST", "/v1/accounts", req, nil); st != 400 {
		t.Fatal("unconfirmed email allowed registration")
	}
	link := mailbox.latest()
	if strings.Contains(link, start.Ticket) {
		t.Fatal("mail and app use the same secret")
	}
	if st := confirmTestEmail(t, link, false); st != 200 {
		t.Fatal("mail landing page failed")
	}
	var state struct {
		Verified bool `json:"verified"`
	}
	if st, _ := c.do("POST", "/v1/accounts/email/status", map[string]string{"ticket": start.Ticket}, &state); st != 200 || state.Verified {
		t.Fatal("GET from a link scanner confirmed the email")
	}
	if st := confirmTestEmail(t, link, true); st != 200 {
		t.Fatal("confirmation failed")
	}
	// A failed registration must leave the proof usable for a corrected nickname.
	register(t, s, ts, "mail_taken")
	req.Username = "mail_taken"
	if st, _ := c.do("POST", "/v1/accounts", req, nil); st != 409 {
		t.Fatal("duplicate nickname did not reject registration")
	}
	req.Username = "mail_alice"
	var out tokensOut
	if st, _ := c.do("POST", "/v1/accounts", req, &out); st != 201 {
		t.Fatal("verified registration failed")
	}
	req.Username = "mail_bob"
	req.Device.DeviceID = newUUID()
	if st, _ := c.do("POST", "/v1/accounts", req, nil); st != 400 {
		t.Fatal("registration ticket was reusable")
	}
	var email string
	if err := s.db.QueryRow(context.Background(), `SELECT email FROM users WHERE id=$1`, out.UserID).Scan(&email); err != nil || email != "alice@example.test" {
		t.Fatal("verified address not saved")
	}
}

func TestEmailLinkExistingAndRevokedDevice(t *testing.T) {
	s, ts, mailbox := emailTestServer(t)
	a := register(t, s, ts, "alice")
	_, _ = s.db.Exec(context.Background(), `UPDATE users SET email=NULL,email_verified_at=NULL WHERE id=$1`, a.userID)
	if st, _ := a.do("POST", "/v1/accounts/current/email", map[string]string{"email": "linked@example.test"}, nil); st != 200 {
		t.Fatal("cannot start binding")
	}
	link := mailbox.latest()
	if st := confirmTestEmail(t, link, true); st != 200 {
		t.Fatal("binding failed")
	}
	var state struct {
		Verified bool   `json:"verified"`
		Email    string `json:"email"`
	}
	if st, _ := a.do("GET", "/v1/accounts/current/email", nil, &state); st != 200 || !state.Verified || state.Email != "linked@example.test" {
		t.Fatal("binding state missing")
	}
	if st := confirmTestEmail(t, link, true); st != 400 {
		t.Fatal("consumed binding link reused")
	}
	b := register(t, s, ts, "bob")
	_, _ = s.db.Exec(context.Background(), `UPDATE users SET email=NULL,email_verified_at=NULL WHERE id=$1`, b.userID)
	if st, _ := b.do("POST", "/v1/accounts/current/email", map[string]string{"email": "revoked@example.test"}, nil); st != 200 {
		t.Fatal("cannot start binding before revocation")
	}
	_, _ = s.db.Exec(context.Background(), `UPDATE devices SET revoked_at=now() WHERE id=$1`, b.deviceID)
	if st := confirmTestEmail(t, mailbox.latest(), true); st != 409 {
		t.Fatal("revoked device could bind an email")
	}
}

func TestEmailCooldownAndFailedDelivery(t *testing.T) {
	s, ts, mailbox := emailTestServer(t)
	c := &client{t: t, base: ts.URL}
	if st, _ := c.do("POST", "/v1/accounts/email", map[string]string{"email": "Name <a@example.test>"}, nil); st != 400 {
		t.Fatal("display-name email accepted")
	}
	if st, _ := c.do("POST", "/v1/accounts/email", map[string]string{"email": "a@example.test"}, nil); st != 200 {
		t.Fatal("first email failed")
	}
	if st, _ := c.do("POST", "/v1/accounts/email", map[string]string{"email": "A@EXAMPLE.TEST"}, nil); st != 429 {
		t.Fatal("cooldown bypassed with case")
	}
	mailbox.fail = true
	if st, _ := c.do("POST", "/v1/accounts/email", map[string]string{"email": "failed@example.test"}, nil); st != 503 {
		t.Fatal("delivery failure reported as sent")
	}
	var count int
	_ = s.db.QueryRow(context.Background(), `SELECT count(*) FROM email_links WHERE email='failed@example.test'`).Scan(&count)
	if count != 0 {
		t.Fatal("failed email left usable proof")
	}
}

func TestEmailTicketConcurrentRegistration(t *testing.T) {
	s, ts, _ := emailTestServer(t)
	ticket := fixtureEmailTicket(t, s)
	results := make(chan int, 2)
	for _, name := range []string{"parallel_one", "parallel_two"} {
		go func(name string) {
			pub, _, _ := ed25519.GenerateKey(rand.Reader)
			req := registerReq{EmailTicket: ticket, Username: name, DisplayName: "Тест", Password: "long enough password", AccountIdentityKey: rnd(33), RecoveryHash: rnd(32), KeyBackup: rnd(80), Device: fakeDevice(newUUID(), pub), Roster: rosterIn{Revision: "1", Blob: []byte("roster"), Signature: rnd(64)}}
			c := &client{t: t, base: ts.URL}
			st, _ := c.do("POST", "/v1/accounts", req, nil)
			results <- st
		}(name)
	}
	first, second := <-results, <-results
	if !((first == 201 && second == 400) || (first == 400 && second == 201)) {
		t.Fatal("one email ticket created more than one account")
	}
}

func TestExistingAccountEmailIsOptionalAndPrivate(t *testing.T) {
	s, ts, _ := emailTestServer(t)
	a := register(t, s, ts, "existing_alice")
	b := register(t, s, ts, "existing_bob")
	_, _ = s.db.Exec(context.Background(), `UPDATE users SET email=NULL,email_verified_at=NULL WHERE id=$1`, a.userID)
	if st, _ := a.do("GET", "/v1/sync", nil, nil); st != 200 {
		t.Fatal("existing account was blocked without email")
	}
	var profile map[string]any
	a.resolve(b)
	if st, _ := a.do("GET", "/v1/users/"+b.userID, nil, &profile); st != 200 {
		t.Fatal("profile lookup failed")
	}
	if _, found := profile["email"]; found {
		t.Fatal("private email was exposed in public profile")
	}
}
