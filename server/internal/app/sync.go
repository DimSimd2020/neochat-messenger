package app

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/jackc/pgx/v5"
)

const (
	syncMaxEvents = 100
	syncMaxBytes  = 1 << 20
)

// appendEvent adds one event to a device feed. The head is incremented inside the
// caller's transaction, so numbers become visible only on commit and never leave gaps
// that a concurrent sync could skip. Callers lock devices in sorted id order.
func appendEvent(ctx context.Context, tx pgx.Tx, deviceID, kind string, messageID *string, payload any) (bool, error) {
	var seq int64
	err := tx.QueryRow(ctx, `UPDATE devices SET event_head=event_head+1 WHERE id=$1 AND revoked_at IS NULL RETURNING event_head`, deviceID).Scan(&seq)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	var p []byte
	if payload != nil {
		if p, err = json.Marshal(payload); err != nil {
			return false, err
		}
	}
	_, err = tx.Exec(ctx, `INSERT INTO device_events(device_id, seq, event_id, kind, message_id, payload) VALUES ($1,$2,$3,$4,$5,$6)`,
		deviceID, seq, newUUID(), kind, messageID, p)
	return err == nil, err
}

// emitToDevices appends the same event to several feeds in a deadlock-free order.
func emitToDevices(ctx context.Context, tx pgx.Tx, devices []string, kind string, payload any) ([]string, error) {
	sort.Strings(devices)
	var out []string
	for i, d := range devices {
		if i > 0 && devices[i-1] == d {
			continue
		}
		ok, err := appendEvent(ctx, tx, d, kind, nil, payload)
		if err != nil {
			return nil, err
		}
		if ok {
			out = append(out, d)
		}
	}
	return out, nil
}

// emitToRelated notifies every active device of users who share a conversation or
// a contact relation with userID, plus userID's own active devices.
func (s *Server) emitToRelated(ctx context.Context, tx pgx.Tx, userID, kind string, payload any) ([]string, error) {
	rows, err := tx.Query(ctx, `SELECT d.id FROM devices d WHERE d.revoked_at IS NULL AND d.user_id IN (
			SELECT $1::uuid
			UNION SELECT owner_id FROM contacts WHERE contact_id=$1
			UNION SELECT contact_id FROM contacts WHERE owner_id=$1
			UNION SELECT m2.user_id FROM memberships m1 JOIN memberships m2 ON m2.conversation_id=m1.conversation_id WHERE m1.user_id=$1)`, userID)
	if err != nil {
		return nil, err
	}
	devices, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return nil, err
	}
	return emitToDevices(ctx, tx, devices, kind, payload)
}

type eventOut struct {
	EventID         string          `json:"event_id"`
	Seq             string          `json:"seq"`
	Kind            string          `json:"kind"`
	CreatedAt       time.Time       `json:"created_at"`
	ConversationID  string          `json:"conversation_id,omitempty"`
	MessageID       string          `json:"message_id,omitempty"`
	SenderUserID    string          `json:"sender_user_id,omitempty"`
	SenderDeviceID  string          `json:"sender_device_id,omitempty"`
	ConversationSeq string          `json:"conversation_seq,omitempty"`
	Ciphertext      []byte          `json:"ciphertext,omitempty"`
	AttachmentIDs   []string        `json:"attachment_ids,omitempty"`
	Payload         json.RawMessage `json:"payload,omitempty"`
}

