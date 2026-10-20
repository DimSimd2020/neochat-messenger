package app

import (
	"bytes"
	"testing"
)

func TestAccountStateRevisions(t *testing.T) {
	s, ts := testServer(t)
	alice := register(t, s, ts, "state_alice")
	bob := register(t, s, ts, "state_bob")
	if st, _ := alice.do("GET", "/v1/accounts/current/state", nil, nil); st != 404 {
		t.Fatalf("empty state: %d", st)
	}
	put := func(c *client, rev string, blob []byte) (int, map[string]any) {
		return c.do("PUT", "/v1/accounts/current/state", map[string]any{"revision": rev, "blob": blob}, nil)
	}
	if st, m := put(alice, "0", []byte("first")); st != 200 || m["revision"] != "1" {
		t.Fatalf("create: %d %v", st, m)
	}
	// A second creation or a stale revision is a conflict the client must merge.
	if st, m := put(alice, "0", []byte("other")); st != 409 || m["code"] != "state_conflict" {
		t.Fatalf("second create: %d %v", st, m)
	}
	if st, m := put(alice, "1", []byte("second")); st != 200 || m["revision"] != "2" {
		t.Fatalf("update: %d %v", st, m)
	}
	if st, m := put(alice, "1", []byte("stale")); st != 409 || m["code"] != "state_conflict" {
		t.Fatalf("stale update: %d %v", st, m)
	}
	var got struct {
		Revision string `json:"revision"`
		Blob     []byte `json:"blob"`
	}
	if st, _ := alice.do("GET", "/v1/accounts/current/state", nil, &got); st != 200 || got.Revision != "2" || !bytes.Equal(got.Blob, []byte("second")) {
		t.Fatalf("read back: %d %+v", st, got)
	}
	// Every account has its own document; empty and oversized blobs are refused.
	if st, _ := bob.do("GET", "/v1/accounts/current/state", nil, nil); st != 404 {
		t.Fatalf("other account sees state: %d", st)
	}
	if st, _ := put(bob, "0", nil); st != 400 {
		t.Fatalf("empty blob: %d", st)
	}
	if st, _ := put(bob, "0", make([]byte, maxStateBlob+1)); st != 400 && st != 413 {
		t.Fatalf("oversized blob: %d", st)
	}
	if st, _ := put(bob, "x", []byte("a")); st != 400 {
		t.Fatalf("bad revision: %d", st)
	}
	anon := &client{t: t, base: ts.URL}
	if st, _ := anon.do("GET", "/v1/accounts/current/state", nil, nil); st != 401 {
		t.Fatalf("anonymous read: %d", st)
	}
}
