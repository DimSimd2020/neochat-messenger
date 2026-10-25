package app

import (
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestAlphaBuildsAreOnlyForTesters(t *testing.T) {
	s, ts := testServer(t)
	tester := register(t, s, ts, "alpha_tester")
	other := register(t, s, ts, "alpha_other")
	if _, err := s.db.Exec(context.Background(), `INSERT INTO alpha_testers(user_id, username) VALUES ($1, $2)`, tester.userID, tester.username); err != nil {
		t.Fatal(err)
	}

	alpha := filepath.Join(s.cfg.DataDir, "updates", "alpha")
	os.MkdirAll(alpha, 0o755)
	os.WriteFile(filepath.Join(alpha, "manifest.json"), []byte(`{"channels":{"alpha":{}}}`), 0o644)
	os.WriteFile(filepath.Join(alpha, "manifest.sig"), []byte("sig"), 0o644)
	os.WriteFile(filepath.Join(alpha, "NeoChat-0.6.9.5-alpha-x64.msi"), []byte("alpha build"), 0o644)

	file := "/v1/app/alpha/files/NeoChat-0.6.9.5-alpha-x64.msi"
	if st, _ := tester.do("GET", "/v1/app/alpha/manifest", nil, nil); st != 200 {
		t.Fatalf("tester manifest: %d", st)
	}
	if st, _ := other.do("GET", "/v1/app/alpha/manifest", nil, nil); st != 404 {
		t.Fatalf("other manifest: %d", st)
	}
	if st, _ := other.do("GET", file, nil, nil); st != 404 {
		t.Fatalf("other file: %d", st)
	}
	anon := &client{t: t, base: ts.URL}
	if st, _ := anon.do("GET", "/v1/app/alpha/manifest", nil, nil); st != 401 {
		t.Fatalf("anonymous manifest: %d", st)
	}

	resp, err := http.Get(ts.URL + file)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 401 {
		t.Fatalf("anonymous file: %d", resp.StatusCode)
	}
	req, _ := http.NewRequest("GET", ts.URL+file, nil)
	req.Header.Set("Authorization", "Bearer "+tester.token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || string(body) != "alpha build" {
		t.Fatalf("tester file: %d %q", resp.StatusCode, body)
	}

	// The public channel never reaches into the alpha directory.
	for _, path := range []string{"/v1/app/files/NeoChat-0.6.9.5-alpha-x64.msi", "/v1/app/files/alpha/NeoChat-0.6.9.5-alpha-x64.msi", "/v1/app/manifest"} {
		if st, _ := anon.do("GET", path, nil, nil); st != 404 {
			t.Fatalf("public %s: %d", path, st)
		}
	}
}

func TestModelFilesArePublicAndNamesAreChecked(t *testing.T) {
	s, ts := testServer(t)
	dir := filepath.Join(s.cfg.DataDir, "models")
	os.MkdirAll(dir, 0o755)
	os.WriteFile(filepath.Join(dir, "stt-ru-test-tokens.txt"), []byte("tokens"), 0o644)
	os.WriteFile(filepath.Join(s.cfg.DataDir, "secret.txt"), []byte("secret"), 0o644)
	get := func(path string) (int, string) {
		resp, err := http.Get(ts.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(body)
	}
	if st, body := get("/v1/app/models/stt-ru-test-tokens.txt"); st != 200 || body != "tokens" {
		t.Fatalf("model file: %d %q", st, body)
	}
	for _, name := range []string{"stt-ru-test-missing.txt", "..%2Fsecret.txt", "secret.txt", "stt-.hidden", "stt-x.exe"} {
		if st, _ := get("/v1/app/models/" + name); st != 404 {
			t.Fatalf("%s: %d", name, st)
		}
	}
}
