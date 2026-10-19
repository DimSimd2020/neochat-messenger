package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strconv"

	"github.com/jackc/pgx/v5"
)

const maxGroupMembers = 16

type querier interface {
	QueryRow(context.Context, string, ...any) pgx.Row
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

type memberOut struct {
	UserID      string `json:"user_id"`
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
}

type recipientOut struct {
	UserID   string `json:"user_id"`
	DeviceID string `json:"device_id"`
}

type signedState struct {
	Revision  string `json:"revision"`
	Blob      []byte `json:"blob"`
	Signature []byte `json:"signature"`
}

type leaveStatement struct {
	UserID    string `json:"user_id"`
	Blob      []byte `json:"blob"`
	Signature []byte `json:"signature"`
}

type conversationOut struct {
	ID                   string           `json:"id"`
	Kind                 string           `json:"kind"`
	OwnerID              string           `json:"owner_id,omitempty"`
	Revision             string           `json:"revision"`
	State                *signedState     `json:"state,omitempty"`
	Leaves               []leaveStatement `json:"leaves"`
	Archived             bool             `json:"archived"`
	Members              []memberOut      `json:"members"`
	Recipients           []recipientOut   `json:"recipients"`
	RecipientSetRevision string           `json:"recipient_set_revision"`
}

// recipientSet returns active devices of all members except the sending device,
// and the SHA-256 of their canonical sorted "user_id/device_id" lines. The hash only
// detects races; clients still verify rosters and the owner-signed member list.
func recipientSet(ctx context.Context, q querier, convID, excludeDevice string) ([]recipientOut, string, error) {
	rows, err := q.Query(ctx, `SELECT d.user_id, d.id FROM memberships m JOIN devices d ON d.user_id=m.user_id
		WHERE m.conversation_id=$1 AND d.revoked_at IS NULL AND d.id<>$2 ORDER BY d.user_id, d.id`, convID, excludeDevice)
	if err != nil {
		return nil, "", err
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (recipientOut, error) {
		var r recipientOut
		return r, row.Scan(&r.UserID, &r.DeviceID)
	})
	if err != nil {
		return nil, "", err
	}
	return list, recipientHash(list), nil
}

func recipientHash(list []recipientOut) string {
	lines := make([]string, len(list))
	for i, r := range list {
		lines[i] = r.UserID + "/" + r.DeviceID + "\n"
	}
	sort.Strings(lines)
	h := sha256.New()
	for _, l := range lines {
		h.Write([]byte(l))
	}
	return hex.EncodeToString(h.Sum(nil))
}

func (s *Server) loadConversation(ctx context.Context, q querier, id, viewer, viewerDevice string) (*conversationOut, error) {
	c := &conversationOut{}
	var owner *string
	var rev int64
	var blob, sig, leaves []byte
	err := q.QueryRow(ctx, `SELECT c.id, c.kind, c.owner_id, c.revision, c.state_blob, c.state_sig, c.leaves, c.archived
		FROM conversations c JOIN memberships m ON m.conversation_id=c.id AND m.user_id=$2 WHERE c.id=$1`, nullUUID(id), viewer).
		Scan(&c.ID, &c.Kind, &owner, &rev, &blob, &sig, &leaves, &c.Archived)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	if owner != nil {
		c.OwnerID = *owner
	}
	c.Revision = strconv.FormatInt(rev, 10)
	if blob != nil {
		c.State = &signedState{Blob: blob, Signature: sig}
	}
	if err := json.Unmarshal(leaves, &c.Leaves); err != nil {
		return nil, err
	}
	rows, err := q.Query(ctx, `SELECT u.id, COALESCE(NULLIF(u.nickname, ''), u.username), u.display_name FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.conversation_id=$1 ORDER BY u.username`, c.ID)
	if err != nil {
		return nil, err
	}
	c.Members, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (memberOut, error) {
		var m memberOut
		return m, row.Scan(&m.UserID, &m.Username, &m.DisplayName)
	})
	if err != nil {
		return nil, err
	}
	c.Recipients, c.RecipientSetRevision, err = recipientSet(ctx, q, c.ID, viewerDevice)
	return c, err
}

