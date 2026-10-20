package app

import (
	"context"
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestICECredentials(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "ice_alice")

	var none struct {
		Servers []iceServer `json:"ice_servers"`
	}
	if st, m := a.do("GET", "/v1/calls/ice", nil, &none); st != 200 || len(none.Servers) != 0 {
		t.Fatalf("without TURN: %d %v %+v", st, m, none)
	}

	s.cfg.TurnSecret = "test-secret"
	s.cfg.TurnURIs = []string{"turn:example.test:3478?transport=udp", "turn:example.test:3478?transport=tcp"}
	var out struct {
		Servers []iceServer `json:"ice_servers"`
		TTL     int         `json:"ttl"`
	}
	if st, m := a.do("GET", "/v1/calls/ice", nil, &out); st != 200 {
		t.Fatalf("ice: %d %v", st, m)
	}
	if len(out.Servers) != 1 || len(out.Servers[0].URLs) != 2 || out.TTL <= 0 {
		t.Fatalf("unexpected: %+v", out)
	}
	srv := out.Servers[0]
	exp, dev, ok := strings.Cut(srv.Username, ":")
	if !ok || dev != a.deviceID {
		t.Fatalf("username %q does not name the device", srv.Username)
	}
	n, _ := strconv.ParseInt(exp, 10, 64)
	if until := time.Until(time.Unix(n, 0)); until < iceTTL-time.Minute || until > iceTTL+time.Minute {
		t.Fatalf("expiry %v", until)
	}
	mac := hmac.New(sha1.New, []byte("test-secret"))
	mac.Write([]byte(srv.Username))
	if srv.Credential != base64.StdEncoding.EncodeToString(mac.Sum(nil)) {
		t.Fatal("credential is not the coturn REST HMAC")
	}

	anon := &client{t: t, base: ts.URL}
	if st, _ := anon.do("GET", "/v1/calls/ice", nil, nil); st != 401 {
		t.Fatalf("anonymous: %d", st)
	}
}

func dialEvents(t *testing.T, c *client, query string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	url := "ws" + strings.TrimPrefix(c.base, "http") + "/v1/events" + query
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + c.token}}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	return conn
}

func readHint(t *testing.T, conn *websocket.Conn) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, msg, err := conn.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	return string(msg)
}

// A background socket gets the same "sync now" hints but never shows its user online.
func TestBackgroundSocketHintsWithoutPresence(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "bg_alice")
	b := register(t, s, ts, "bg_bob")
	conv := direct(t, a, b)

	bg := dialEvents(t, b, "?mode=background")
	if h := readHint(t, bg); !strings.Contains(h, "sync.required") {
		t.Fatalf("first hint: %s", h)
	}
	if s.hub.online(b.userID) {
		t.Fatal("background socket made the user online")
	}
	if st, m, _ := a.send(conv, newUUID(), 3); st != 200 {
		t.Fatalf("send: %d %v", st, m)
	}
	if h := readHint(t, bg); !strings.Contains(h, "sync.required") {
		t.Fatalf("hint after send: %s", h)
	}

	fg := dialEvents(t, b, "")
	readHint(t, fg)
	if !s.hub.online(b.userID) {
		t.Fatal("foreground socket did not make the user online")
	}
	fg.Close(websocket.StatusNormalClosure, "")
	deadline := time.Now().Add(5 * time.Second)
	for s.hub.online(b.userID) {
		if time.Now().After(deadline) {
			t.Fatal("user stayed online after the foreground socket closed")
		}
		time.Sleep(20 * time.Millisecond)
	}
}
