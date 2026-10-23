package app

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

type emailTransport func(*http.Request) (*http.Response, error)

func (f emailTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestFirebaseEmailActionPurposes(t *testing.T) {
	for _, mode := range []string{"verifyEmail", "resetPassword"} {
		updates := 0
		kind := "VERIFY_EMAIL"
		if mode == "resetPassword" {
			kind = "PASSWORD_RESET"
		}
		f := &FirebaseEmailSender{apiKey: "test", http: &http.Client{Transport: emailTransport(func(r *http.Request) (*http.Response, error) {
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil {
				t.Fatal("invalid request")
			}
			if _, exists := body["newPassword"]; exists {
				t.Fatal("NeoChat password sent to Firebase")
			}
			if strings.HasSuffix(r.URL.Path, ":update") {
				updates++
			}
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`{"email":"owner@example.test","requestType":"` + kind + `"}`)), Header: make(http.Header)}, nil
		})}}
		email, err := f.CompleteAction(context.Background(), mode, "test-code")
		if err != nil || email != "owner@example.test" {
			t.Fatal("valid action rejected")
		}
		if mode == "resetPassword" && updates != 0 || mode == "verifyEmail" && updates != 1 {
			t.Fatal("wrong Firebase action applied")
		}
		otherMode := "verifyEmail"
		if mode == otherMode {
			otherMode = "resetPassword"
		}
		if _, err := f.CompleteAction(context.Background(), otherMode, "test-code"); err == nil {
			t.Fatal("mismatched action type accepted")
		}
	}
}

func TestFirebaseResetUsesOwnTemplate(t *testing.T) {
	f := &FirebaseEmailSender{apiKey: "test", customActions: true, http: &http.Client{Transport: emailTransport(func(r *http.Request) (*http.Response, error) {
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		if body["requestType"] != "PASSWORD_RESET" || body["email"] != "owner@example.test" || body["idToken"] != nil {
			t.Fatal("reset used verification transport")
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`{}`)), Header: make(http.Header)}, nil
	})}}
	if err := f.SendPasswordReset(context.Background(), "owner@example.test", "https://example.test/continue"); err != nil {
		t.Fatal(err)
	}
}
