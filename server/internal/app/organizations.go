package app

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// CreateOrganization is used by the maintenance command, outside the HTTP API.
func CreateOrganization(ctx context.Context, db *pgxpool.Pool, name, username string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" || utf8.RuneCountInString(name) > 64 {
		return "", errBadRequest
	}
	tx, err := db.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)
	var owner, id string
	if err := tx.QueryRow(ctx, "SELECT id FROM users WHERE username=$1", strings.ToLower(strings.TrimPrefix(strings.TrimSpace(username), "@"))).Scan(&owner); err != nil {
		return "", err
	}
	err = tx.QueryRow(ctx, `INSERT INTO organizations(name, owner_id) VALUES ($1,$2)
		ON CONFLICT (lower(name)) DO UPDATE SET name=organizations.name WHERE organizations.owner_id=EXCLUDED.owner_id
		RETURNING id`, name, owner).Scan(&id)
	if err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx, "INSERT INTO organization_members VALUES ($1,$2,'owner') ON CONFLICT DO NOTHING", id, owner); err != nil {
		return "", err
	}
	return id, tx.Commit(ctx)
}

type organizationOut struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Badge   string `json:"badge"`
	Role    string `json:"role"`
	Channel string `json:"channel_id,omitempty"`
	Group   string `json:"group_id,omitempty"`
	IconID  string `json:"icon_id,omitempty"`
}

