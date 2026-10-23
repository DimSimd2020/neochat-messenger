package app

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

func TestReinstallationReplacesDeviceAtLimit(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "reinstall_owner")
	id := strings.Repeat("a", 64)
	if status, _ := a.do("PUT", "/v1/devices/current/installation", map[string]string{"installation_id": id}, nil); status != 204 {
		t.Fatal("cannot register installation")
	}
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	if err := s.inTx(context.Background(), func(tx pgx.Tx) error {
		for i := 1; i < maxDevices; i++ {
			d := fakeDevice(newUUID(), pub)
			if err := insertDevice(context.Background(), tx, a.userID, &d, false); err != nil {
				return err
			}
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	c := &client{t: t, base: ts.URL}
	start := map[string]string{"username": "reinstall_owner", "password": "wrong-password!", "installation_id": id, "platform": "linux"}
	if status, _ := c.do("POST", "/v1/devices/new/start", start, nil); status != 401 {
		t.Fatal("installation hint bypassed password")
	}
	start["password"] = "correct horse battery"
	var login map[string]any
	if status, _ := c.do("POST", "/v1/devices/new/start", start, &login); status != 200 || login["replace_device_id"] != a.deviceID {
		t.Fatal("same installation rejected at device limit")
	}
	d := fakeDevice(newUUID(), pub)
	d.InstallationID = id
	request := map[string]any{"start_token": login["start_token"], "device": d, "roster": rosterIn{Revision: "1", Blob: []byte("new roster"), Signature: rnd(64)}}
	if status, _ := c.do("POST", "/v1/devices/new/finish", request, nil); status != 409 {
		t.Fatal("stale roster accepted")
	}
	if status, _ := a.do("GET", "/v1/sync", nil, nil); status != 200 {
		t.Fatal("failed finish revoked old device")
	}
	request["roster"] = rosterIn{Revision: "2", Blob: []byte("new roster"), Signature: rnd(64)}
	if status, _ := c.do("POST", "/v1/devices/new/finish", request, nil); status != 200 {
		t.Fatal("replacement finish failed")
	}
	if status, _ := a.do("GET", "/v1/sync", nil, nil); status != 401 {
		t.Fatal("old session survived replacement")
	}
	var count int
	s.db.QueryRow(context.Background(), `SELECT count(*) FROM devices WHERE user_id=$1 AND revoked_at IS NULL`, a.userID).Scan(&count)
	if count != maxDevices {
		t.Fatal("replacement consumed an additional slot")
	}
	var primary bool
	if err := s.db.QueryRow(context.Background(), `SELECT is_primary FROM devices WHERE id=$1 AND revoked_at IS NULL`, d.DeviceID).Scan(&primary); err != nil || !primary {
		t.Fatal("primary device role lost")
	}
	s.db.QueryRow(context.Background(), `SELECT count(*) FROM one_time_prekeys WHERE device_id=$1`, a.deviceID).Scan(&count)
	if count != 0 {
		t.Fatal("old prekeys retained")
	}
	if status, _ := c.do("POST", "/v1/devices/new/finish", request, nil); status != 401 {
		t.Fatal("finish token reusable")
	}
	start["installation_id"] = strings.Repeat("b", 64)
	if status, _ := c.do("POST", "/v1/devices/new/start", start, nil); status != 409 {
		t.Fatal("unrelated installation bypassed device limit")
	}
}

func TestQRReinstallationReplacesDevice(t *testing.T) {
	s, ts := testServer(t)
	a := register(t, s, ts, "qr_reinstall")
	id := strings.Repeat("c", 64)
	a.do("PUT", "/v1/devices/current/installation", map[string]string{"installation_id": id}, nil)
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	d := fakeDevice(newUUID(), pub)
	d.InstallationID = id
	c := &client{t: t, base: ts.URL}
	var link map[string]any
	if status, _ := c.do("POST", "/v1/devices/qr", map[string]any{"device": d}, &link); status != 201 {
		t.Fatal("QR start failed")
	}
	path := "/v1/devices/qr/" + link["link_id"].(string)
	var pending map[string]any
	if status, _ := a.do("GET", path+"/device", nil, &pending); status != 200 || pending["replace_device_id"] != a.deviceID {
		t.Fatal("QR did not identify previous installation")
	}
	if status, _ := a.do("POST", path+"/approve", map[string]any{"roster": rosterIn{Revision: "2", Blob: []byte("qr roster"), Signature: rnd(64)}, "sealed": rnd(80)}, nil); status != 204 {
		t.Fatal("QR replacement failed")
	}
	var count int
	s.db.QueryRow(context.Background(), `SELECT count(*) FROM devices WHERE user_id=$1 AND revoked_at IS NULL`, a.userID).Scan(&count)
	if count != 1 {
		t.Fatal("QR reinstallation left duplicate device")
	}
}