func (s *Server) handleSync(w http.ResponseWriter, r *http.Request, a *authCtx) {
	inc, err := s.incarnation(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var after int64
	if c := r.URL.Query().Get("cursor"); c != "" {
		ci, cs, ok := strings.Cut(c, ".")
		if !ok {
			s.fail(w, r, errf(400, "bad_cursor", "Некорректный курсор"))
			return
		}
		if ci != inc {
			s.fail(w, r, errf(409, "sync_reset_required", "Сервер восстановлен из резервной копии, требуется повторная синхронизация"))
			return
		}
		if after, err = strconv.ParseInt(cs, 10, 64); err != nil || after < 0 {
			s.fail(w, r, errf(400, "bad_cursor", "Некорректный курсор"))
			return
		}
	}
	limit := syncMaxEvents
	if l, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && l > 0 && l < limit {
		limit = l
	}

	var head int64
	var minSeq *int64
	if err := s.db.QueryRow(r.Context(), `SELECT event_head, (SELECT min(seq) FROM device_events WHERE device_id=$1) FROM devices WHERE id=$1`, a.DeviceID).Scan(&head, &minSeq); err != nil {
		s.fail(w, r, err)
		return
	}
	if after < head && (minSeq == nil || *minSeq > after+1) {
		s.fail(w, r, errf(410, "cursor_expired", "История на сервере истекла"))
		return
	}

	rows, err := s.db.Query(r.Context(), `SELECT e.event_id, e.seq, e.kind, e.created_at, e.payload,
			m.id, m.conversation_id, m.sender_device_id, sd.user_id, m.conversation_seq, env.ciphertext,
			COALESCE((SELECT array_agg(at.id::text) FROM attachments at WHERE at.message_id=m.id), '{}')
		FROM device_events e
		LEFT JOIN messages m ON m.id=e.message_id
		LEFT JOIN devices sd ON sd.id=m.sender_device_id
		LEFT JOIN envelopes env ON env.message_id=e.message_id AND env.device_id=e.device_id
		WHERE e.device_id=$1 AND e.seq>$2 ORDER BY e.seq LIMIT $3`, a.DeviceID, after, limit+1)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	defer rows.Close()
	events := []eventOut{}
	size := 0
	hasMore := false
	last := after
	for rows.Next() {
		var e eventOut
		var seq int64
		var payload []byte
		var mid, cid, sdev, suser *string
		var cseq *int64
		var ct []byte
		var atts []string
		if err := rows.Scan(&e.EventID, &seq, &e.Kind, &e.CreatedAt, &payload, &mid, &cid, &sdev, &suser, &cseq, &ct, &atts); err != nil {
			s.fail(w, r, err)
			return
		}
		size += len(ct)*4/3 + len(payload) + 256
		if len(events) == limit || (len(events) > 0 && size > syncMaxBytes) {
			hasMore = true
			break
		}
		e.Seq = strconv.FormatInt(seq, 10)
		if payload != nil {
			e.Payload = payload
		}
		if mid != nil {
			e.MessageID, e.ConversationID, e.SenderDeviceID, e.SenderUserID = *mid, *cid, *sdev, *suser
			e.ConversationSeq = strconv.FormatInt(*cseq, 10)
			e.Ciphertext = ct
			e.AttachmentIDs = atts
		}
		events = append(events, e)
		last = seq
	}
	if err := rows.Err(); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{
		"events":      events,
		"next_cursor": inc + "." + strconv.FormatInt(last, 10),
		"has_more":    hasMore,
	})
}

// ---- WebSocket hints ----

type hub struct {
	mu    sync.Mutex
	conns map[string]map[*wsConn]struct{}
}

type wsConn struct {
	ch     chan struct{}
	cancel context.CancelFunc
}

func newHub() *hub { return &hub{conns: map[string]map[*wsConn]struct{}{}} }

func (h *hub) add(device string, c *wsConn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.conns[device] == nil {
		h.conns[device] = map[*wsConn]struct{}{}
	}
	h.conns[device][c] = struct{}{}
}

func (h *hub) remove(device string, c *wsConn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.conns[device], c)
	if len(h.conns[device]) == 0 {
		delete(h.conns, device)
	}
}

// notify is called only after commit. A slow connection whose queue is full is
// closed; the client will reconnect and sync instead of buffering unbounded hints.
func (h *hub) notify(devices []string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, d := range devices {
		for c := range h.conns[d] {
			select {
			case c.ch <- struct{}{}:
			default:
				c.cancel()
			}
		}
	}
}

func (h *hub) disconnect(device string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.conns[device] {
		c.cancel()
	}
}

func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request, a *authCtx) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		return
	}
	defer conn.CloseNow()
	conn.SetReadLimit(1024)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	c := &wsConn{ch: make(chan struct{}, 8), cancel: cancel}
	s.hub.add(a.DeviceID, c)
	defer s.hub.remove(a.DeviceID, c)
	c.ch <- struct{}{}

	// Clients never send application data; reading detects close frames.
	go func() {
		for {
			if _, _, err := conn.Read(ctx); err != nil {
				cancel()
				return
			}
		}
	}()
	ping := time.NewTicker(30 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			conn.Close(websocket.StatusGoingAway, "")
			return
		case <-ping.C:
			pctx, pc := context.WithTimeout(ctx, 10*time.Second)
			err := conn.Ping(pctx)
			pc()
			if err != nil {
				return
			}
		case <-c.ch:
			var head int64
			var revoked *time.Time
			if err := s.db.QueryRow(ctx, `SELECT event_head, revoked_at FROM devices WHERE id=$1`, a.DeviceID).Scan(&head, &revoked); err != nil || revoked != nil {
				return
			}
			msg, _ := json.Marshal(map[string]string{"type": "sync.required", "head": strconv.FormatInt(head, 10)})
			wctx, wc := context.WithTimeout(ctx, 10*time.Second)
			err := conn.Write(wctx, websocket.MessageText, msg)
			wc()
			if err != nil {
				return
			}
		}
	}
}
