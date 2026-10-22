package app

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	maxCiphertextSize = 26 << 20
	dailyUploadQuota  = 250 << 20
	storedQuota       = 1 << 30
)

func (s *Server) objectPath(id string) string {
	// id is a validated UUID; user-supplied names never reach the filesystem.
	return filepath.Join(s.cfg.DataDir, "objects", id)
}

func (s *Server) handleReserveAttachment(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		Size   int64  `json:"size"`
		SHA256 []byte `json:"sha256"`
	}
	if err := readJSON(w, r, 1<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if req.Size <= 0 || req.Size > maxCiphertextSize || len(req.SHA256) != sha256.Size {
		s.fail(w, r, errf(413, "too_large", "Файл больше 25 МиБ"))
		return
	}
	id := newUUID()
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		// Serialize quota checks per account.
		if _, err := tx.Exec(r.Context(), `SELECT 1 FROM users WHERE id=$1 FOR UPDATE`, a.UserID); err != nil {
			return err
		}
		var daily, stored int64
		err := tx.QueryRow(r.Context(), `SELECT COALESCE(sum(size) FILTER (WHERE a.created_at > now() - interval '1 day'), 0),
			COALESCE(sum(size) FILTER (WHERE a.state='attached'), 0)
			FROM attachments a JOIN devices d ON d.id=a.owner_device_id WHERE d.user_id=$1`, a.UserID).Scan(&daily, &stored)
		if err != nil {
			return err
		}
		if daily+req.Size > dailyUploadQuota {
			return &apiError{Status: 429, Code: "daily_quota", Message: "Достигнут суточный лимит загрузки файлов", RetryAfterMs: int64(time.Hour / time.Millisecond)}
		}
		if stored+req.Size > storedQuota {
			return errf(413, "storage_quota", "Достигнут лимит хранения файлов")
		}
		_, err = tx.Exec(r.Context(), `INSERT INTO attachments(id, owner_device_id, size, sha256, state, expires_at) VALUES ($1,$2,$3,$4,'reserved', now() + interval '24 hours')`,
			id, a.DeviceID, req.Size, req.SHA256)
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 201, map[string]any{"attachment_id": id})
}

func (s *Server) handleUploadAttachment(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	var size int64
	var want []byte
	var state string
	err := s.db.QueryRow(r.Context(), `SELECT size, sha256, state FROM attachments WHERE id=$1 AND owner_device_id=$2 AND expires_at>now()`,
		nullUUID(id), a.DeviceID).Scan(&size, &want, &state)
	if errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, errNotFound)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if state != "reserved" {
		// Repeating a finished upload is idempotent.
		io.Copy(io.Discard, io.LimitReader(r.Body, size))
		w.WriteHeader(204)
		return
	}
	if r.ContentLength != size {
		s.fail(w, r, errf(400, "bad_length", "Размер не совпадает с резервированием"))
		return
	}
	tmpDir := filepath.Join(s.cfg.DataDir, "tmp")
	f, err := os.CreateTemp(tmpDir, "up-*")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(r.Body, size+1))
	if cerr := f.Sync(); err == nil {
		err = cerr
	}
	f.Close()
	if err != nil {
		s.fail(w, r, errf(400, "upload_interrupted", "Загрузка прервана"))
		return
	}
	if n != size || subtle.ConstantTimeCompare(h.Sum(nil), want) != 1 {
		s.fail(w, r, errf(400, "hash_mismatch", "Содержимое повреждено"))
		return
	}
	if err := os.Rename(tmp, s.objectPath(id)); err != nil {
		s.fail(w, r, err)
		return
	}
	if _, err := s.db.Exec(r.Context(), `UPDATE attachments SET state='ready' WHERE id=$1 AND state='reserved'`, id); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) handleDownloadAttachment(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	var size int64
	// Allowed: the uploading device, or a device that received an envelope of the
	// message while its account is still a member of the conversation.
	err := s.db.QueryRow(r.Context(), `SELECT at.size FROM attachments at
		LEFT JOIN messages m ON m.id=at.message_id
		WHERE at.id=$1 AND at.expires_at>now() AND at.state IN ('ready','attached') AND (
			at.owner_device_id=$2 OR (
				EXISTS(SELECT 1 FROM envelopes e WHERE e.message_id=m.id AND e.device_id=$2)
				AND EXISTS(SELECT 1 FROM memberships mm WHERE mm.conversation_id=m.conversation_id AND mm.user_id=$3)))`,
		nullUUID(id), a.DeviceID, a.UserID).Scan(&size)
	if errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, errNotFound)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	f, err := os.Open(s.objectPath(id))
	if err != nil {
		s.fail(w, r, errNotFound)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	w.WriteHeader(200)
	io.Copy(w, f)
}

// RunCleanup deletes expired objects, envelopes and events until ctx ends.
func (s *Server) RunCleanup(ctx context.Context) {
	t := time.NewTicker(15 * time.Minute)
	defer t.Stop()
	for {
		s.cleanupOnce(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (s *Server) cleanupOnce(ctx context.Context) {
	rows, err := s.db.Query(ctx, `DELETE FROM attachments WHERE expires_at < now() RETURNING id`)
	if err == nil {
		ids, _ := pgx.CollectRows(rows, pgx.RowTo[string])
		for _, id := range ids {
			os.Remove(s.objectPath(id))
		}
	}
	for _, q := range []string{
		`DELETE FROM email_links WHERE expires_at < now() - interval '1 day'`,
		`DELETE FROM device_events WHERE created_at < now() - interval '30 days'`,
		`DELETE FROM messages WHERE accepted_at < now() - interval '30 days'`,
		`DELETE FROM device_links WHERE expires_at < now() - interval '1 hour'`,
		`DELETE FROM sessions WHERE refresh_expires < now() OR revoked_at < now() - interval '1 day'`,
	} {
		if _, err := s.db.Exec(ctx, q); err != nil && ctx.Err() == nil {
			s.log.Error("cleanup", "err", err)
		}
	}
}
