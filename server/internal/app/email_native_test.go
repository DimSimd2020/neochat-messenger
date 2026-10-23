package app

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// Only test binaries contain this mailbox. It simulates the owner following the
// verification email; the HTTP API, one-time proof and crypto remain real.
type nativeMailbox struct{ server *Server }

func (m nativeMailbox) SendPasswordReset(context.Context, string, string) error { return errBadRequest }
func (m nativeMailbox) CompleteAction(context.Context, string, string) (string, error) {
	return "", errBadRequest
}

func (m nativeMailbox) SendVerification(ctx context.Context, email, link string) error {
	if !strings.HasSuffix(email, "@example.test") {
		return errBadRequest
	}
	go func() {
		u, _ := url.Parse(link)
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			var sent bool
			if m.server.db.QueryRow(context.Background(), `SELECT sent_at IS NOT NULL FROM email_links WHERE link_hash=$1`, emailHash(u.Query().Get("t"))).Scan(&sent) != nil {
				return
			}
			if sent {
				response, err := http.PostForm(u.Scheme+"://"+u.Host+u.Path, url.Values{"t": {u.Query().Get("t")}})
				if err == nil {
					response.Body.Close()
				}
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()
	return nil
}

// NEOCHAT_NATIVE_TEST_COMMAND is a JSON argument array, e.g. [".../neochat_uitest.exe"].
// {server} is available to host/simulator clients, {android_server} to an emulator.
func TestNativeEmailFlow(t *testing.T) {
	input := os.Getenv("NEOCHAT_NATIVE_TEST_COMMAND")
	if input == "" {
		t.Skip("native test command not set")
	}
	var args []string
	if json.Unmarshal([]byte(input), &args) != nil || len(args) == 0 {
		t.Fatal("invalid native test command")
	}
	s, ts := testServer(t)
	s.cfg.Email = nativeMailbox{server: s}
	s.cfg.EmailBaseURL = ts.URL
	for i, arg := range args {
		args[i] = strings.ReplaceAll(strings.ReplaceAll(arg, "{android_server}", strings.Replace(ts.URL, "127.0.0.1", "10.0.2.2", 1)), "{server}", ts.URL)
	}
	command := exec.Command(args[0], args[1:]...)
	command.Env = append(os.Environ(), "NEOCHAT_E2E_SERVER="+ts.URL, "TEST_RUNNER_NEOCHAT_TEST_SERVER="+ts.URL)
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Run(); err != nil {
		var prepared, sent int
		_ = s.db.QueryRow(context.Background(), `SELECT count(*),count(sent_at) FROM email_links`).Scan(&prepared, &sent)
		t.Logf("test mailbox proofs prepared: %d, delivered: %d", prepared, sent)
		t.Fatal("native email integration failed")
	}
}
