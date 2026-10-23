package app

import (
	"context"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type userOut struct {
	UserID             string            `json:"user_id"`
	Username           string            `json:"username"`
	DisplayName        string            `json:"display_name"`
	Bio                string            `json:"bio"`
	NameColor          string            `json:"name_color"`
	Header             string            `json:"header"`
	AvatarID           string            `json:"avatar_id"`
	Number             string            `json:"number"`
	AccountIdentityKey []byte            `json:"account_identity_key"`
	IdentityEpoch      string            `json:"identity_epoch"`
	Roster             rosterIn          `json:"roster"`
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
	err := q.QueryRow(ctx, `SELECT id, `+nicknameSQL+`, display_name, bio, name_color, header, number, account_identity_key, identity_epoch, roster_revision, roster_blob, roster_sig, avatar FROM users WHERE `+where, arg).
		Scan(&u.UserID, &u.Username, &u.DisplayName, &u.Bio, &u.NameColor, &u.Header, &number, &u.AccountIdentityKey, &epoch, &rev, &u.Roster.Blob, &u.Roster.Signature, &u.AvatarID)
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

const maxBio = 100

var hexColorRe = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)

// validateNameColor accepts '#rrggbb' or an empty string (the default colour).
func validateNameColor(c string) (string, error) {
	c = strings.TrimSpace(c)
	if c != "" && !hexColorRe.MatchString(c) {
		return "", errf(400, "bad_name_color", "Цвет имени: #RRGGBB")
	}
	return strings.ToLower(c), nil
}

// validateHeader accepts up to three '#rrggbb' joined by commas, or an empty string.
func validateHeader(h string) (string, error) {
	h = strings.TrimSpace(h)
	if h == "" {
		return "", nil
	}
	parts := strings.Split(h, ",")
	if len(parts) > 3 {
		return "", errf(400, "bad_header", "Шапка профиля: до трёх цветов")
	}
	for i, p := range parts {
		p = strings.TrimSpace(p)
		if !hexColorRe.MatchString(p) {
			return "", errf(400, "bad_header", "Шапка профиля: цвета в виде #RRGGBB")
		}
		parts[i] = strings.ToLower(p)
	}
	return strings.Join(parts, ","), nil
}

// handlePutProfile changes the display name and/or the about text; related users
// refresh it on the "profile" event.
func (s *Server) handlePutProfile(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		DisplayName *string `json:"display_name"`
		Bio         *string `json:"bio"`
		Username    *string `json:"username"`
		NameColor   *string `json:"name_color"`
		Header      *string `json:"header"`
	}
	if err := readJSON(w, r, 4<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	var name string
	if req.DisplayName != nil {
		var err error
		if name, err = validateDisplayName(*req.DisplayName); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	var bio string
	if req.Bio != nil {
		bio = strings.TrimSpace(*req.Bio)
		if utf8.RuneCountInString(bio) > maxBio || !utf8.ValidString(bio) {
			s.fail(w, r, errf(400, "bad_bio", "О себе: до 100 символов"))
			return
		}
	}
	var nameColor, header string
	if req.NameColor != nil {
		var err error
		if nameColor, err = validateNameColor(*req.NameColor); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	if req.Header != nil {
		var err error
		if header, err = validateHeader(*req.Header); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		if req.Username != nil {
			nick := strings.TrimPrefix(strings.TrimSpace(*req.Username), "@")
			if !nicknameRe.MatchString(nick) || strings.EqualFold(nick, "everyone") || strings.EqualFold(nick, "here") {
				return errf(400, "bad_username", "Имя пользователя: 3–32 латинские буквы, цифры или подчёркивание; everyone и here заняты")
			}
			_, err := tx.Exec(r.Context(), `UPDATE users SET password_salt=COALESCE(NULLIF(password_salt,''),username), username=$2, nickname=$3 WHERE id=$1`, a.UserID, strings.ToLower(nick), nick)
			if err != nil {
				var pe *pgconn.PgError
				if errors.As(err, &pe) && pe.Code == "23505" {
					return errf(409, "username_taken", "Это имя пользователя уже занято")
				}
				return err
			}
		}
		if req.DisplayName != nil {
			if _, err := tx.Exec(r.Context(), `UPDATE users SET display_name=$2 WHERE id=$1`, a.UserID, name); err != nil {
				return err
			}
		}
		if req.Bio != nil {
			if _, err := tx.Exec(r.Context(), `UPDATE users SET bio=$2 WHERE id=$1`, a.UserID, bio); err != nil {
				return err
			}
		}
		if req.NameColor != nil {
			if _, err := tx.Exec(r.Context(), `UPDATE users SET name_color=$2 WHERE id=$1`, a.UserID, nameColor); err != nil {
				return err
			}
		}
		if req.Header != nil {
			if _, err := tx.Exec(r.Context(), `UPDATE users SET header=$2 WHERE id=$1`, a.UserID, header); err != nil {
				return err
			}
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
	writeJSON(w, 200, map[string]any{"ok": true})
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
