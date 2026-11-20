package app

import (
	"context"
	"encoding/json"
	"testing"
)

func TestConversationCopyProtection(t *testing.T) {
	s, ts := testServer(t)
	alice := register(t, s, ts, "alice")
	bob := register(t, s, ts, "bob")
	eve := register(t, s, ts, "eve")
	chat := direct(t, alice, bob)
	path := "/v1/conversations/" + chat.ID
	change := func(c *client, rule, action string, rev int64, want int) copyProtection {
		t.Helper()
		var out copyProtection
		status, body := c.do("PUT", path+"/copy-protection", map[string]any{
			"rule": rule, "action": action, "revision": rev,
		}, &out)
		if status != want {
			t.Fatalf("%s %s: got %d, want %d (%v)", rule, action, status, want, body)
		}
		return out
	}

	if chat.CopyProtection.Revision != 0 || len(chat.CopyProtection.Rules) != 0 {
		t.Fatal("existing chats must allow every action by default")
	}
	change(eve, "copy", "block", 0, 404)
	change(alice, "export", "block", 0, 400)
	first := change(alice, "copy", "block", 0, 200)
	if first.Revision != 1 || first.Rules["copy"].By != alice.userID {
		t.Fatal("copy must belong to Alice's account")
	}
	change(bob, "copy", "allow", 1, 403)
	if out := change(bob, "copy", "block", 1, 200); out.Revision != 1 || out.Rules["copy"].By != alice.userID {
		t.Fatal("a repeated block must not replace the original blocker")
	}
	if retry := change(alice, "copy", "block", 0, 409); retry.Revision != 0 {
		t.Fatal("a stale write must fail")
	}
	if out := change(bob, "copy", "request", 1, 200); out.Revision != 2 || !out.Rules["copy"].Requested {
		t.Fatal("request must be persistent and leave the rule blocked")
	}
	if out := change(bob, "copy", "request", 2, 200); out.Revision != 2 {
		t.Fatal("duplicate requests must not generate events")
	}
	change(bob, "forward", "block", 2, 200)
	out := change(alice, "copy", "allow", 3, 200)
	if _, present := out.Rules["copy"]; present || out.Rules["forward"].By != bob.userID {
		t.Fatal("allow must clear exactly the corresponding rule and its request")
	}
	_, cursor := alice.syncAll("")
	change(bob, "screenshot", "block", 4, 200)
	events, _ := alice.syncAll(cursor)
	if len(events) != 1 || events[0].Kind != "copy_protection" {
		t.Fatalf("expected one change after reconnect, got %d", len(events))
	}
	var event struct {
		ID     string         `json:"conversation_id"`
		Policy copyProtection `json:"copy_protection"`
	}
	if err := json.Unmarshal(events[0].Payload, &event); err != nil || event.ID != chat.ID ||
		event.Policy.Revision != 5 || event.Policy.Rules["screenshot"].By != bob.userID {
		t.Fatalf("incorrect sync payload: %+v, %v", event, err)
	}
	var fresh conversationOut
	if status, _ := alice.do("GET", path, nil, &fresh); status != 200 || fresh.CopyProtection.Revision != 5 {
		t.Fatal("conversation must include current policy")
	}
	// An expired feed still provides the latest state, including to devices that missed every update.
	if _, err := s.db.Exec(context.Background(), `DELETE FROM device_events WHERE device_id=$1`, alice.deviceID); err != nil {
		t.Fatal(err)
	}
	var snapshot struct {
		Policies []struct {
			ID     string         `json:"conversation_id"`
			Policy copyProtection `json:"copy_protection"`
		} `json:"copy_protection"`
	}
	if status, _ := alice.do("GET", "/v1/sync?copy_protection=1", nil, &snapshot); status != 200 ||
		len(snapshot.Policies) != 1 || snapshot.Policies[0].ID != chat.ID || snapshot.Policies[0].Policy.Revision != 5 {
		t.Fatal("fresh sync must provide current protection after feed expiration")
	}
	results := make(chan int, 2)
	for _, participant := range []*client{alice, bob} {
		go func() {
			status, _ := participant.do("PUT", path+"/copy-protection", map[string]any{
				"rule": "copy", "action": "block", "revision": 5,
			}, nil)
			results <- status
		}()
	}
	a, b := <-results, <-results
	if !((a == 200 && b == 409) || (a == 409 && b == 200)) {
		t.Fatalf("concurrent block must have one winner, got %d and %d", a, b)
	}
	if status, _ := bob.do("DELETE", "/v1/devices/"+bob.deviceID, nil, nil); status != 204 {
		t.Fatal("could not revoke the test device")
	}
	change(bob, "forward", "allow", 6, 401)
}
