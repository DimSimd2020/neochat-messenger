package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"

	"github.com/jackc/pgx/v5"
)

const (
	maxGroupMembers   = 50
	maxChannelMembers = 200
)

// groupSettings are not secret: the server enforces who may post.
type groupSettings struct {
	// MembersCanSend=false leaves posting to the owner and admins.
	MembersCanSend  *bool `json:"members_can_send,omitempty"`
	MembersCanMedia *bool `json:"members_can_media,omitempty"`
	MembersCanPolls *bool `json:"members_can_polls,omitempty"`
	MembersCanInfo  *bool `json:"members_can_info,omitempty"`
}

type querier interface {
	QueryRow(context.Context, string, ...any) pgx.Row
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

type memberOut struct {
	UserID      string `json:"user_id"`
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
	Role        string `json:"role"`
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
	Settings             json.RawMessage  `json:"settings"`
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
	err := q.QueryRow(ctx, `SELECT c.id, c.kind, c.owner_id, c.revision, c.state_blob, c.state_sig, c.leaves, c.archived, c.settings
		FROM conversations c JOIN memberships m ON m.conversation_id=c.id AND m.user_id=$2 WHERE c.id=$1`, nullUUID(id), viewer).
		Scan(&c.ID, &c.Kind, &owner, &rev, &blob, &sig, &leaves, &c.Archived, &c.Settings)
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
	rows, err := q.Query(ctx, `SELECT u.id, COALESCE(NULLIF(u.nickname, ''), u.username), u.display_name, m.role FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.conversation_id=$1 ORDER BY u.username`, c.ID)
	if err != nil {
		return nil, err
	}
	c.Members, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (memberOut, error) {
		var m memberOut
		return m, row.Scan(&m.UserID, &m.Username, &m.DisplayName, &m.Role)
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

func maxMembers(kind string) int {
	if kind == "channel" {
		return maxChannelMembers
	}
	return maxGroupMembers
}

// checkMembers validates a new member list. `actor` adds people, so new members must be
// related to the actor; people already in the group stay without that check.
func (s *Server) checkMembers(r *http.Request, actor, owner, kind string, members []string, existing map[string]bool) ([]string, error) {
	set := map[string]bool{owner: true}
	for _, m := range members {
		if !isUUID(m) {
			return nil, errBadRequest
		}
		set[m] = true
	}
	if len(set) < 2 && kind != "channel" {
		return nil, errf(400, "too_few_members", "В группе должно быть минимум два участника")
	}
	if len(set) > maxMembers(kind) {
		return nil, errf(400, "too_many_members", fmt.Sprintf("Участников может быть не больше %d", maxMembers(kind)))
	}
	out := make([]string, 0, len(set))
	for m := range set {
		if m != actor && !existing[m] {
			ok, err := s.related(r, actor, m)
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
	if err != nil || rev < 1 || len(st.Blob) == 0 || len(st.Blob) > 16384 || len(st.Signature) != signatureLen {
		return 0, errf(400, "bad_request", "Некорректное состояние группы")
	}
	return rev, nil
}

func (s *Server) handleCreateGroup(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		GroupID string      `json:"group_id"`
		Kind    string      `json:"kind"`
		Members []string    `json:"members"`
		Admins  []string    `json:"admins"`
		State   signedState `json:"state"`
	}
	if err := readJSON(w, r, 128<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if req.Kind == "" {
		req.Kind = "group"
	}
	rev, err := validState(&req.State)
	if err != nil || rev != 1 || !isUUID(req.GroupID) || (req.Kind != "group" && req.Kind != "channel") {
		s.fail(w, r, errBadRequest)
		return
	}
	members, err := s.checkMembers(r, a.UserID, a.UserID, req.Kind, req.Members, nil)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	roles := rolesFor(a.UserID, members, req.Admins)
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		_, err := tx.Exec(r.Context(), `INSERT INTO conversations(id, kind, owner_id, revision, state_blob, state_sig) VALUES ($1,$5,$2,1,$3,$4)`,
			req.GroupID, a.UserID, req.State.Blob, req.State.Signature, req.Kind)
		if isUniqueViolation(err) {
			return errf(409, "conversation_exists", "Группа уже существует")
		}
		if err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `INSERT INTO group_states(conversation_id, revision, blob, sig) VALUES ($1,1,$2,$3)`, req.GroupID, req.State.Blob, req.State.Signature); err != nil {
			return err
		}
		for _, m := range members {
			if _, err := tx.Exec(r.Context(), `INSERT INTO memberships(conversation_id, user_id, joined_revision, role) VALUES ($1,$2,1,$3)`, req.GroupID, m, roles[m]); err != nil {
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

// rolesFor gives the owner, admins (who must be members) and everyone else their role.
func rolesFor(owner string, members, admins []string) map[string]string {
	roles := map[string]string{}
	for _, m := range members {
		roles[m] = "member"
	}
	for _, a := range admins {
		if _, ok := roles[a]; ok {
			roles[a] = "admin"
		}
	}
	roles[owner] = "owner"
	return roles
}

type lockedGroup struct {
	owner, kind, role string
	rev               int64
	archived          bool
	settings          groupSettings
}

// lockGroup locks the conversation row; membership changes and sends serialize on it.
func lockGroup(ctx context.Context, tx pgx.Tx, id, user string) (*lockedGroup, error) {
	var ownerP *string
	var raw []byte
	g := &lockedGroup{}
	err := tx.QueryRow(ctx, `SELECT c.kind, c.owner_id, c.revision, c.archived, c.settings, m.role FROM conversations c
		JOIN memberships m ON m.conversation_id=c.id AND m.user_id=$2 WHERE c.id=$1 FOR UPDATE OF c`, nullUUID(id), user).
		Scan(&g.kind, &ownerP, &g.rev, &g.archived, &raw, &g.role)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	if g.kind != "group" && g.kind != "channel" {
		return nil, errf(400, "not_group", "Это не группа")
	}
	g.owner = *ownerP
	_ = json.Unmarshal(raw, &g.settings)
	return g, nil
}

func (g *lockedGroup) isAdmin() bool { return g.role == "owner" || g.role == "admin" }

func (s *Server) handleMembership(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	var req struct {
		PrevRevision string      `json:"prev_revision"`
		Members      []string    `json:"members"`
		Admins       []string    `json:"admins"`
		State        signedState `json:"state"`
	}
	if err := readJSON(w, r, 128<<10, &req); err != nil {
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
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		g, err := lockGroup(r.Context(), tx, id, a.UserID)
		if err != nil {
			return err
		}
		if !g.isAdmin() {
			return errf(403, "admin_required", "Состав меняют владелец и админы")
		}
		if g.archived {
			return errf(409, "conversation_archived", "Группа архивирована")
		}
		if g.rev != prev {
			return errf(409, "conversation_revision_changed", "Состав группы уже изменился")
		}
		rows, err := tx.Query(r.Context(), `SELECT user_id, role FROM memberships WHERE conversation_id=$1`, id)
		if err != nil {
			return err
		}
		type mr struct{ user, role string }
		cur, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (mr, error) {
			var x mr
			return x, row.Scan(&x.user, &x.role)
		})
		if err != nil {
			return err
		}
		old := make([]string, 0, len(cur))
		existing := map[string]bool{}
		oldAdmins := map[string]bool{}
		for _, x := range cur {
			old = append(old, x.user)
			existing[x.user] = true
			if x.role == "admin" {
				oldAdmins[x.user] = true
			}
		}
		members, err := s.checkMembers(r, a.UserID, g.owner, g.kind, req.Members, existing)
		if err != nil {
			return err
		}
		roles := rolesFor(g.owner, members, req.Admins)
		if g.role != "owner" {
			// Admins manage ordinary members only; the admin list belongs to the owner.
			newAdmins := map[string]bool{}
			for u, role := range roles {
				if role == "admin" {
					newAdmins[u] = true
				}
			}
			if len(newAdmins) != len(oldAdmins) {
				return errf(403, "owner_required", "Админов назначает и снимает только владелец")
			}
			for u := range oldAdmins {
				if !newAdmins[u] {
					return errf(403, "owner_required", "Админов назначает и снимает только владелец")
				}
			}
		}
		if _, err := tx.Exec(r.Context(), `DELETE FROM memberships WHERE conversation_id=$1 AND NOT (user_id = ANY($2::uuid[]))`, id, members); err != nil {
			return err
		}
		for _, m := range members {
			if _, err := tx.Exec(r.Context(), `INSERT INTO memberships(conversation_id, user_id, joined_revision, role) VALUES ($1,$2,$3,$4)
				ON CONFLICT (conversation_id, user_id) DO UPDATE SET role=excluded.role`, id, m, rev, roles[m]); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(r.Context(), `UPDATE conversations SET revision=$2, state_blob=$3, state_sig=$4, leaves='[]' WHERE id=$1`,
			id, rev, req.State.Blob, req.State.Signature); err != nil {
			return err
		}
		if _, err := tx.Exec(r.Context(), `INSERT INTO group_states(conversation_id, revision, blob, sig) VALUES ($1,$2,$3,$4)`, id, rev, req.State.Blob, req.State.Signature); err != nil {
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
		g, err := lockGroup(r.Context(), tx, id, a.UserID)
		if err != nil {
			return err
		}
		owner, cur, archived := g.owner, g.rev, g.archived
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

// handleStates returns every signed state of a group so a client can check admin-signed
// changes against the state it trusted before.
func (s *Server) handleStates(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	var member bool
	if err := s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM memberships WHERE conversation_id=$1 AND user_id=$2)`, nullUUID(id), a.UserID).Scan(&member); err != nil {
		s.fail(w, r, err)
		return
	}
	if !member {
		s.fail(w, r, errNotFound)
		return
	}
	rows, err := s.db.Query(r.Context(), `SELECT revision, blob, sig FROM group_states WHERE conversation_id=$1 ORDER BY revision`, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	states, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (signedState, error) {
		var st signedState
		var rev int64
		err := row.Scan(&rev, &st.Blob, &st.Signature)
		st.Revision = strconv.FormatInt(rev, 10)
		return st, err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"states": states})
}

// handleSettings changes the posting rules of a group; only the owner and admins may.
func (s *Server) handleSettings(w http.ResponseWriter, r *http.Request, a *authCtx) {
	id := r.PathValue("id")
	var req groupSettings
	if err := readJSON(w, r, 4<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	raw, _ := json.Marshal(req)
	var notify []string
	err := s.inTx(r.Context(), func(tx pgx.Tx) error {
		g, err := lockGroup(r.Context(), tx, id, a.UserID)
		if err != nil {
			return err
		}
		if !g.isAdmin() {
			return errf(403, "admin_required", "Настройки меняют владелец и админы")
		}
		if _, err := tx.Exec(r.Context(), `UPDATE conversations SET settings=$2 WHERE id=$1`, id, raw); err != nil {
			return err
		}
		rows, err := tx.Query(r.Context(), `SELECT user_id FROM memberships WHERE conversation_id=$1`, id)
		if err != nil {
			return err
		}
		users, err := pgx.CollectRows(rows, pgx.RowTo[string])
		if err != nil {
			return err
		}
		devs, err := memberDevices(r.Context(), tx, users)
		if err != nil {
			return err
		}
		notify, err = emitToDevices(r.Context(), tx, devs, "membership", map[string]string{"conversation_id": id, "revision": strconv.FormatInt(g.rev, 10)})
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
