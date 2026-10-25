package app

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"neochat/server/migrations"
)

// Tests need a disposable database: NEOCHAT_TEST_DATABASE_URL=postgres://...
// The schema is dropped and recreated on every run.
func testServer(t *testing.T) (*Server, *httptest.Server) {
	t.Helper()
	url := os.Getenv("NEOCHAT_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("NEOCHAT_TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	db, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if _, err := db.Exec(ctx, `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`); err != nil {
		t.Fatal(err)
	}
	if err := Migrate(ctx, db, migrations.FS); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	os.MkdirAll(dir+"/objects", 0o700)
	os.MkdirAll(dir+"/tmp", 0o700)
	s := New(db, Config{DataDir: dir, ArgonMemoryKiB: 1024, ArgonTime: 1}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	ts := httptest.NewServer(s)
	t.Cleanup(ts.Close)
	return s, ts
}

type client struct {
	t        *testing.T
	base     string
	token    string
	refresh  string
	userID   string
	deviceID string
	username string
	authPriv ed25519.PrivateKey
}

func rnd(n int) []byte {
	b := make([]byte, n)
	rand.Read(b)
	return b
}

func fakeDevice(id string, pub ed25519.PublicKey) deviceIn {
	d := deviceIn{
		DeviceID: id, Name: "test", Platform: "linux", RegistrationID: 42,
		IdentityKey: rnd(identityLen), AuthKey: pub,
		SignedPrekey: prekeyIn{ID: 1, PublicKey: rnd(identityLen), Signature: rnd(64)},
		KyberPrekey:  prekeyIn{ID: 1, PublicKey: rnd(kyberKeyLen), Signature: rnd(64)},
	}
	for i := 0; i < 3; i++ {
		d.OneTimePrekeys = append(d.OneTimePrekeys, prekeyIn{ID: uint32(10 + i), PublicKey: rnd(identityLen)})
		d.KyberOneTimePrekeys = append(d.KyberOneTimePrekeys, prekeyIn{ID: uint32(10 + i), PublicKey: rnd(kyberKeyLen), Signature: rnd(64)})
	}
	return d
}

func (c *client) do(method, path string, body any, out any) (int, map[string]any) {
	c.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.base+path, rd)
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var m map[string]any
	json.Unmarshal(raw, &m)
	if out != nil && resp.StatusCode < 300 {
		if err := json.Unmarshal(raw, out); err != nil {
			c.t.Fatalf("decode %s: %v (%s)", path, err, raw)
		}
	}
	return resp.StatusCode, m
}

func register(t *testing.T, s *Server, ts *httptest.Server, username string) *client {
	t.Helper()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	c := &client{t: t, base: ts.URL, username: username, authPriv: priv}
	secret := sha256.Sum256([]byte("recovery-" + username))
	req := registerReq{
		EmailTicket: fixtureEmailTicket(t, s),
		Username:    username, Password: "correct horse battery",
		AccountIdentityKey: rnd(identityLen), RecoveryHash: secret[:], KeyBackup: rnd(80), DisplayName: "Тест",
		Device: fakeDevice(newUUID(), pub),
		Roster: rosterIn{Revision: "1", Blob: []byte("roster"), Signature: rnd(64)},
	}
	var out tokensOut
	if st, m := c.do("POST", "/v1/accounts", req, &out); st != 201 {
		t.Fatalf("register %s: %d %v", username, st, m)
	}
	c.token, c.refresh, c.userID, c.deviceID = out.AccessToken, out.RefreshToken, out.UserID, out.DeviceID
	return c
}

func (c *client) resolve(other *client) {
	c.t.Helper()
	if st, m := c.do("POST", "/v1/contacts/resolve", map[string]string{"username": other.username}, nil); st != 200 {
		c.t.Fatalf("resolve: %d %v", st, m)
	}
}

func (c *client) send(conv *conversationOut, cmid string, ct byte) (int, map[string]any, sendAck) {
	c.t.Helper()
	recips := []map[string]any{}
	for _, r := range conv.Recipients {
		recips = append(recips, map[string]any{"target_device_id": r.DeviceID, "ciphertext": []byte{ct, 1, 2, 3}})
	}
	var ack sendAck
	st, m := c.do("POST", "/v1/conversations/"+conv.ID+"/messages", map[string]any{
		"version": 1, "client_message_id": cmid, "conversation_revision": conv.Revision,
		"recipient_set_revision": conv.RecipientSetRevision, "recipients": recips, "attachment_ids": []string{},
	}, &ack)
	return st, m, ack
}

type syncResp struct {
	Events     []eventOut `json:"events"`
	NextCursor string     `json:"next_cursor"`
	HasMore    bool       `json:"has_more"`
}

func (c *client) syncAll(cursor string) ([]eventOut, string) {
	c.t.Helper()
	var all []eventOut
	for {
		var r syncResp
		if st, m := c.do("GET", "/v1/sync?cursor="+cursor, nil, &r); st != 200 {
			c.t.Fatalf("sync: %d %v", st, m)
		}
		all = append(all, r.Events...)
		cursor = r.NextCursor
		if !r.HasMore {
			return all, cursor
		}
	}
}

func direct(t *testing.T, a, b *client) *conversationOut {
	a.resolve(b)
	var conv conversationOut
	if st, m := a.do("POST", "/v1/conversations/direct", map[string]string{"user_id": b.userID}, &conv); st != 200 {
		t.Fatalf("direct: %d %v", st, m)
	}
	return &conv
}

func TestRegisterAndLogin(t *testing.T) {
	s, ts := testServer(t)
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	c := &client{t: t, base: ts.URL}
	mk := func(name string) registerReq {
		h := sha256.Sum256([]byte(name))
		return registerReq{EmailTicket: fixtureEmailTicket(t, s), Username: name, Password: "long enough pw", AccountIdentityKey: rnd(33), RecoveryHash: h[:], KeyBackup: rnd(80), DisplayName: "Тест",
			Device: fakeDevice(newUUID(), pub), Roster: rosterIn{Revision: "1", Blob: []byte("r"), Signature: rnd(64)}}
	}
	if st, _ := c.do("POST", "/v1/accounts", mk("alice"), nil); st != 201 {
		t.Fatalf("register: %d", st)
	}
	if st, m := c.do("POST", "/v1/accounts", mk("alice"), nil); st != 409 || m["code"] != "username_taken" {
		t.Fatalf("taken username: %d %v", st, m)
	}

	bob := register(t, s, ts, "bob")
	ts2 := strconv.FormatInt(time.Now().Unix(), 10)
	login := func(pw string, sig []byte) int {
		st, _ := c.do("POST", "/v1/sessions", loginReq{Username: "bob", Password: pw, DeviceID: bob.deviceID, Timestamp: ts2, Signature: sig}, nil)
		return st
	}
	good := ed25519.Sign(bob.authPriv, LoginChallenge("bob", bob.deviceID, ts2))
	if st := login("correct horse battery", good); st != 200 {
		t.Fatalf("login: %d", st)
	}
	// The password alone does not authenticate a device.
	if st := login("correct horse battery", rnd(64)); st != 401 {
		t.Fatalf("login without device proof: %d", st)
	}
	if st := login("wrong password!", good); st != 401 {
		t.Fatalf("login with wrong password: %d", st)
	}
}

func TestRefreshRotationGraceAndReuse(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	old := a.refresh
	var t1, t2 tokensOut
	if st, _ := a.do("POST", "/v1/sessions/refresh", map[string]string{"refresh_token": old}, &t1); st != 200 {
		t.Fatal("refresh")
	}
	// A lost response: the same refresh token is retried within the grace window.
	if st, _ := a.do("POST", "/v1/sessions/refresh", map[string]string{"refresh_token": old}, &t2); st != 200 {
		t.Fatal("refresh retry within grace")
	}
	if st, _ := a.do("POST", "/v1/sessions/refresh", map[string]string{"refresh_token": t1.RefreshToken}, nil); st != 401 {
		t.Fatalf("superseded successor must be revoked: %d", st)
	}
	s.db.Exec(context.Background(), `UPDATE sessions SET replaced_at = now() - interval '5 minutes' WHERE replaced_at IS NOT NULL`)
	if st, _ := a.do("POST", "/v1/sessions/refresh", map[string]string{"refresh_token": old}, nil); st != 401 {
		t.Fatalf("late reuse must fail: %d", st)
	}
	if st, _ := a.do("POST", "/v1/sessions/refresh", map[string]string{"refresh_token": t2.RefreshToken}, nil); st != 401 {
		t.Fatalf("late reuse must revoke the family: %d", st)
	}
}

func TestSendIdempotencyAndSync(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	b := register(t, s, ts, "bob")
	conv := direct(t, a, b)
	cmid := newUUID()
	st, m, ack1 := a.send(conv, cmid, 7)
	if st != 200 {
		t.Fatalf("send: %d %v", st, m)
	}
	_, _, ack2 := a.send(conv, cmid, 7)
	if ack1.MessageID != ack2.MessageID || ack1.ConversationSeq != ack2.ConversationSeq {
		t.Fatalf("retry must return the same ACK: %+v %+v", ack1, ack2)
	}
	if st, m, _ := a.send(conv, cmid, 8); st != 409 || m["code"] != "idempotency_conflict" {
		t.Fatalf("different body: %d %v", st, m)
	}
	events, cursor := b.syncAll("")
	if len(events) != 1 || events[0].MessageID != ack1.MessageID || events[0].SenderUserID != a.userID || events[0].Ciphertext[0] != 7 {
		t.Fatalf("bob events: %+v", events)
	}
	if more, _ := b.syncAll(cursor); len(more) != 0 {
		t.Fatalf("cursor must not repeat events: %+v", more)
	}
	// Strangers cannot see the conversation or claim keys.
	eve := register(t, s, ts, "eve")
	if st, _ := eve.do("GET", "/v1/conversations/"+conv.ID, nil, nil); st != 404 {
		t.Fatalf("IDOR conversation: %d", st)
	}
	if st, _ := eve.do("POST", "/v1/users/"+a.userID+"/bundles", nil, nil); st != 404 {
		t.Fatalf("IDOR bundles: %d", st)
	}
	if st, _, _ := eve.send(conv, newUUID(), 1); st != 404 {
		t.Fatalf("IDOR send: %d", st)
	}
}

func TestConcurrentSendsLeaveNoCursorGaps(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	b := register(t, s, ts, "bob")
	conv := direct(t, a, b)
	var bconv conversationOut
	b.do("GET", "/v1/conversations/"+conv.ID, nil, &bconv)

	const n = 20
	var wg sync.WaitGroup
	cursor := ""
	seen := map[string]bool{}
	var mu sync.Mutex
	stop := make(chan struct{})
	// A reader syncs concurrently with writers; every event must arrive exactly once.
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			ev, c := b.syncAll(cursor)
			mu.Lock()
			for _, e := range ev {
				if seen[e.EventID] {
					t.Errorf("duplicate event %s", e.EventID)
				}
				seen[e.EventID] = true
			}
			mu.Unlock()
			cursor = c
			select {
			case <-stop:
				return
			default:
			}
		}
	}()
	for i := 0; i < n; i++ {
		wg.Add(2)
		go func() { defer wg.Done(); a.send(conv, newUUID(), 1) }()
		go func() { defer wg.Done(); b.send(&bconv, newUUID(), 2) }()
	}
	wg.Wait()
	close(stop)
	<-done
	ev, _ := b.syncAll(cursor)
	for _, e := range ev {
		seen[e.EventID] = true
	}
	if len(seen) != n {
		t.Fatalf("bob got %d events, want %d", len(seen), n)
	}
}

