package app

import (
	"bytes"
	"errors"
	"image/png"
	"io"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
)

const maxOrganizationIconSize = 48 << 10

func (s *Server) handleOrganizationBadgesBatch(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		Users []string `json:"user_ids"`
	}
	if err := readJSON(w, r, 4096, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(req.Users) > 64 {
		s.fail(w, r, errBadRequest)
		return
	}
	for _, id := range req.Users {
		if !isUUID(id) {
			s.fail(w, r, errBadRequest)
			return
		}
	}
	type badge struct {
		Name string `json:"name"`
		Icon string `json:"icon_id,omitempty"`
	}
	rows, err := s.db.Query(r.Context(), `SELECT m.user_id, o.name, coalesce(o.icon_id::text,'')
		FROM organization_members m JOIN organizations o ON o.id=m.organization_id
		WHERE m.user_id=ANY($1::uuid[]) AND m.role IN ('owner','admin')
		ORDER BY m.user_id, lower(o.name)`, req.Users)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	defer rows.Close()
	out := make(map[string][]badge)
	for rows.Next() {
		var user string
		var b badge
		if err := rows.Scan(&user, &b.Name, &b.Icon); err != nil {
			s.fail(w, r, err)
			return
		}
		out[user] = append(out[user], b)
	}
	if err := rows.Err(); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleSetOrganizationIcon(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	if !isUUID(id) {
		s.fail(w, r, errBadRequest)
		return
	}
	var owned bool
	err := s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM organizations WHERE id=$1 AND owner_id=$2)`, id, a.UserID).Scan(&owned)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !owned {
		s.fail(w, r, errForbidden)
		return
	}
	if !s.limits.allow("organization-icon:"+a.UserID, 30, time.Hour) {
		s.fail(w, r, errRateLimited(time.Hour))
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxOrganizationIconSize))
	if err == nil {
		var width, height int
		if cfg, e := png.DecodeConfig(bytes.NewReader(body)); e == nil {
			width, height = cfg.Width, cfg.Height
		}
		if width > 0 && width <= 128 && height > 0 && height <= 128 {
			_, err = png.Decode(bytes.NewReader(body))
		} else {
			err = errBadRequest
		}
	}
	if err != nil {
		s.fail(w, r, errf(400, "bad_organization_icon", "Значок: PNG до 128×128 и 48 КБ"))
		return
	}
	tag, err := s.db.Exec(r.Context(), `UPDATE organizations SET icon_id=$3, icon_png=$4 WHERE id=$1 AND owner_id=$2`, id, a.UserID, newUUID(), body)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if tag.RowsAffected() == 0 {
		s.fail(w, r, errNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleOrganizationIcon(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	if !isUUID(id) {
		s.fail(w, r, errNotFound)
		return
	}
	var body []byte
	err := s.db.QueryRow(r.Context(), "SELECT icon_png FROM organizations WHERE icon_id=$1", id).Scan(&body)
	if errors.Is(err, pgx.ErrNoRows) {
		err = errNotFound
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "private, max-age=86400, immutable")
	w.Write(body)
}
