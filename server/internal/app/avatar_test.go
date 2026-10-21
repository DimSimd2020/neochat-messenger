package app

import (
	"bytes"
	"image"
	"image/jpeg"
	"io"
	"net/http"
	"os"
	"testing"
)

func TestAvatarValidation(t *testing.T) {
	encode := func(width, height int) []byte {
		var b bytes.Buffer
		if err := jpeg.Encode(&b, image.NewRGBA(image.Rect(0, 0, width, height)), nil); err != nil {
			t.Fatal(err)
		}
		return b.Bytes()
	}
	valid := encode(256, 256)
	for _, test := range []struct {
		name string
		data []byte
		ok   bool
	}{
		{"empty upload", nil, false}, {"jpeg", valid, true},
		{"not an image", []byte("test"), false},
		{"oversize", make([]byte, maxAvatarSize+1), false}, {"too wide", encode(513, 16), false},
		{"too tall", encode(16, 513), false}, {"truncated", valid[:len(valid)/2], false},
	} {
		t.Run(test.name, func(t *testing.T) {
			if (validateAvatar(test.data) == nil) != test.ok {
				t.Fatalf("unexpected validation result")
			}
		})
	}
}

func TestAvatarFilesAndPermissions(t *testing.T) {
	s, ts := testServer(t)
	alice := register(t, s, ts, "avatar_alice")
	bob := register(t, s, ts, "avatar_bob")
	stranger := register(t, s, ts, "avatar_stranger")
	var jpegBody bytes.Buffer
	jpeg.Encode(&jpegBody, image.NewRGBA(image.Rect(0, 0, 256, 256)), nil)
	request := func(c *client, method, path string, body []byte) (int, []byte) {
		t.Helper()
		req, _ := http.NewRequest(method, ts.URL+path, bytes.NewReader(body))
		if c != nil {
			req.Header.Set("Authorization", "Bearer "+c.token)
		}
		req.Header.Set("Content-Type", "image/jpeg")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		data, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatal(err)
		}
		return resp.StatusCode, data
	}
	if st, _ := request(nil, "PUT", "/v1/accounts/current/avatar", jpegBody.Bytes()); st != 401 {
		t.Fatalf("anonymous upload: %d", st)
	}
	if st, _ := request(alice, "PUT", "/v1/accounts/current/avatar", []byte("bad image")); st != 400 {
		t.Fatalf("invalid image: %d", st)
	}
	if st, _ := request(alice, "PUT", "/v1/accounts/current/avatar", make([]byte, maxAvatarSize+1)); st != 413 {
		t.Fatalf("oversize image: %d", st)
	}
	if st, _ := request(alice, "PUT", "/v1/accounts/current/avatar", jpegBody.Bytes()); st != 200 {
		t.Fatalf("upload: %d", st)
	}
	var profile userOut
	alice.do("GET", "/v1/users/"+alice.userID, nil, &profile)
	if !isUUID(profile.AvatarID) {
		t.Fatal("profile must contain a file ID")
	}
	id := profile.AvatarID
	path := "/v1/users/" + alice.userID + "/avatar/" + id
	if st, _ := request(nil, "GET", path, nil); st != 401 {
		t.Fatalf("anonymous download: %d", st)
	}
	if st, _ := request(stranger, "GET", path, nil); st != 404 {
		t.Fatalf("unrelated download: %d", st)
	}
	bob.resolve(alice)
	if st, data := request(bob, "GET", path, nil); st != 200 || !bytes.Equal(data, jpegBody.Bytes()) {
		t.Fatal("contact did not receive original JPEG bytes")
	}
	if st, _ := request(alice, "PUT", "/v1/accounts/current/avatar", jpegBody.Bytes()); st != 200 {
		t.Fatalf("replace: %d", st)
	}
	alice.do("GET", "/v1/users/"+alice.userID, nil, &profile)
	if profile.AvatarID == id {
		t.Fatal("replacement must invalidate the cached file ID")
	}
	if _, err := os.Stat(s.avatarPath(id)); err != nil {
		t.Fatal("old profile photo must remain in the gallery")
	}
	if st, _ := request(bob, "GET", path, nil); st != 200 {
		t.Fatalf("old file: %d", st)
	}
	if st, _ := alice.do("DELETE", "/v1/accounts/current/avatar", nil, nil); st != 200 {
		t.Fatalf("delete: %d", st)
	}
	if _, err := os.Stat(s.avatarPath(profile.AvatarID)); !os.IsNotExist(err) {
		t.Fatal("deleted avatar file remains")
	}
	alice.do("GET", "/v1/users/"+alice.userID, nil, &profile)
	if profile.AvatarID != id {
		t.Fatal("deleting the current photo must select the previous photo")
	}
	if st, _ := alice.do("DELETE", "/v1/accounts/current/photos/"+id, nil, nil); st != 200 {
		t.Fatalf("delete previous: %d", st)
	}
	alice.do("GET", "/v1/users/"+alice.userID, nil, &profile)
	if profile.AvatarID != "" {
		t.Fatal("avatar ID remains after deletion")
	}
	var full bytes.Buffer
	jpeg.Encode(&full, image.NewRGBA(image.Rect(0, 0, 960, 640)), nil)
	if st, _ := request(alice, "PUT", "/v1/accounts/current/photos", full.Bytes()); st != 200 {
		t.Fatalf("full photo: %d", st)
	}
	alice.do("GET", "/v1/users/"+alice.userID, nil, &profile)
	fullPath := "/v1/users/" + alice.userID + "/photos/" + profile.AvatarID
	if st, data := request(bob, "GET", fullPath, nil); st != 200 || !bytes.Equal(data, full.Bytes()) {
		t.Fatal("full uncropped image did not survive upload")
	}
	if st, _ := request(stranger, "GET", fullPath, nil); st != 404 {
		t.Fatalf("unrelated full image access: %d", st)
	}
	if st, _ := stranger.do("DELETE", "/v1/accounts/current/photos/"+profile.AvatarID, nil, nil); st != 404 {
		t.Fatalf("deleting another user's photo: %d", st)
	}
}
