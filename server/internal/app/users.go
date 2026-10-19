package app

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
)

type userOut struct {
	UserID             string           `json:"user_id"`
	Username           string           `json:"username"`
	DisplayName        string           `json:"display_name"`
	Number             string           `json:"number"`
	AccountIdentityKey []byte           `json:"account_identity_key"`
	IdentityEpoch      string           `json:"identity_epoch"`
	Roster             rosterIn         `json:"roster"`
	ActiveDevices      []activeDeviceOut `json:"active_devices"`
}

type activeDeviceOut struct {
	ID          string `json:"id"`
	IdentityKey []byte `json:"identity_key"`
}

func (s *Server) loadUser(ctx context.Context, q interface {
	QueryRow(context.Context, string, ...any) pgx.Row
	Query(context.Context, string, ...any) (pgx.Rows, error)
}, where string, arg any) (*userOut, error) {
	u := &userOut{}
	var epoch, rev, number int64
	err := q.QueryRow(ctx, `SELECT id, `+nicknameSQL+`, display_name, number, account_identity_key, identity_epoch, roster_revision, roster_blob, roster_sig FROM users WHERE `+where, arg).
		Scan(&u.UserID, &u.Username, &u.DisplayName, &number, &u.AccountIdentityKey, &epoch, &rev, &u.Roster.Blob, &u.Roster.Signature)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	u.Number = strconv.FormatInt(number, 10)
	u.IdentityEpoch = strconv.FormatInt(epoch, 10)
	u.Roster.Revision = strconv.FormatInt(rev, 10)
	rows, err := q.Query(ctx, `SELECT id, identity_key FROM devices WHERE user_id=$1 AND revoked_at IS NULL ORDER BY id`, u.UserID)
	if err != nil {
		return nil, err
	}
	u.ActiveDevices, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (activeDeviceOut, error) {
		var d activeDeviceOut
		return d, row.Scan(&d.ID, &d.IdentityKey)
	})
	return u, err
}

// nicknameSQL is the username as its owner typed it; lookups use the lowercase column.
const nicknameSQL = `COALESCE(NULLIF(nickname, ''), username)`

func validateDisplayName(name string) (string, error) {
	name = strings.Join(strings.Fields(name), " ")
	if name == "" || utf8.RuneCountInString(name) > 64 {
		return "", errf(400, "bad_display_name", "Имя: от 1 до 64 символов")
	}
	return name, nil
}

// handlePutProfile changes the display name; related users refresh it on the "profile" event.
func (s *Server) handlePutProfile(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		DisplayName string `json:"display_name"`
	}
	if err := readJSON(w, r, 4<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	name, err := validateDisplayName(req.DisplayName)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		if _, err := tx.Exec(r.Context(), `UPDATE users SET display_name=$2 WHERE id=$1`, a.UserID, name); err != nil {
			return err
		}
		var err error
		notify, err = s.emitToRelated(r.Context(), tx, a.UserID, "profile", map[string]string{"user_id": a.UserID})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	writeJSON(w, 200, map[string]any{"display_name": name})
}

// handleResolve finds a user by exact username. There is no listing or prefix search.
func (s *Server) handleResolve(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.limits.allow("resolve:"+a.UserID, 60, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var req struct {
		Username string `json:"username"`
	}
	if err := readJSON(w, r, 1<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	name := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(req.Username), "@")))
	if !usernameRe.MatchString(name) {
		s.fail(w, r, errNotFound)
		return
	}
	u, err := s.loadUser(r.Context(), s.db, "username=$1", name)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if u.UserID != a.UserID {
		if _, err := s.db.Exec(r.Context(), `INSERT INTO contacts(owner_id, contact_id) VALUES ($1,$2) ON CONFLICT DO NOTHING`, a.UserID, u.UserID); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	writeJSON(w, 200, u)
}

func (s *Server) handleGetUser(w http.ResponseWriter, r *http.Request, a *authCtx) {
	target := r.PathValue("id")
	ok, err := s.related(r, a.UserID, target)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !ok {
		s.fail(w, r, errNotFound)
		return
	}
	u, err := s.loadUser(r.Context(), s.db, "id=$1", target)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, u)
}
