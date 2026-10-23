package app

import (
	"context"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestCustomEmailAction(t *testing.T) {
	s, ts, mailbox := emailTestServer(t)
	c := &client{t: t, base: ts.URL}
	var ticket map[string]any
	if status, _ := c.do("POST", "/v1/accounts/email", map[string]string{"email": "action@example.test"}, &ticket); status != 200 {
		t.Fatal("verification request failed")
	}
	mailbox.actionEmail = "action@example.test"
	link := mailbox.latest()
	action := func(mode, code, continuation string) string {
		return ts.URL + "/v1/accounts/email/action?" + url.Values{"mode": {mode}, "oobCode": {code}, "continueUrl": {continuation}}.Encode()
	}
	get := func(address string, want int) string {
		t.Helper()
		response, err := http.Get(address)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, _ := io.ReadAll(response.Body)
		if response.StatusCode != want || response.Header.Get("Cache-Control") != "no-store" {
			t.Fatalf("action page: status=%d", response.StatusCode)
		}
		return string(body)
	}
	page := get(action("verifyEmail", "verifyEmail-valid", link), 200)
	if !strings.Contains(page, `action="/v1/accounts/email/action"`) || !strings.Contains(page, "Подтвердить почту") {
		t.Fatal("missing combined confirmation form")
	}
	var state map[string]any
	c.do("POST", "/v1/accounts/email/status", map[string]any{"ticket": ticket["ticket"]}, &state)
	if state["verified"] != false {
		t.Fatal("GET confirmed email")
	}
	get(action("resetPassword", "resetPassword-valid", link), 400)
	get(action("verifyEmail", "verifyEmail-valid", "https://example.invalid/v1/accounts/email/confirm?t="+strings.Repeat("a", 43)), 400)
	u, _ := url.Parse(link)
	form := url.Values{"mode": {"verifyEmail"}, "oobCode": {"invalid"}, "t": {u.Query().Get("t")}}
	response, err := http.PostForm(ts.URL+"/v1/accounts/email/action", form)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 409 {
		t.Fatal("invalid Firebase code accepted")
	}
	form.Set("oobCode", "verifyEmail-valid")
	response, err = http.PostForm(ts.URL+"/v1/accounts/email/action", form)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 200 {
		t.Fatal("combined confirmation failed")
	}
	c.do("POST", "/v1/accounts/email/status", map[string]any{"ticket": ticket["ticket"]}, &state)
	if state["verified"] != true {
		t.Fatal("combined confirmation did not verify email")
	}

	a := register(t, s, ts, "action_reset")
	s.db.QueryRow(context.Background(), `SELECT email FROM users WHERE id=$1`, a.userID).Scan(&mailbox.actionEmail)
	if status, _ := c.do("POST", "/v1/accounts/password-reset/request", map[string]string{"login": "action_reset"}, nil); status != 200 || !mailbox.reset {
		t.Fatal("reset request failed")
	}
	resetLink := mailbox.latest()
	get(action("resetPassword", "invalid", resetLink), 400)
	page = get(action("resetPassword", "resetPassword-valid", resetLink), 200)
	if !strings.Contains(page, "Скопировать код восстановления") || strings.Contains(page, "Подтвердить почту") {
		t.Fatal("reset used verification page")
	}
	get(action("verifyEmail", "verifyEmail-valid", resetLink), 400)
}
