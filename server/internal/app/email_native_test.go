package app

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"strings"
	"testing"
)

// Only test binaries contain this mailbox. It simulates the owner following the
// verification email; the HTTP API, one-time proof and crypto remain real.
type nativeMailbox struct{}

func (m nativeMailbox) SendPasswordReset(context.Context, string, string) error { return errBadRequest }
func (m nativeMailbox) CompleteAction(context.Context, string, string) (string, error) {
	return "", errBadRequest
}

func (m nativeMailbox) SendVerification(_ context.Context, email, _ string) error {
	if !strings.HasSuffix(email, "@example.test") {
		return errBadRequest
	}
	return nil
}

func (m nativeMailbox) IsEmailVerified(_ context.Context, email string) (bool, error) {
	return strings.HasSuffix(email, "@example.test"), nil
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
	s.cfg.Email = nativeMailbox{}
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