func (s *Server) handleListConversations(w http.ResponseWriter, r *http.Request, a *authCtx) {
	rows, err := s.db.Query(r.Context(), `SELECT conversation_id FROM memberships WHERE user_id=$1`, a.UserID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := []*conversationOut{}
	for _, id := range ids {
		c, err := s.loadConversation(r.Context(), s.db, id, a.UserID, a.DeviceID)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		out = append(out, c)
	}
	writeJSON(w, 200, map[string]any{"conversations": out})
}

func (s *Server) handleGetConversation(w http.ResponseWriter, r *http.Request, a *authCtx) {
	c, err := s.loadConversation(r.Context(), s.db, r.PathValue("id"), a.UserID, a.DeviceID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, c)
}

func (s *Server) handleDirect(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		UserID string `json:"user_id"`
	}
	if err := readJSON(w, r, 1<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if req.UserID == a.UserID {
		s.fail(w, r, errf(400, "bad_request", "Нельзя создать диалог с собой"))
		return
	}
	ok, err := s.related(r, a.UserID, req.UserID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !ok {
		s.fail(w, r, errNotFound)
		return
	}
	ua, ub := a.UserID, req.UserID
	if ub < ua {
		ua, ub = ub, ua
	}
	var convID string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		err := tx.QueryRow(r.Context(), `SELECT conversation_id FROM direct_pairs WHERE user_a=$1 AND user_b=$2`, ua, ub).Scan(&convID)
		if err == nil {
			return nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		convID = newUUID()
		if _, err := tx.Exec(r.Context(), `INSERT INTO conversations(id, kind, revision) VALUES ($1,'direct',1)`, convID); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `INSERT INTO memberships(conversation_id, user_id, joined_revision) VALUES ($1,$2,1),($1,$3,1)`, convID, ua, ub); err != nil {
			return err
		}
		_, err = tx.Exec(r.Context(), `INSERT INTO direct_pairs(user_a, user_b, conversation_id) VALUES ($1,$2,$3)`, ua, ub, convID)
		if isUniqueViolation(err) {
			// A concurrent request created the pair; retry reads it.
			return &pgconnRetry{}
		}
		return err
	})
	var retry *pgconnRetry
	if errors.As(err, &retry) {
		err = s.db.QueryRow(r.Context(), `SELECT conversation_id FROM direct_pairs WHERE user_a=$1 AND user_b=$2`, ua, ub).Scan(&convID)
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	c, err := s.loadConversation(r.Context(), s.db, convID, a.UserID, a.DeviceID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, c)
}

type pgconnRetry struct{}

func (*pgconnRetry) Error() string { return "retry" }

func (s *Server) checkMembers(r *http.Request, owner string, members []string) ([]string, error) {
	set := map[string]bool{owner: true}
	for _, m := range members {
		if !isUUID(m) {
			return nil, errBadRequest
		}
		set[m] = true
	}
	if len(set) < 2 {
		return nil, errf(400, "too_few_members", "В группе должно быть минимум два участника")
	}
	if len(set) > maxGroupMembers {
		return nil, errf(400, "too_many_members", "В группе может быть не больше 16 участников")
	}
	out := make([]string, 0, len(set))
	for m := range set {
		if m != owner {
			ok, err := s.related(r, owner, m)
			if err != nil {
				return nil, err
			}
			if !ok {
				return nil, errNotFound
			}
		}
		out = append(out, m)
	}
	sort.Strings(out)
	return out, nil
}

func memberDevices(ctx context.Context, tx pgx.Tx, users []string) ([]string, error) {
	rows, err := tx.Query(ctx, `SELECT id FROM devices WHERE user_id = ANY($1::uuid[]) AND revoked_at IS NULL`, users)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[string])
}

func validState(st *signedState) (int64, error) {
	rev, err := strconv.ParseInt(st.Revision, 10, 64)
	if err != nil || rev < 1 || len(st.Blob) == 0 || len(st.Blob) > 4096 || len(st.Signature) != signatureLen {
		return 0, errf(400, "bad_request", "Некорректное состояние группы")
	}
	return rev, nil
}

func (s *Server) handleCreateGroup(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		GroupID string      `json:"group_id"`
		Members []string    `json:"members"`
		State   signedState `json:"state"`
	}
	if err := readJSON(w, r, 32<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	rev, err := validState(&req.State)
	if err != nil || rev != 1 || !isUUID(req.GroupID) {
		s.fail(w, r, errBadRequest)
		return
	}
	members, err := s.checkMembers(r, a.UserID, req.Members)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		_, err := tx.Exec(r.Context(), `INSERT INTO conversations(id, kind, owner_id, revision, state_blob, state_sig) VALUES ($1,'group',$2,1,$3,$4)`,
			req.GroupID, a.UserID, req.State.Blob, req.State.Signature)
		if isUniqueViolation(err) {
			return errf(409, "conversation_exists", "Группа уже существует")
		}
		if err != nil {
			return err
		}
		for _, m := range members {
			if _, err := tx.Exec(r.Context(), `INSERT INTO memberships(conversation_id, user_id, joined_revision) VALUES ($1,$2,1)`, req.GroupID, m); err != nil {
				return err
			}
		}
		devs, err := memberDevices(r.Context(), tx, members)
		if err != nil {
			return err
		}
		notify, err = emitToDevices(r.Context(), tx, devs, "membership", map[string]string{"conversation_id": req.GroupID, "revision": "1"})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	c, err := s.loadConversation(r.Context(), s.db, req.GroupID, a.UserID, a.DeviceID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 201, c)
}

