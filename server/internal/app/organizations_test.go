package app

import (
	"bytes"
	"context"
	"image"
	"image/png"
	"io"
	"net/http"
	"testing"
)

func TestOrganizationAccess(t *testing.T) {
	s, ts := testServer(t)
	owner := register(t, s, ts, "founder")
	member := register(t, s, ts, "member")
	outsider := register(t, s, ts, "outsider")
	id, err := CreateOrganization(context.Background(), s.db, "NeoChat", "@Founder")
	if err != nil {
		t.Fatal(err)
	}
	if got, err := CreateOrganization(context.Background(), s.db, "neochat", "founder"); err != nil || got != id {
		t.Fatalf("retry = %q, %v", got, err)
	}
	if _, err := CreateOrganization(context.Background(), s.db, "NeoChat", "outsider"); err == nil {
		t.Fatal("organization ownership was replaced")
	}
	path := "/v1/organizations/" + id
	request := func(c *client, method, path string, body, out any, want int) {
		t.Helper()
		if status, msg := c.do(method, path, body, out); status != want {
			t.Fatalf("%s %s via %s: got %d, want %d: %v", method, path, c.username, status, want, msg)
		}
	}
	action := func(c *client, data map[string]string, want int) {
		t.Helper()
		request(c, "POST", path, data, nil, want)
	}
	badgePath := "/v1/users/" + member.userID + "/organization-badges"
	var badges []map[string]string
	request(outsider, "POST", "/v1/organizations", map[string]string{"name": "Other"}, nil, 405)
	request(outsider, "GET", path+"/members", nil, nil, 404)
	action(outsider, map[string]string{"action": "accept"}, 404)
	action(owner, map[string]string{"action": "invite", "username": "@Member"}, 204)
	action(owner, map[string]string{"action": "invite", "username": "member"}, 204)
	request(member, "GET", path+"/members", nil, nil, 404)
	var organizations []organizationOut
	request(member, "GET", "/v1/organizations", nil, &organizations, 200)
	if len(organizations) != 1 || organizations[0].Role != "invited" {
		t.Fatalf("invitations = %+v", organizations)
	}
	action(member, map[string]string{"action": "accept"}, 204)
	action(member, map[string]string{"action": "badge", "badge": "⚡"}, 403)
	action(member, map[string]string{"action": "invite", "username": "outsider"}, 403)
	request(outsider, "GET", badgePath, nil, &badges, 200)
	if len(badges) != 0 {
		t.Fatal("regular member received a badge")
	}
	action(owner, map[string]string{"action": "role", "user_id": member.userID, "role": "admin"}, 204)
	action(owner, map[string]string{"action": "badge", "badge": "test⚡"}, 400)
	action(owner, map[string]string{"action": "badge", "badge": "⚡"}, 204)
	request(outsider, "GET", badgePath, nil, &badges, 200)
	if len(badges) != 1 || badges[0]["name"] != "NeoChat" || badges[0]["emoji"] != "⚡" {
		t.Fatalf("admin badges = %+v", badges)
	}
	action(member, map[string]string{"action": "invite", "username": "outsider"}, 204)
	action(outsider, map[string]string{"action": "decline"}, 204)
	action(owner, map[string]string{"action": "role", "user_id": member.userID, "role": "member"}, 204)
	request(outsider, "GET", badgePath, nil, &badges, 200)
	if len(badges) != 0 {
		t.Fatal("revoked administrator kept a badge")
	}

	for _, kind := range []string{"channel", "group"} {
		chatID := newUUID()
		create := map[string]any{
			"group_id": chatID, "kind": kind, "organization_id": id,
			"state": signedState{Revision: "1", Blob: []byte("state"), Signature: rnd(64)},
		}
		request(member, "POST", "/v1/conversations/groups", create, nil, 404)
		request(owner, "POST", "/v1/conversations/groups", create, nil, 201)
		create["group_id"] = newUUID()
		request(owner, "POST", "/v1/conversations/groups", create, nil, 409)
		action(member, map[string]string{"action": "link_" + kind, "conversation_id": chatID}, 403)
		wrongKind := "group"
		if kind == "group" {
			wrongKind = "channel"
		}
		action(owner, map[string]string{"action": "link_" + wrongKind, "conversation_id": chatID}, 404)
		action(owner, map[string]string{"action": "link_" + kind, "conversation_id": chatID}, 204)
		request(outsider, "GET", "/v1/conversations/"+chatID, nil, nil, 404)
		request(member, "GET", "/v1/conversations/"+chatID, nil, nil, 404)
	}
	request(owner, "GET", "/v1/organizations", nil, &organizations, 200)
	if len(organizations) != 1 || organizations[0].Channel == "" || organizations[0].Group == "" {
		t.Fatalf("linked chats = %+v", organizations)
	}
}

func TestOrganizationIcon(t *testing.T) {
	s, ts := testServer(t)
	owner := register(t, s, ts, "owner")
	visitor := register(t, s, ts, "visitor")
	id, err := CreateOrganization(context.Background(), s.db, "Example", owner.username)
	if err != nil {
		t.Fatal(err)
	}
	var icon bytes.Buffer
	if err := png.Encode(&icon, image.NewRGBA(image.Rect(0, 0, 96, 96))); err != nil {
		t.Fatal(err)
	}
	request := func(c *client, method, path string, body []byte, want int) []byte {
		t.Helper()
		req, err := http.NewRequest(method, ts.URL+path, bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+c.token)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		if res.StatusCode != want {
			t.Fatalf("%s %s: %d, want %d", method, path, res.StatusCode, want)
		}
		data, err := io.ReadAll(res.Body)
		if err != nil {
			t.Fatal(err)
		}
		return data
	}
	path := "/v1/organizations/" + id + "/icon"
	request(visitor, http.MethodPut, path, icon.Bytes(), 403)
	request(owner, http.MethodPut, path, []byte("not a PNG"), 400)
	request(owner, http.MethodPut, path, icon.Bytes(), 204)
	var badges map[string][]map[string]string
	body := map[string][]string{"user_ids": {owner.userID, visitor.userID}}
	if status, _ := visitor.do("POST", "/v1/organization-badges", body, &badges); status != 200 {
		t.Fatalf("badge lookup status = %d", status)
	}
	if len(badges[owner.userID]) != 1 || len(badges[visitor.userID]) != 0 {
		t.Fatalf("badges = %+v", badges)
	}
	firstID := badges[owner.userID][0]["icon_id"]
	iconPath := "/v1/organization-icons/" + firstID
	if got := request(visitor, http.MethodGet, iconPath, nil, 200); !bytes.Equal(got, icon.Bytes()) {
		t.Fatal("downloaded icon differs from the upload")
	}
	request(owner, http.MethodPut, path, icon.Bytes(), 204)
	request(visitor, http.MethodGet, iconPath, nil, 404)
}
