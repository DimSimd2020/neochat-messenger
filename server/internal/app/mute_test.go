package app

import (
	"testing"
	"time"
)

func TestMuteMember(t *testing.T) {
	s, ts := testServer(t)
	owner := register(t, s, ts, "olga")
	admin := register(t, s, ts, "adam")
	bob := register(t, s, ts, "bob")
	carol := register(t, s, ts, "carol")
	for _, u := range []*client{admin, bob, carol} {
		owner.resolve(u)
	}
	gid := newUUID()
	var g conversationOut
	if st, m := owner.do("POST", "/v1/conversations/groups", map[string]any{
		"group_id": gid, "members": []string{admin.userID, bob.userID, carol.userID}, "admins": []string{admin.userID},
		"state": signedState{Revision: "1", Blob: []byte("g1"), Signature: rnd(64)},
	}, &g); st != 201 {
		t.Fatalf("create group: %d %v", st, m)
	}
	mute := func(by *client, target *client, until int64) (int, map[string]any) {
		return by.do("PUT", "/v1/conversations/"+gid+"/members/"+target.userID+"/mute", map[string]int64{"until": until}, nil)
	}
	view := func(c *client) conversationOut {
		var out conversationOut
		if st, m := c.do("GET", "/v1/conversations/"+gid, nil, &out); st != 200 {
			t.Fatalf("get: %d %v", st, m)
		}
		return out
	}
	sendAs := func(c *client, control bool) (int, map[string]any) {
		conv := view(c)
		recips := []map[string]any{}
		for _, r := range conv.Recipients {
			recips = append(recips, map[string]any{"target_device_id": r.DeviceID, "ciphertext": []byte{1, 1, 2, 3}})
		}
		body := map[string]any{
			"version": 1, "client_message_id": newUUID(), "conversation_revision": conv.Revision,
			"recipient_set_revision": conv.RecipientSetRevision, "recipients": recips, "attachment_ids": []string{},
		}
		if control {
			body["control"] = true
		}
		return c.do("POST", "/v1/conversations/"+gid+"/messages", body, nil)
	}

	if st, _ := sendAs(bob, false); st != 200 {
		t.Fatalf("bob writes before the mute: %d", st)
	}

	// Ordinary members cannot mute anyone; nobody mutes the owner or themselves.
	if st, m := mute(bob, carol, -1); st != 403 || m["code"] != "admin_required" {
		t.Fatalf("member mutes: %d %v", st, m)
	}
	if st, m := mute(admin, owner, -1); st != 403 || m["code"] != "cannot_mute" {
		t.Fatalf("admin mutes owner: %d %v", st, m)
	}
	if st, m := mute(admin, admin, -1); st != 403 || m["code"] != "cannot_mute" {
		t.Fatalf("admin mutes self: %d %v", st, m)
	}
	if st, m := mute(owner, bob, time.Now().Add(-time.Hour).UnixMilli()); st != 400 || m["code"] != "bad_until" {
		t.Fatalf("mute in the past: %d %v", st, m)
	}

	until := time.Now().Add(time.Hour).UnixMilli()
	if st, m := mute(admin, bob, until); st != 200 {
		t.Fatalf("admin mutes member: %d %v", st, m)
	}
	var muted *int64
	for _, m := range view(carol).Members {
		if m.UserID == bob.userID {
			muted = m.MutedUntil
		}
	}
	if muted == nil || *muted < until-2 || *muted > until+2 {
		t.Fatalf("members list shows muted_until=%v, want about %d", muted, until)
	}
	if st, m := sendAs(bob, false); st != 403 || m["code"] != "muted" {
		t.Fatalf("muted member writes: %d %v", st, m)
	}
	// Receipts and votes are not content.
	if st, m := sendAs(bob, true); st != 200 {
		t.Fatalf("muted member sends a control event: %d %v", st, m)
	}
	if st, _ := sendAs(carol, false); st != 200 {
		t.Fatalf("other members are unaffected: %d", st)
	}

	// Only the owner mutes admins.
	if st, m := mute(owner, admin, -1); st != 200 {
		t.Fatalf("owner mutes admin: %d %v", st, m)
	}
	if st, m := mute(admin, admin, 0); st != 403 {
		t.Fatalf("muted admin unmutes self: %d %v", st, m)
	}
	if st, m := mute(owner, admin, 0); st != 200 {
		t.Fatalf("owner unmutes admin: %d %v", st, m)
	}

	if st, m := mute(owner, bob, 0); st != 200 {
		t.Fatalf("unmute: %d %v", st, m)
	}
	if st, m := sendAs(bob, false); st != 200 {
		t.Fatalf("unmuted member writes: %d %v", st, m)
	}
	for _, m := range view(carol).Members {
		if m.UserID == bob.userID && m.MutedUntil != nil {
			t.Fatalf("unmuted member still listed as muted: %d", *m.MutedUntil)
		}
	}

	// An expired mute stops counting even while the row remains.
	if _, err := s.db.Exec(t.Context(), `UPDATE memberships SET muted_until = now() - interval '1 minute' WHERE conversation_id=$1 AND user_id=$2`, gid, bob.userID); err != nil {
		t.Fatal(err)
	}
	if st, m := sendAs(bob, false); st != 200 {
		t.Fatalf("member with an expired mute writes: %d %v", st, m)
	}

	// Directs and strangers cannot be muted.
	dm := direct(t, owner, bob)
	if st, m := owner.do("PUT", "/v1/conversations/"+dm.ID+"/members/"+bob.userID+"/mute", map[string]int64{"until": -1}, nil); st == 200 {
		t.Fatalf("muted in a direct chat: %d %v", st, m)
	}
	dave := register(t, s, ts, "dave")
	if st, _ := mute(owner, dave, -1); st != 404 {
		t.Fatalf("muted a non-member: %d", st)
	}
}
