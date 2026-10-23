package app

import "testing"

func TestProfileStyle(t *testing.T) {
	s, ts := testServer(t)
	alice := register(t, s, ts, "style_alice")
	put := func(body map[string]string) int {
		st, _ := alice.do("PUT", "/v1/accounts/current/profile", body, nil)
		return st
	}
	if st := put(map[string]string{"name_color": "#FF8800", "header": "#112233, #445566"}); st != 200 {
		t.Fatalf("set style: %d", st)
	}
	var p userOut
	alice.do("GET", "/v1/users/"+alice.userID, nil, &p)
	if p.NameColor != "#ff8800" || p.Header != "#112233,#445566" {
		t.Fatalf("style was not kept in canonical form: %q %q", p.NameColor, p.Header)
	}
	for _, bad := range []map[string]string{
		{"name_color": "red"},
		{"name_color": "#12345"},
		{"header": "#111111,#222222,#333333,#444444"},
		{"header": "#111111,blue"},
	} {
		if st := put(bad); st != 400 {
			t.Fatalf("%v was accepted: %d", bad, st)
		}
	}
	// A request without the style keeps it; an empty string clears it.
	if st := put(map[string]string{"bio": "hi"}); st != 200 {
		t.Fatalf("bio only: %d", st)
	}
	alice.do("GET", "/v1/users/"+alice.userID, nil, &p)
	if p.NameColor != "#ff8800" {
		t.Fatal("an unrelated profile change dropped the name colour")
	}
	if st := put(map[string]string{"name_color": "", "header": ""}); st != 200 {
		t.Fatalf("clear: %d", st)
	}
	alice.do("GET", "/v1/users/"+alice.userID, nil, &p)
	if p.NameColor != "" || p.Header != "" {
		t.Fatal("the style was not cleared")
	}
}

func TestProfileUsernameAndSalt(t *testing.T) {
	s, ts := testServer(t)
	alice := register(t, s, ts, "profile_alice")
	bob := register(t, s, ts, "profile_bob")
	for _, name := range []string{"everyone", "HeRe"} {
		status, body := alice.do("POST", "/v1/accounts", map[string]string{"username": name}, nil)
		if status != 400 || body["code"] != "bad_username" {
			t.Fatalf("reserved registration: %d", status)
		}
	}
	if st, _ := alice.do("PUT", "/v1/accounts/current/profile", map[string]string{"username": "@Alice_New"}, nil); st != 200 {
		t.Fatalf("rename: %d", st)
	}
	var salt struct {
		Salt string `json:"salt"`
	}
	if st, _ := alice.do("POST", "/v1/accounts/password-salt", map[string]string{"username": "alice_new"}, &salt); st != 200 || salt.Salt != "profile_alice" {
		t.Fatal("rename changed the password salt")
	}
	if st, _ := bob.do("PUT", "/v1/accounts/current/profile", map[string]string{"username": "ALICE_NEW"}, nil); st != 409 {
		t.Fatalf("duplicate name: %d", st)
	}
	if st, _ := alice.do("PUT", "/v1/accounts/current/profile", map[string]string{"username": "everyone"}, nil); st != 400 {
		t.Fatalf("broadcast name: %d", st)
	}
	var p userOut
	alice.do("GET", "/v1/users/"+alice.userID, nil, &p)
	if p.Username != "Alice_New" {
		t.Fatal("the profile did not retain the new spelling")
	}
}