func TestGroupMembershipChangesRecipients(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	b := register(t, s, ts, "bob")
	c := register(t, s, ts, "carol")
	a.resolve(b)
	a.resolve(c)
	gid := newUUID()
	var g conversationOut
	if st, m := a.do("POST", "/v1/conversations/groups", map[string]any{
		"group_id": gid, "members": []string{b.userID, c.userID},
		"state": signedState{Revision: "1", Blob: []byte("g1"), Signature: rnd(64)},
	}, &g); st != 201 {
		t.Fatalf("create group: %d %v", st, m)
	}
	if len(g.Recipients) != 2 {
		t.Fatalf("recipients: %+v", g.Recipients)
	}
	// Remove carol while alice still holds the old recipient set.
	var g2 conversationOut
	if st, m := a.do("POST", "/v1/conversations/"+gid+"/membership", map[string]any{
		"prev_revision": "1", "members": []string{b.userID},
		"state": signedState{Revision: "2", Blob: []byte("g2"), Signature: rnd(64)},
	}, &g2); st != 200 {
		t.Fatalf("membership: %d %v", st, m)
	}
	if st, m, _ := a.send(&g, newUUID(), 1); st != 409 || m["code"] != "conversation_revision_changed" {
		t.Fatalf("stale revision send: %d %v", st, m)
	}
	stale := g2
	stale.Recipients = g.Recipients
	stale.RecipientSetRevision = g.RecipientSetRevision
	if st, m, _ := a.send(&stale, newUUID(), 1); st != 409 || m["code"] != "recipient_set_changed" {
		t.Fatalf("stale recipients send: %d %v", st, m)
	}
	if st, m, _ := a.send(&g2, newUUID(), 1); st != 200 {
		t.Fatalf("send to new set: %d %v", st, m)
	}
	// Carol keeps nothing new: no access to the group and no message event.
	if st, _ := c.do("GET", "/v1/conversations/"+gid, nil, nil); st != 404 {
		t.Fatalf("removed member still sees group: %d", st)
	}
	ev, _ := c.syncAll("")
	for _, e := range ev {
		if e.Kind == "message" {
			t.Fatalf("removed member received a message")
		}
	}
	// Bob leaves; alice's group then has no recipients.
	var bg conversationOut
	b.do("GET", "/v1/conversations/"+gid, nil, &bg)
	if st, m := b.do("POST", "/v1/conversations/"+gid+"/leave", map[string]any{"prev_revision": bg.Revision, "blob": []byte("leave"), "signature": rnd(64)}, nil); st != 204 {
		t.Fatalf("leave: %d %v", st, m)
	}
	var g3 conversationOut
	a.do("GET", "/v1/conversations/"+gid, nil, &g3)
	if len(g3.Recipients) != 0 || len(g3.Leaves) != 1 {
		t.Fatalf("after leave: %+v", g3)
	}
}

