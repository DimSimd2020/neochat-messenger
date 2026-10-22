package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"time"

	"github.com/jackc/pgx/v5"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

// FCMClient sends data-only hints. Tokens, message bodies and server credentials are never logged.
type FCMClient struct {
	client   *http.Client
	endpoint string
}

// NewFCMClient accepts a service-account file, not the Android google-services.json.
// An empty path leaves the existing socket and periodic-sync paths in place.
func NewFCMClient(ctx context.Context, path string) (*FCMClient, error) {
	if path == "" {
		return nil, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, errors.New("cannot read FCM service account")
	}
	config, err := google.JWTConfigFromJSON(data, "https://www.googleapis.com/auth/firebase.messaging")
	if err != nil {
		return nil, errors.New("invalid FCM service account")
	}
	var account struct {
		Project string `json:"project_id"`
	}
	if json.Unmarshal(data, &account) != nil || account.Project == "" {
		return nil, errors.New("missing FCM project ID")
	}
	authContext := context.WithValue(ctx, oauth2.HTTPClient, &http.Client{Timeout: 10 * time.Second})
	client := config.Client(authContext)
	client.Timeout = 10 * time.Second
	return &FCMClient{client: client, endpoint: "https://fcm.googleapis.com/v1/projects/" + url.PathEscape(account.Project) + "/messages:send"}, nil
}

// send returns true only for a definitively unregistered token. Other failures must not delete it.
func (f *FCMClient) send(ctx context.Context, token string) (bool, error) {
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"fid":     token,
		"data":    map[string]string{"type": "sync"},
		"android": map[string]string{"priority": "high", "ttl": "60s", "collapse_key": "sync", "restricted_package_name": "app.neochat"},
	}})
	req, err := http.NewRequestWithContext(ctx, "POST", f.endpoint, bytes.NewReader(body))
	if err != nil {
		return false, errors.New("invalid FCM endpoint")
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.client.Do(req)
	if err != nil {
		return false, errors.New("FCM transport failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return false, nil
	}
	var reply struct {
		Error struct {
			Details []struct {
				Type string `json:"@type"`
				Code string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	_ = json.NewDecoder(io.LimitReader(resp.Body, 32<<10)).Decode(&reply)
	for _, detail := range reply.Error.Details {
		if resp.StatusCode == 404 && detail.Type == "type.googleapis.com/google.firebase.fcm.v1.FcmError" && detail.Code == "UNREGISTERED" {
			return true, nil
		}
	}
	return false, fmt.Errorf("FCM rejected request (HTTP %d)", resp.StatusCode)
}

type pushJob struct {
	device, token string
	seq           int64
	attempts      int
}

// RunPush uses one worker and bounded batches. The queue commits with each encrypted envelope.
func (s *Server) RunPush(ctx context.Context) {
	if s.cfg.FCM == nil {
		return
	}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		s.pushBatch(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (s *Server) pushBatch(ctx context.Context) {
	rows, err := s.db.Query(ctx, `SELECT j.device_id, p.token, j.event_seq, j.attempts
		FROM push_jobs j JOIN push_tokens p ON p.device_id=j.device_id JOIN devices d ON d.id=j.device_id
		WHERE p.provider='fcm' AND d.revoked_at IS NULL AND j.next_attempt_at<=now()
		ORDER BY j.next_attempt_at LIMIT 32`)
	if err != nil {
		return
	}
	jobs, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (pushJob, error) {
		var j pushJob
		err := row.Scan(&j.device, &j.token, &j.seq, &j.attempts)
		return j, err
	})
	if err != nil {
		return
	}
	for _, j := range jobs {
		if ctx.Err() != nil {
			return
		}
		// Recheck revocation/token rotation after collecting the batch, before handing data to Google.
		var valid bool
		if err := s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM push_tokens p JOIN devices d ON d.id=p.device_id
			WHERE p.device_id=$1 AND p.token=$2 AND p.provider='fcm' AND d.revoked_at IS NULL)`, j.device, j.token).Scan(&valid); err != nil || !valid {
			continue
		}
		unregistered, sendErr := s.cfg.FCM.send(ctx, j.token)
		if unregistered {
			_, _ = s.db.Exec(ctx, `DELETE FROM push_tokens WHERE device_id=$1 AND token=$2`, j.device, j.token)
		} else if sendErr == nil {
			_, _ = s.db.Exec(ctx, `DELETE FROM push_jobs j WHERE device_id=$1 AND event_seq=$2
				AND EXISTS(SELECT 1 FROM push_tokens p WHERE p.device_id=j.device_id AND p.token=$3)`, j.device, j.seq, j.token)
		} else {
			delay := time.Minute * time.Duration(1<<min(j.attempts, 5))
			_, _ = s.db.Exec(ctx, `UPDATE push_jobs SET attempts=attempts+1, next_attempt_at=$3 WHERE device_id=$1 AND event_seq=$2`, j.device, j.seq, time.Now().Add(delay))
			s.log.Warn("push delivery deferred", "reason", sendErr.Error())
		}
	}
}
