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

// EmailSender delivers a private continueUrl only to the addressed mailbox via Firebase
// Authentication OOB (VERIFY_EMAIL). Used for registration/link confirmation and for
// password-reset continueUrl. No SMTP. The app never receives this URL or a Firebase token.
type EmailSender interface {
	SendVerification(context.Context, string, string) error
}

type FirebaseEmailSender struct {
	auth   *auth.Client
	apiKey string
	http   *http.Client
}

func NewFirebaseEmailSender(ctx context.Context, credentials, apiKey string) (EmailSender, error) {
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
	return &FirebaseEmailSender{auth: client, apiKey: apiKey, http: &http.Client{Timeout: 10 * time.Second}}, nil
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
	// Password-reset and re-link reuse this same VERIFY_EMAIL delivery. Firebase
	// skips OOB for already-verified addresses, so clear the flag before send.
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