func TestRevokedDeviceGetsNothing(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	b := register(t, s, ts, "bob")
	conv := direct(t, a, b)
	if st, _ := b.do("DELETE", "/v1/devices/"+b.deviceID, nil, nil); st != 204 {
		t.Fatal("self revoke")
	}
	if st, _ := b.do("GET", "/v1/sync", nil, nil); st != 401 {
		t.Fatalf("revoked device can sync: %d", st)
	}
	var fresh conversationOut
	a.do("GET", "/v1/conversations/"+conv.ID, nil, &fresh)
	if len(fresh.Recipients) != 0 {
		t.Fatalf("revoked device still a recipient: %+v", fresh.Recipients)
	}
	if st, m, _ := a.send(&fresh, newUUID(), 1); st != 409 || m["code"] != "no_recipients" {
		t.Fatalf("send with no recipients: %d %v", st, m)
	}
}

func TestBundlesHandOutOneTimeKeysOnce(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	b := register(t, s, ts, "bob")
	a.resolve(b)
	ids := map[uint32]bool{}
	for i := 0; i < 4; i++ {
		var out struct {
			Bundles []bundleOut `json:"bundles"`
		}
		if st, m := a.do("POST", "/v1/users/"+b.userID+"/bundles", nil, &out); st != 200 || len(out.Bundles) != 1 {
			t.Fatalf("claim: %d %v", st, m)
		}
		if p := out.Bundles[0].OneTimePrekey; p != nil {
			if ids[p.ID] {
				t.Fatalf("one-time prekey %d handed out twice", p.ID)
			}
			ids[p.ID] = true
		} else if i < 3 {
			t.Fatalf("missing one-time prekey at claim %d", i)
		}
	}
}

