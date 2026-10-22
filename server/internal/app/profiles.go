package app

import (
	"bytes"
	"errors"
	"image"
	"image/jpeg"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/jackc/pgx/v5"
)

// Public salt material, like the original username-based salt. Never returns a
// password verifier or a backup key. Unknown logins have the same response shape.
// login may be a nickname or a verified email; response includes canonical username.
func (s *Server) handlePasswordSalt(w http.ResponseWriter, r *http.Request) {
	if !s.limits.allow("salt:"+clientIP(r), 60, 15*time.Minute) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var req struct {
		Username string `json:"username"`
		Login    string `json:"login"`
	}
	if err := readJSON(w, r, 1024, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	raw := req.Login
	if raw == "" {
		raw = req.Username
	}
	login := normalizeLogin(raw)
	if login == "" {
		s.fail(w, r, errBadRequest)
		return
	}
	salt, username := login, login
	var err error
	if looksLikeEmail(login) {
		if _, e := normalizeEmail(login); e != nil {
			s.fail(w, r, e)
			return
		}
		err = s.db.QueryRow(r.Context(), `SELECT COALESCE(NULLIF(password_salt,''),username), username FROM users
			WHERE email=$1 AND email_verified_at IS NOT NULL`, login).Scan(&salt, &username)
	} else {
		if !usernameRe.MatchString(login) {
			s.fail(w, r, errBadRequest)
			return
		}
		err = s.db.QueryRow(r.Context(), `SELECT COALESCE(NULLIF(password_salt,''),username), username FROM users WHERE username=$1`, login).
			Scan(&salt, &username)
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]string{"salt": salt, "username": username})
}

const maxPhotoSize = 8 << 20
const maxProfilePhotos = 20

func (s *Server) photoPath(id string) string {
	return filepath.Join(s.cfg.DataDir, "avatars", id+"-full.jpg")
}

func (s *Server) handleUploadPhoto(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.limits.allow("avatar:"+a.UserID, 30, time.Hour) {
		s.fail(w, r, errRateLimited(time.Hour))
		return
	}
	data, err := io.ReadAll(io.LimitReader(r.Body, maxPhotoSize+1))
	if err != nil {
		s.fail(w, r, errBadRequest)
		return
	}
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(data))
	if err != nil || len(data) > maxPhotoSize || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 4096 || cfg.Height > 4096 {
		s.fail(w, r, errf(400, "bad_photo", "Фото: JPEG до 8 МБ и 4096×4096"))
		return
	}
	src, err := jpeg.Decode(bytes.NewReader(data))
	if err != nil {
		s.fail(w, r, errBadRequest)
		return
	}
	// A small square thumbnail for chat rows; the complete image stays intact.
	side := min(cfg.Width, cfg.Height)
	thumb := image.NewRGBA(image.Rect(0, 0, 256, 256))
	for y := 0; y < 256; y++ {
		for x := 0; x < 256; x++ {
			thumb.Set(x, y, src.At((cfg.Width-side)/2+x*side/256, (cfg.Height-side)/2+y*side/256))
		}
	}
	var small bytes.Buffer
	for _, quality := range []int{80, 60, 40} {
		small.Reset()
		if err = jpeg.Encode(&small, thumb, &jpeg.Options{Quality: quality}); err != nil {
			s.fail(w, r, err)
			return
		}
		if small.Len() <= maxAvatarSize {
			break
		}
	}
	if small.Len() > maxAvatarSize {
		s.fail(w, r, errf(400, "bad_photo", "Не удалось подготовить миниатюру"))
		return
	}
	if err = os.MkdirAll(filepath.Join(s.cfg.DataDir, "avatars"), 0700); err != nil {
		s.fail(w, r, err)
		return
	}
	id := newUUID()
	if err = os.WriteFile(s.photoPath(id), data, 0600); err != nil {
		s.fail(w, r, err)
		return
	}
	if err = os.WriteFile(s.avatarPath(id), small.Bytes(), 0600); err != nil {
		os.Remove(s.photoPath(id))
		s.fail(w, r, err)
		return
	}
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		var current string
		if err := tx.QueryRow(r.Context(), `SELECT avatar FROM users WHERE id=$1 FOR UPDATE`, a.UserID).Scan(&current); err != nil {
			return err
		}
		var count int
		if err := tx.QueryRow(r.Context(), `SELECT count(*) FROM profile_photos WHERE user_id=$1`, a.UserID).Scan(&count); err != nil {
			return err
		}
		if count >= maxProfilePhotos {
			return errf(409, "photo_limit", "Можно хранить до 20 фото. Удалите одно из старых")
		}
		if _, err := tx.Exec(r.Context(), `INSERT INTO profile_photos(id,user_id) VALUES($1,$2)`, id, a.UserID); err != nil {
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
		os.Remove(s.photoPath(id))
		os.Remove(s.avatarPath(id))
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	writeJSON(w, 200, map[string]string{"avatar_id": id})
}

func (s *Server) handleListPhotos(w http.ResponseWriter, r *http.Request, a *authCtx) {
	uid := r.PathValue("id")
	ok, err := s.related(r, a.UserID, uid)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !ok {
		s.fail(w, r, errNotFound)
		return
	}
	rows, err := s.db.Query(r.Context(), `SELECT p.id FROM profile_photos p JOIN users u ON u.id=p.user_id WHERE p.user_id=$1 ORDER BY (p.id::text=u.avatar) DESC,p.created_at DESC,p.id DESC LIMIT 20`, uid)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"photos": ids})
}

func (s *Server) handleGetPhoto(w http.ResponseWriter, r *http.Request, a *authCtx) {
	uid, id := r.PathValue("id"), r.PathValue("photo")
	ok, err := s.related(r, a.UserID, uid)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !ok || !isUUID(id) {
		s.fail(w, r, errNotFound)
		return
	}
	var exists bool
	err = s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM profile_photos WHERE user_id=$1 AND id=$2)`, uid, id).Scan(&exists)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !exists {
		s.fail(w, r, errNotFound)
		return
	}
	f, err := os.Open(s.photoPath(id))
	if os.IsNotExist(err) {
		f, err = os.Open(s.avatarPath(id))
	}
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
	http.ServeContent(w, r, "profile.jpg", info.ModTime(), f)
}

func (s *Server) handleDeletePhoto(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("photo")
	if !isUUID(id) {
		s.fail(w, r, errNotFound)
		return
	}
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		var current string
		if err := tx.QueryRow(r.Context(), `SELECT avatar FROM users WHERE id=$1 FOR UPDATE`, a.UserID).Scan(&current); err != nil {
			return err
		}
		tag, err := tx.Exec(r.Context(), `DELETE FROM profile_photos WHERE id=$1 AND user_id=$2`, id, a.UserID)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return errNotFound
		}
		if current == id {
			next := ""
			err = tx.QueryRow(r.Context(), `SELECT id FROM profile_photos WHERE user_id=$1 ORDER BY created_at DESC,id DESC LIMIT 1`, a.UserID).Scan(&next)
			if err != nil && !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
			if _, err = tx.Exec(r.Context(), `UPDATE users SET avatar=$2 WHERE id=$1`, a.UserID, next); err != nil {
				return err
			}
		}
		notify, err = s.emitToRelated(r.Context(), tx, a.UserID, "profile", map[string]string{"user_id": a.UserID})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	os.Remove(s.avatarPath(id))
	os.Remove(s.photoPath(id))
	s.hub.notify(notify)
	writeJSON(w, 200, map[string]bool{"ok": true})
}