func (s *Server) handleOrganizations(w http.ResponseWriter, r *http.Request, a *authCtx) {
	rows, err := s.db.Query(r.Context(), `SELECT o.id, o.name, o.badge, m.role,
		CASE WHEN m.role='invited' THEN '' ELSE coalesce(o.channel_id::text,'') END,
		CASE WHEN m.role='invited' THEN '' ELSE coalesce(o.group_id::text,'') END,
		coalesce(o.icon_id::text,'')
		FROM organization_members m JOIN organizations o ON o.id=m.organization_id
		WHERE m.user_id=$1 ORDER BY lower(o.name)`, a.UserID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (organizationOut, error) {
		var o organizationOut
		err := row.Scan(&o.ID, &o.Name, &o.Badge, &o.Role, &o.Channel, &o.Group, &o.IconID)
		return o, err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleOrganizationBadges(w http.ResponseWriter, r *http.Request, a *authCtx) {
	user := r.PathValue("user")
	if !isUUID(user) {
		s.fail(w, r, errBadRequest)
		return
	}
	rows, err := s.db.Query(r.Context(), `SELECT o.name, o.badge FROM organization_members m
		JOIN organizations o ON o.id=m.organization_id
		WHERE m.user_id=$1 AND m.role IN ('owner','admin') ORDER BY lower(o.name)`, user)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	type badge struct {
		Name  string `json:"name"`
		Emoji string `json:"emoji"`
	}
	out, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (badge, error) {
		var b badge
		err := row.Scan(&b.Name, &b.Emoji)
		return b, err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleOrganizationMembers(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	if !isUUID(id) {
		s.fail(w, r, errBadRequest)
		return
	}
	rows, err := s.db.Query(r.Context(), `SELECT u.id, `+nicknameSQL+`, m.role
		FROM organization_members viewer JOIN organization_members m ON m.organization_id=viewer.organization_id
		JOIN users u ON u.id=m.user_id
		WHERE viewer.organization_id=$1 AND viewer.user_id=$2 AND viewer.role<>'invited'
		AND (m.role<>'invited' OR viewer.role IN ('owner','admin')) ORDER BY u.username`, id, a.UserID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	type member struct {
		UserID   string `json:"user_id"`
		Username string `json:"username"`
		Role     string `json:"role"`
	}
	out, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (member, error) {
		var m member
		err := row.Scan(&m.UserID, &m.Username, &m.Role)
		return m, err
	})
	if err == nil && len(out) == 0 {
		err = errNotFound
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleOrganizationUpdate(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	var req struct {
		Action   string `json:"action"`
		Badge    string `json:"badge"`
		Username string `json:"username"`
		UserID   string `json:"user_id"`
		Role     string `json:"role"`
		ChatID   string `json:"conversation_id"`
	}
	if err := readJSON(w, r, 4096, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if !isUUID(id) {
		s.fail(w, r, errBadRequest)
		return
	}
	ctx := r.Context()
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		var role string
		// Serialize permission checks with membership changes.
		if err := tx.QueryRow(ctx, "SELECT id FROM organizations WHERE id=$1 FOR UPDATE", id).Scan(&id); err != nil {
			return err
		}
		err := tx.QueryRow(ctx, "SELECT role FROM organization_members WHERE organization_id=$1 AND user_id=$2", id, a.UserID).Scan(&role)
		if err != nil {
			return err
		}
		switch req.Action {
		case "accept", "decline":
			if role != "invited" {
				return errForbidden
			}
			if req.Action == "decline" {
				_, err = tx.Exec(ctx, "DELETE FROM organization_members WHERE organization_id=$1 AND user_id=$2", id, a.UserID)
			} else {
				_, err = tx.Exec(ctx, "UPDATE organization_members SET role='member' WHERE organization_id=$1 AND user_id=$2", id, a.UserID)
			}
		case "invite":
			if role != "owner" && role != "admin" {
				return errForbidden
			}
			name := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(req.Username), "@"))
			var user string
			if err := tx.QueryRow(ctx, "SELECT id FROM users WHERE username=$1", name).Scan(&user); err != nil {
				return err
			}
			_, err = tx.Exec(ctx, "INSERT INTO organization_members VALUES ($1,$2,'invited') ON CONFLICT DO NOTHING", id, user)
		case "role":
			if role != "owner" {
				return errForbidden
			}
			if !isUUID(req.UserID) || (req.Role != "member" && req.Role != "admin") {
				return errBadRequest
			}
			tag, err := tx.Exec(ctx, "UPDATE organization_members SET role=$3 WHERE organization_id=$1 AND user_id=$2 AND role IN ('member','admin')", id, req.UserID, req.Role)
			if err == nil && tag.RowsAffected() == 0 {
				return errNotFound
			}
			return err
		case "badge":
			if role != "owner" {
				return errForbidden
			}
			badge := strings.TrimSpace(req.Badge)
			if len(badge) > 64 || !strings.ContainsFunc(badge, isBadgeSymbol) ||
				strings.ContainsFunc(badge, func(r rune) bool {
					return !isBadgeSymbol(r) && r != '\u200d' && r != '\ufe0f' &&
						!(r >= '\U0001f3fb' && r <= '\U0001f3ff') && !(r >= '\U000e0020' && r <= '\U000e007f')
				}) {
				return errf(400, "bad_badge", "Укажите эмодзи для значка")
			}
			_, err = tx.Exec(ctx, "UPDATE organizations SET badge=$2 WHERE id=$1", id, badge)
		case "link_channel", "link_group":
			if role != "owner" {
				return errForbidden
			}
			if !isUUID(req.ChatID) {
				return errBadRequest
			}
			kind := strings.TrimPrefix(req.Action, "link_")
			var chat string
			if err := tx.QueryRow(ctx, "SELECT id FROM conversations WHERE id=$1 AND owner_id=$2 AND kind=$3", req.ChatID, a.UserID, kind).Scan(&chat); err != nil {
				return err
			}
			err = setOrganizationChat(ctx, tx, id, kind, chat)
			if isUniqueViolation(err) {
				return errf(409, "organization_chat_linked", "Чат уже привязан к организации")
			}
		default:
			return errBadRequest
		}
		return err
	})
	if errors.Is(err, pgx.ErrNoRows) {
		err = errNotFound
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func isBadgeSymbol(r rune) bool { return unicode.Is(unicode.So, r) }

func setOrganizationChat(ctx context.Context, tx pgx.Tx, id, kind, chat string) error {
	if kind == "channel" {
		_, err := tx.Exec(ctx, "UPDATE organizations SET channel_id=$2 WHERE id=$1", id, chat)
		return err
	}
	_, err := tx.Exec(ctx, "UPDATE organizations SET group_id=$2 WHERE id=$1", id, chat)
	return err
}