func TestAttachmentUploadAndAccess(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	b := register(t, s, ts, "bob")
	eve := register(t, s, ts, "eve")
	conv := direct(t, a, b)
	content := rnd(100_000)
	sum := sha256.Sum256(content)
	var res struct {
		AttachmentID string `json:"attachment_id"`
	}
	if st, m := a.do("POST", "/v1/attachments", map[string]any{"size": len(content), "sha256": sum[:]}, &res); st != 201 {
		t.Fatalf("reserve: %d %v", st, m)
	}
	put := func(body []byte) int {
		req, _ := http.NewRequest("PUT", ts.URL+"/v1/attachments/"+res.AttachmentID+"/content", bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+a.token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	bad := append([]byte{}, content...)
	bad[0] ^= 1
	if st := put(bad); st != 400 {
		t.Fatalf("corrupted upload accepted: %d", st)
	}
	if st := put(content); st != 204 {
		t.Fatalf("upload: %d", st)
	}
	get := func(c *client) int {
		req, _ := http.NewRequest("GET", ts.URL+"/v1/attachments/"+res.AttachmentID+"/content", nil)
		req.Header.Set("Authorization", "Bearer "+c.token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode == 200 && !bytes.Equal(body, content) {
			t.Fatal("content mismatch")
		}
		return resp.StatusCode
	}
	if st := get(b); st != 404 {
		t.Fatalf("bob downloads before message: %d", st)
	}
	recips := []map[string]any{{"target_device_id": conv.Recipients[0].DeviceID, "ciphertext": []byte{1, 2}}}
	if st, m := a.do("POST", "/v1/conversations/"+conv.ID+"/messages", map[string]any{
		"version": 1, "client_message_id": newUUID(), "conversation_revision": conv.Revision,
		"recipient_set_revision": conv.RecipientSetRevision, "recipients": recips, "attachment_ids": []string{res.AttachmentID},
	}, nil); st != 200 {
		t.Fatalf("send with attachment: %d %v", st, m)
	}
	if st := get(b); st != 200 {
		t.Fatalf("bob download: %d", st)
	}
	if st := get(eve); st != 404 {
		t.Fatalf("eve download: %d", st)
	}
}

func TestDeviceAndRecipientCaps(t *testing.T) {
	if maxDevices != 10 {
		t.Fatalf("maxDevices=%d", maxDevices)
	}
	want := maxChannelMembers*maxDevices - 1
	if maxRecipients != want {
		t.Fatalf("maxRecipients=%d want %d", maxRecipients, want)
	}
	if want != 1999 {
		t.Fatalf("channel fan-out cap %d, want 1999", want)
	}
	if maxRosterBlob < 1024 {
		t.Fatalf("roster blob cap too small for 10 devices: %d", maxRosterBlob)
	}
}

func addDeviceViaPassword(t *testing.T, ts *httptest.Server, username string, rev int64) {
	t.Helper()
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	c := &client{t: t, base: ts.URL}
	st, m := c.do("POST", "/v1/devices/new/start", map[string]string{"username": username, "password": "correct horse battery"}, nil)
	if st != 200 {
		t.Fatalf("new device start: %d %v", st, m)
	}
	token, _ := m["start_token"].(string)
	st, m = c.do("POST", "/v1/devices/new/finish", map[string]any{
		"start_token": token,
		"device":      fakeDevice(newUUID(), pub),
		"roster":      rosterIn{Revision: strconv.FormatInt(rev, 10), Blob: []byte("roster"), Signature: rnd(64)},
	}, nil)
	if st != 200 {
		t.Fatalf("new device finish: %d %v", st, m)
	}
}

func TestDeviceLimit(t *testing.T) {
	s, ts := testServer(t)
	register(t, s, ts, "alice")
	for i := 2; i <= maxDevices; i++ {
		addDeviceViaPassword(t, ts, "alice", int64(i))
	}
	c := &client{t: t, base: ts.URL}
	st, m := c.do("POST", "/v1/devices/new/start", map[string]string{"username": "alice", "password": "correct horse battery"}, nil)
	if st != 409 || m["code"] != "device_limit" {
		t.Fatalf("device %d: %d %v", maxDevices+1, st, m)
	}
	if msg, _ := m["message"].(string); !strings.Contains(msg, "10") {
		t.Fatalf("limit message should mention 10: %v", m)
	}
}

func TestSendRejectsTooManyRecipients(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	b := register(t, s, ts, "bob")
	conv := direct(t, a, b)
	recips := make([]map[string]any, maxRecipients+1)
	for i := range recips {
		recips[i] = map[string]any{"target_device_id": newUUID(), "ciphertext": []byte{1, 2}}
	}
	st, m := a.do("POST", "/v1/conversations/"+conv.ID+"/messages", map[string]any{
		"version": 1, "client_message_id": newUUID(), "conversation_revision": conv.Revision,
		"recipient_set_revision": conv.RecipientSetRevision, "recipients": recips, "attachment_ids": []string{},
	}, nil)
	if st != 400 {
		t.Fatalf("oversize recipient list: %d %v", st, m)
	}
}

func TestAttachmentSizeLimit(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "alice")
	sum := sha256.Sum256([]byte("x"))
	// 50 MiB of file and the tags of its chunks fit; one byte more does not.
	var res struct {
		AttachmentID string `json:"attachment_id"`
	}
	if st, m := a.do("POST", "/v1/attachments", map[string]any{"size": 51 << 20, "sha256": sum[:]}, &res); st != 201 {
		t.Fatalf("reserve at the limit: %d %v", st, m)
	}
	if st, _ := a.do("POST", "/v1/attachments", map[string]any{"size": 51<<20 + 1, "sha256": sum[:]}, nil); st != 413 {
		t.Fatalf("reserve over the limit: %d", st)
	}
}
