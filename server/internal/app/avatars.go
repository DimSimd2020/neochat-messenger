package app

import (
	"bytes"
	"image/jpeg"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/jackc/pgx/v5"
)

const maxAvatarSize = 48 << 10

func validateAvatar(data []byte) error {
	if len(data) > maxAvatarSize {
		return errf(413, "bad_avatar", "Аватар: JPEG до 48 КБ и 512×512")
	}
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(data))
	if err != nil || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 512 || cfg.Height > 512 {
		return errf(400, "bad_avatar", "Аватар: JPEG до 48 КБ и 512×512")
	}
	if _, err := jpeg.Decode(bytes.NewReader(data)); err != nil {
		return errf(400, "bad_avatar", "Не удалось прочитать аватар")
	}
	return nil
}

func (s *Server) avatarPath(id string) string {
	return filepath.Join(s.cfg.DataDir, "avatars", id+".jpg")
}

func (s *Server) replaceAvatar(r *http.Request, a *authCtx, id string) error {
	var old string
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		if err := tx.QueryRow(r.Context(), `SELECT avatar FROM users WHERE id=$1 FOR UPDATE`, a.UserID).Scan(&old); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `UPDATE users SET avatar=$2 WHERE id=$1`, a.UserID, id); err != nil {
			return err
		}
		var err error
		notify, err = s.emitToRelated(r.Context(), tx, a.UserID, "profile", map[string]string{"user_id": a.UserID})
		return err
	})
	if err != nil {
		return err
	}
	// Every upload has a new UUID: an old file can never be reused by a later update.
	if isUUID(old) {
		os.Remove(s.avatarPath(old))
	}
	s.hub.notify(notify)
	return nil
}

func (s *Server) handleUploadAvatar(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.limits.allow("avatar:"+a.UserID, 30, time.Hour) {
		s.fail(w, r, errRateLimited(time.Hour))
		return
	}
	data, err := io.ReadAll(io.LimitReader(r.Body, maxAvatarSize+1))
	if err != nil {
		s.fail(w, r, errf(400, "upload_interrupted", "Загрузка прервана"))
		return
	}
	if err := validateAvatar(data); err != nil {
		s.fail(w, r, err)
		return
	}
	if err := os.MkdirAll(filepath.Join(s.cfg.DataDir, "avatars"), 0o700); err != nil {
		s.fail(w, r, err)
		return
	}
	id := newUUID()
	if err := os.WriteFile(s.avatarPath(id), data, 0o600); err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.replaceAvatar(r, a, id); err != nil {
		os.Remove(s.avatarPath(id))
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]string{"avatar_id": id})
}

func (s *Server) handleDeleteAvatar(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if err := s.replaceAvatar(r, a, ""); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) handleGetAvatar(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id, avatar := r.PathValue("id"), r.PathValue("avatar")
	if !isUUID(avatar) {
		s.fail(w, r, errNotFound)
		return
	}
	allowed, err := s.related(r, a.UserID, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var current bool
	if allowed {
		err = s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND avatar=$2)`, nullUUID(id), avatar).Scan(&current)
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !current {
		s.fail(w, r, errNotFound)
		return
	}
	f, err := os.Open(s.avatarPath(avatar))
	if err != nil {
		s.fail(w, r, errNotFound)
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "avatar.jpg", info.ModTime(), f)
}
