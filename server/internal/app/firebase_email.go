package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"time"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/auth"
	"google.golang.org/api/option"
)

// EmailSender delivers purpose-specific Firebase mail. NeoChat passwords and keys
// never go to Firebase; password reset codes only prove access to the mailbox.
type EmailSender interface {
	SendVerification(context.Context, string, string) error
	SendPasswordReset(context.Context, string, string) error
	CompleteAction(context.Context, string, string) (string, error)
}

type FirebaseEmailSender struct {
	auth          *auth.Client
	apiKey        string
	http          *http.Client
	customActions bool
}

func NewFirebaseEmailSender(ctx context.Context, credentials, apiKey string, customActions bool) (EmailSender, error) {
	if apiKey == "" {
		return nil, nil
	}
	app, err := firebase.NewApp(ctx, nil, option.WithCredentialsFile(credentials))
	if err != nil {
		return nil, errors.New("invalid Firebase email configuration")
	}
	client, err := app.Auth(ctx)
	if err != nil {
		return nil, errors.New("cannot initialize Firebase email")
	}
	return &FirebaseEmailSender{auth: client, apiKey: apiKey, http: &http.Client{Timeout: 10 * time.Second}, customActions: customActions}, nil
}

func (f *FirebaseEmailSender) call(ctx context.Context, method string, body any, out any) error {
	encoded, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, "POST", "https://identitytoolkit.googleapis.com/v1/accounts:"+method+"?key="+url.QueryEscape(f.apiKey), bytes.NewReader(encoded))
	if err != nil {
		return errors.New("invalid Firebase email request")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Firebase-Locale", "ru")
	resp, err := f.http.Do(req)
	if err != nil {
		return errors.New("Firebase email transport failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return errors.New("Firebase email request rejected")
	}
	if out != nil && json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(out) != nil {
		return errors.New("invalid Firebase email response")
	}
	return nil
}

func (f *FirebaseEmailSender) SendVerification(ctx context.Context, email, continuation string) error {
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	user, err := f.auth.GetUserByEmail(ctx, email)
	if auth.IsUserNotFound(err) {
		user, err = f.auth.CreateUser(ctx, (&auth.UserToCreate{}).UID("neochat-email-"+newUUID()).Email(email))
		if auth.IsEmailAlreadyExists(err) {
			user, err = f.auth.GetUserByEmail(ctx, email)
		}
	}
	if err != nil {
		return errors.New("cannot prepare Firebase email verification")
	}
	// Firebase skips verification delivery for already-verified addresses.
	// NeoChat ownership is proven by our own link_hash, not by Firebase emailVerified.
	if user.EmailVerified {
		if _, err := f.auth.UpdateUser(ctx, user.UID, (&auth.UserToUpdate{}).EmailVerified(false)); err != nil {
			return errors.New("cannot prepare Firebase email delivery")
		}
	}
	// A Firebase session exists only here, to deliver the continueUrl.
	// NeoChat passwords, recovery keys and sessions never go to Firebase.
	token, err := f.auth.CustomToken(ctx, user.UID)
	if err != nil {
		return errors.New("cannot authorize Firebase email verification")
	}
	var session struct {
		IDToken string `json:"idToken"`
	}
	if err := f.call(ctx, "signInWithCustomToken", map[string]any{"token": token, "returnSecureToken": true}, &session); err != nil {
		return err
	}
	if session.IDToken == "" {
		return errors.New("missing Firebase email session")
	}
	return f.call(ctx, "sendOobCode", map[string]any{"requestType": "VERIFY_EMAIL", "idToken": session.IDToken, "email": email, "continueUrl": continuation, "canHandleCodeInApp": false}, nil)
}

func (f *FirebaseEmailSender) SendPasswordReset(ctx context.Context, email, continuation string) error {
	// Enable only after the project's callback URL points to our handler. The
	// default Firebase reset page would otherwise ask for an unrelated password.
	if !f.customActions {
		return f.SendVerification(ctx, email, continuation)
	}
	return f.call(ctx, "sendOobCode", map[string]any{"requestType": "PASSWORD_RESET", "email": email, "continueUrl": continuation, "canHandleCodeInApp": false}, nil)
}

func (f *FirebaseEmailSender) CompleteAction(ctx context.Context, mode, code string) (string, error) {
	var info struct {
		Email string `json:"email"`
		Type  string `json:"requestType"`
	}
	// Without newPassword this validates any OOB code without changing an account.
	if err := f.call(ctx, "resetPassword", map[string]string{"oobCode": code}, &info); err != nil {
		return "", err
	}
	if (mode == "verifyEmail" && info.Type != "VERIFY_EMAIL") || (mode == "resetPassword" && info.Type != "PASSWORD_RESET") || (mode != "verifyEmail" && mode != "resetPassword") {
		return "", errors.New("Firebase email action purpose mismatch")
	}
	if mode == "verifyEmail" {
		if err := f.call(ctx, "update", map[string]string{"oobCode": code}, nil); err != nil {
			return "", err
		}
	}
	// The NeoChat one-time token is consumed by our password-reset endpoint.
	// Never set a Firebase password for the service record.
	return normalizeEmail(info.Email)
}
