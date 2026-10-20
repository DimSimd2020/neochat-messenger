package app

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
)

// Presence and typing are live hints over the WebSocket. They are metadata the
// server sees anyway (when a device is connected, when it sends), never stored
// beyond users.last_seen.

const recentlyWindow = 7 * 24 * time.Hour

// presenceAudience: users who may ask for userID's presence — contacts in either
// direction and members of shared direct chats and groups. Channel subscribers
// do not see each other.
const presenceAudience = `
	SELECT owner_id FROM contacts WHERE contact_id=$1
	UNION SELECT contact_id FROM contacts WHERE owner_id=$1
	UNION SELECT m2.user_id FROM memberships m1
		JOIN memberships m2 ON m2.conversation_id=m1.conversation_id
		JOIN conversations c ON c.id=m1.conversation_id
		WHERE m1.user_id=$1 AND c.kind<>'channel'`

// presenceChanged stores the time and tells related online devices to refetch.
func (s *Server) presenceChanged(userID string) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := s.db.Exec(ctx, `UPDATE users SET last_seen=now() WHERE id=$1`, userID); err != nil {
		return
	}
	rows, err := s.db.Query(ctx, `SELECT d.id FROM devices d WHERE d.revoked_at IS NULL AND d.user_id<>$1 AND d.user_id IN (`+presenceAudience+`)`, userID)
	if err != nil {
		return
	}
	devices, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return
	}
	msg, _ := json.Marshal(map[string]string{"type": "presence", "user_id": userID})
	s.hub.send(devices, msg)
}

type presenceOut struct {
	UserID string `json:"user_id"`
	// online | recently | long; "online" and last_seen only when both sides show the time.
	Status   string `json:"status"`
	LastSeen int64  `json:"last_seen,omitempty"`
}

func (s *Server) handlePresence(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		UserIDs []string `json:"user_ids"`
	}
	if err := readJSON(w, r, 16<<10, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(req.UserIDs) > 200 {
		s.fail(w, r, errBadRequest)
		return
	}
	ids := make([]string, 0, len(req.UserIDs))
	for _, id := range req.UserIDs {
		if isUUID(id) {
			ids = append(ids, id)
		}
	}
	var mine bool
	if err := s.db.QueryRow(r.Context(), `SELECT show_last_seen FROM users WHERE id=$1`, a.UserID).Scan(&mine); err != nil {
		s.fail(w, r, err)
		return
	}
	// Only users in the viewer's audience; others are silently left out.
	rows, err := s.db.Query(r.Context(), `SELECT u.id, u.last_seen, u.show_last_seen FROM users u
		WHERE u.id = ANY($2::uuid[]) AND (u.id=$1 OR u.id IN (`+presenceAudience+`))`, a.UserID, ids)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := []presenceOut{}
	for rows.Next() {
		var p presenceOut
		var seen *time.Time
		var show bool
		if err := rows.Scan(&p.UserID, &seen, &show); err != nil {
			rows.Close()
			s.fail(w, r, err)
			return
		}
		online := s.hub.online(p.UserID)
		// Telegram rule: who hides their time does not see others' either.
		exact := p.UserID == a.UserID || (show && mine)
		switch {
		case exact && online:
			p.Status = "online"
		case exact && seen != nil:
			p.Status = "exact"
			p.LastSeen = seen.UnixMilli()
		case online || (seen != nil && time.Since(*seen) < recentlyWindow):
			p.Status = "recently"
		default:
			p.Status = "long"
		}
		out = append(out, p)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"presence": out})
}

// handleTyping relays "user is typing" to the other members' connected devices.
func (s *Server) handleTyping(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.limits.allow("typing:"+a.DeviceID, 40, time.Minute) {
		w.WriteHeader(204)
		return
	}
	cid := r.PathValue("id")
	if !isUUID(cid) {
		s.fail(w, r, errNotFound)
		return
	}
	var kind string
	err := s.db.QueryRow(r.Context(), `SELECT c.kind FROM conversations c JOIN memberships m ON m.conversation_id=c.id
		WHERE c.id=$1 AND m.user_id=$2`, cid, a.UserID).Scan(&kind)
	if err == pgx.ErrNoRows {
		s.fail(w, r, errNotFound)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if kind == "channel" {
		w.WriteHeader(204)
		return
	}
	rows, err := s.db.Query(r.Context(), `SELECT d.id FROM devices d JOIN memberships m ON m.user_id=d.user_id
		WHERE m.conversation_id=$1 AND d.user_id<>$2 AND d.revoked_at IS NULL`, cid, a.UserID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	devices, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		s.fail(w, r, err)
		return
	}
	msg, _ := json.Marshal(map[string]string{"type": "typing", "conversation_id": cid, "user_id": a.UserID})
	s.hub.send(devices, msg)
	w.WriteHeader(204)
}
