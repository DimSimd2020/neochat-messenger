package app

import "testing"

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
