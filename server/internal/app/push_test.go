package app

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func TestFCMSend(t *testing.T) {
	for _, tc := range []struct {
		name            string
		status          int
		body            string
		invalid, failed bool
	}{
		{"accepted", 200, `{}`, false, false},
		{"expired", 404, `{"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}`, true, false},
		{"wrong-project", 404, `{"error":{"status":"NOT_FOUND"}}`, false, true},
		{"bad-request", 400, `{"error":{"status":"INVALID_ARGUMENT"}}`, false, true},
		{"retry", 503, `private response must not enter logs`, false, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var body struct {
					Message map[string]json.RawMessage `json:"message"`
				}
				if r.Method != "POST" || r.Header.Get("Content-Type") != "application/json" {
					t.Error("invalid FCM request")
				}
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Fatal(err)
				}
				if len(body.Message) != 3 || body.Message["notification"] != nil {
					t.Error("push includes unexpected content")
				}
				var data map[string]string
				_ = json.Unmarshal(body.Message["data"], &data)
				if len(data) != 1 || data["type"] != "sync" {
					t.Error("push must contain only a sync hint")
				}
				var fid string
				_ = json.Unmarshal(body.Message["fid"], &fid)
				if fid != "fictional-installation" {
					t.Error("wrong target")
				}
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer endpoint.Close()
			fcm := &FCMClient{client: endpoint.Client(), endpoint: endpoint.URL}
			invalid, err := fcm.send(context.Background(), "fictional-installation")
			if invalid != tc.invalid || (err != nil) != tc.failed {
				t.Fatalf("unexpected result: invalid=%v, failed=%v", invalid, err != nil)
			}
			if err != nil && (strings.Contains(err.Error(), "fictional") || strings.Contains(err.Error(), "private response")) {
				t.Error("sensitive response leaked into error")
			}
		})
	}
	if fcm, err := NewFCMClient(context.Background(), ""); fcm != nil || err != nil {
		t.Fatal("empty config should disable FCM")
	}
}