// lockGroup locks the conversation row; membership changes and sends serialize on it.
func lockGroup(ctx context.Context, tx pgx.Tx, id, user string) (owner string, rev int64, archived bool, err error) {
	var ownerP *string
	var kind string
	err = tx.QueryRow(ctx, `SELECT c.kind, c.owner_id, c.revision, c.archived FROM conversations c
		JOIN memberships m ON m.conversation_id=c.id AND m.user_id=$2 WHERE c.id=$1 FOR UPDATE OF c`, nullUUID(id), user).
		Scan(&kind, &ownerP, &rev, &archived)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", 0, false, errNotFound
	}
	if err != nil {
		return "", 0, false, err
	}
	if kind != "group" {
		return "", 0, false, errf(400, "not_group", "Это не группа")
	}
	return *ownerP, rev, archived, nil
}

func (s *Server) handleMembership(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	var req struct {
		PrevRevision string      `json:"prev_revision"`
		Members      []string    `json:"members"`
		State        signedState `json:"state"`
	}
	if err := readJSON(w, r, 32<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	rev, err := validState(&req.State)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	prev, err := strconv.ParseInt(req.PrevRevision, 10, 64)
	if err != nil || rev != prev+1 {
		s.fail(w, r, errBadRequest)
		return
	}
	members, err := s.checkMembers(r, a.UserID, req.Members)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		owner, cur, archived, err := lockGroup(r.Context(), tx, id, a.UserID)
		if err != nil {
			return err
		}
		if owner != a.UserID {
			return errf(403, "owner_required", "Состав группы меняет только владелец")
		}
		if archived {
			return errf(409, "conversation_archived", "Группа архивирована")
		}
		if cur != prev {
			return errf(409, "conversation_revision_changed", "Состав группы уже изменился")
		}
		rows, err := tx.Query(r.Context(), `SELECT user_id FROM memberships WHERE conversation_id=$1`, id)
		if err != nil {
			return err
		}
		old, err := pgx.CollectRows(rows, pgx.RowTo[string])
		if err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `DELETE FROM memberships WHERE conversation_id=$1 AND NOT (user_id = ANY($2::uuid[]))`, id, members); err != nil {
			return err
		}
		for _, m := range members {
			if _, err := tx.Exec(r.Context(), `INSERT INTO memberships(conversation_id, user_id, joined_revision) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, id, m, rev); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(r.Context(), `UPDATE conversations SET revision=$2, state_blob=$3, state_sig=$4, leaves='[]' WHERE id=$1`,
			id, rev, req.State.Blob, req.State.Signature); err != nil {
			return err
		}
		devs, err := memberDevices(r.Context(), tx, append(old, members...))
		if err != nil {
			return err
		}
		notify, err = emitToDevices(r.Context(), tx, devs, "membership", map[string]string{"conversation_id": id, "revision": strconv.FormatInt(rev, 10)})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	c, err := s.loadConversation(r.Context(), s.db, id, a.UserID, a.DeviceID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, c)
}

// handleLeave removes only the caller. The owner leaving archives the group for new sends.
func (s *Server) handleLeave(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	var req struct {
		PrevRevision string `json:"prev_revision"`
		Blob         []byte `json:"blob"`
		Signature    []byte `json:"signature"`
	}
	if err := readJSON(w, r, 8<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	prev, err := strconv.ParseInt(req.PrevRevision, 10, 64)
	if err != nil || len(req.Blob) == 0 || len(req.Blob) > 1024 || len(req.Signature) != signatureLen {
		s.fail(w, r, errBadRequest)
		return
	}
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		owner, cur, archived, err := lockGroup(r.Context(), tx, id, a.UserID)
		if err != nil {
			return err
		}
		if cur != prev {
			return errf(409, "conversation_revision_changed", "Состав группы уже изменился")
		}
		devs, err := func() ([]string, error) {
			rows, err := tx.Query(r.Context(), `SELECT user_id FROM memberships WHERE conversation_id=$1`, id)
			if err != nil {
				return nil, err
			}
			users, err := pgx.CollectRows(rows, pgx.RowTo[string])
			if err != nil {
				return nil, err
			}
			return memberDevices(r.Context(), tx, users)
		}()
		if err != nil {
			return err
		}
		stmt, _ := json.Marshal(leaveStatement{UserID: a.UserID, Blob: req.Blob, Signature: req.Signature})
		if owner == a.UserID {
			if archived {
				return errf(409, "conversation_archived", "Группа уже архивирована")
			}
			_, err = tx.Exec(r.Context(), `UPDATE conversations SET archived=true, revision=revision+1, leaves=leaves || jsonb_build_array($2::jsonb) WHERE id=$1`, id, stmt)
		} else {
			if _, err = tx.Exec(r.Context(), `DELETE FROM memberships WHERE conversation_id=$1 AND user_id=$2`, id, a.UserID); err != nil {
				return err
			}
			_, err = tx.Exec(r.Context(), `UPDATE conversations SET revision=revision+1, leaves=leaves || jsonb_build_array($2::jsonb) WHERE id=$1`, id, stmt)
		}
		if err != nil {
			return err
		}
		notify, err = emitToDevices(r.Context(), tx, devs, "membership", map[string]string{"conversation_id": id, "revision": strconv.FormatInt(cur+1, 10)})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	w.WriteHeader(204)
}