func TestPushQueueLifecycle(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "push_alice")
	b := register(t, s, ts, "push_bob")
	ctx := context.Background()
	registerPush := func(c *client, token string) {
		t.Helper()
		if status, _ := c.do("PUT", "/v1/devices/current/push", map[string]string{"provider": "fcm", "token": token}, nil); status != 204 {
			t.Fatalf("register push: %d", status)
		}
	}
	count := func(table string, device string) int {
		t.Helper()
		var n int
		if err := s.db.QueryRow(ctx, "SELECT count(*) FROM "+table+" WHERE device_id=$1", device).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	registerPush(a, "fictional-alice")
	registerPush(b, "fictional-bob")
	if status, _ := (&client{t: t, base: ts.URL}).do("PUT", "/v1/devices/current/push", map[string]string{"provider": "fcm", "token": "fictional-stranger"}, nil); status != 401 {
		t.Fatal("anonymous push registration accepted")
	}
	statusCode, _ := b.do("PUT", "/v1/devices/current/push", map[string]string{"provider": "fcm", "token": "fictional-bob", "device_id": a.deviceID}, nil)
	if statusCode != 204 && statusCode != 400 {
		t.Fatal("unexpected registration status")
	}
	if status, _ := b.do("PUT", "/v1/devices/current/push", map[string]string{"provider": "fcm", "token": "", "device_id": a.deviceID}, nil); status != 400 {
		t.Fatal("malformed registration accepted")
	}
	var aliceToken string
	if err := s.db.QueryRow(ctx, `SELECT token FROM push_tokens WHERE device_id=$1`, a.deviceID).Scan(&aliceToken); err != nil || aliceToken != "fictional-alice" {
		t.Fatal("another device's registration changed")
	}
	conv := direct(t, a, b)
	cmid := newUUID()
	for i := 0; i < 2; i++ {
		if status, _, _ := a.send(conv, cmid, 1); status != 200 {
			t.Fatalf("send: %d", status)
		}
	}
	var initialSeq int64
	if err := s.db.QueryRow(ctx, `SELECT event_seq FROM push_jobs WHERE device_id=$1`, b.deviceID).Scan(&initialSeq); err != nil {
		t.Fatal(err)
	}
	if count("push_jobs", b.deviceID) != 1 {
		t.Fatal("retry duplicated push jobs")
	}
	rollback := errors.New("rollback fixture")
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		if _, err := appendEvent(ctx, tx, b.deviceID, "message", nil, nil); err != nil {
			return err
		}
		return rollback
	})
	if !errors.Is(err, rollback) {
		t.Fatal("transaction did not roll back")
	}
	var afterRollback int64
	if err := s.db.QueryRow(ctx, `SELECT event_seq FROM push_jobs WHERE device_id=$1`, b.deviceID).Scan(&afterRollback); err != nil || afterRollback != initialSeq {
		t.Fatal("push escaped the message transaction")
	}

	var status atomic.Int32
	status.Store(503)
	var newMessage atomic.Bool
	var requests atomic.Int32
	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if newMessage.Swap(false) {
			if err := s.inTx(ctx, func(tx pgx.Tx) error { _, err := appendEvent(ctx, tx, b.deviceID, "message", nil, nil); return err }); err != nil {
				t.Error(err)
			}
		}
		w.WriteHeader(int(status.Load()))
		if status.Load() == 404 {
			_, _ = w.Write([]byte(`{"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}`))
		}
	}))
	defer endpoint.Close()
	fcm := &FCMClient{client: endpoint.Client(), endpoint: endpoint.URL}
	// A new server instance sees the queue persisted by the original process.
	restarted := New(s.db, Config{FCM: fcm}, s.log)
	restarted.pushBatch(ctx)
	var attempts int
	var retryAt time.Time
	if err := s.db.QueryRow(ctx, `SELECT attempts,next_attempt_at FROM push_jobs WHERE device_id=$1`, b.deviceID).Scan(&attempts, &retryAt); err != nil || attempts != 1 || !retryAt.After(time.Now()) {
		t.Fatal("transient failure lost the job or did not back off")
	}
	if count("push_tokens", b.deviceID) != 1 {
		t.Fatal("transient failure deleted the token")
	}
	if _, err := s.db.Exec(ctx, `UPDATE push_jobs SET next_attempt_at=now()`); err != nil {
		t.Fatal(err)
	}
	status.Store(200)
	newMessage.Store(true)
	restarted.pushBatch(ctx)
	if count("push_jobs", b.deviceID) != 1 {
		t.Fatal("completion erased a newer message's wake-up")
	}
	restarted.pushBatch(ctx)
	if count("push_jobs", b.deviceID) != 0 {
		t.Fatal("successful delivery stayed queued")
	}
	if status, _, _ := a.send(conv, newUUID(), 2); status != 200 {
		t.Fatal("second send failed")
	}
	status.Store(404)
	restarted.pushBatch(ctx)
	if count("push_tokens", b.deviceID) != 0 || count("push_jobs", b.deviceID) != 0 {
		t.Fatal("expired registration was not removed")
	}

	registerPush(b, "fictional-bob-new")
	if status, _, _ := a.send(conv, newUUID(), 3); status != 200 {
		t.Fatal("third send failed")
	}
	if status, _ := b.do("DELETE", "/v1/sessions/current", nil, nil); status != 204 {
		t.Fatal("logout failed")
	}
	if count("push_tokens", b.deviceID) != 0 || count("push_jobs", b.deviceID) != 0 {
		t.Fatal("logout retained push delivery")
	}
	if status, _ := a.do("DELETE", "/v1/devices/"+a.deviceID, nil, nil); status != 204 {
		t.Fatal("self-revocation failed")
	}
	if count("push_tokens", a.deviceID) != 0 {
		t.Fatal("revoked device retained its token")
	}
	if status, _ := a.do("PUT", "/v1/devices/current/push", map[string]string{"provider": "fcm", "token": "fictional-revoked"}, nil); status != 401 {
		t.Fatal("revoked device registered for push")
	}
	before := requests.Load()
	restarted.pushBatch(ctx)
	if requests.Load() != before {
		t.Fatal("logged-out or revoked device received push")
	}
}
