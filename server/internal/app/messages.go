package app

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	// Fits a MAX_GROUP×maxDevices fan-out at maxEnvelopeSize (base64 JSON),
	// under nginx client_max_body_size 52m. Typical text is far smaller.
	maxSendBody = 24 << 20
	// A long Cyrillic text or a photo thumbnail with padding fits comfortably.
	maxEnvelopeSize = 32 << 10
	maxRecipients   = maxChannelMembers*maxDevices - 1
)

// sendReq matches docs/protocol/send.schema.json.
type sendReq struct {
	Version              int    `json:"version"`
	ClientMessageID      string `json:"client_message_id"`
	ConversationRevision string `json:"conversation_revision"`
	RecipientSetRevision string `json:"recipient_set_revision"`
	Recipients           []struct {
		TargetDeviceID string `json:"target_device_id"`
		Ciphertext     []byte `json:"ciphertext"`
	} `json:"recipients"`
	AttachmentIDs []string `json:"attachment_ids"`
	// Control marks receipts and poll votes: members may send them where only admins
	// post. Content is end-to-end encrypted, so clients also drop content from members
	// who may not post.
	Control bool `json:"control,omitempty"`
}

type sendAck struct {
	MessageID       string    `json:"message_id"`
	ClientMessageID string    `json:"client_message_id"`
	ConversationSeq string    `json:"conversation_seq"`
	AcceptedAt      time.Time `json:"accepted_at"`
}

// fingerprint is a canonical digest of the request, independent of JSON field order,
// so a retry of the same stored request matches and a different body conflicts.
func (q *sendReq) fingerprint() []byte {
	type rc struct {
		dev string
		h   [32]byte
	}
	list := make([]rc, len(q.Recipients))
	for i, r := range q.Recipients {
		list[i] = rc{r.TargetDeviceID, sha256.Sum256(r.Ciphertext)}
	}
	sort.Slice(list, func(i, j int) bool { return list[i].dev < list[j].dev })
	h := sha256.New()
	put := func(s string) {
		var n [4]byte
		binary.BigEndian.PutUint32(n[:], uint32(len(s)))
		h.Write(n[:])
		h.Write([]byte(s))
	}
	put("NeoChat-send-v1")
	put(q.ClientMessageID)
	put(q.ConversationRevision)
	put(q.RecipientSetRevision)
	for _, r := range list {
		put(r.dev)
		h.Write(r.h[:])
	}
	for _, a := range q.AttachmentIDs {
		put(a)
	}
	return h.Sum(nil)
}

func (s *Server) handleSend(w http.ResponseWriter, r *http.Request, a *authCtx) {
	convID := r.PathValue("id")
	if !s.limits.allow("send:"+a.DeviceID, 600, time.Minute) {
		s.fail(w, r, errRateLimited(5*time.Second))
		return
	}
	var req sendReq
	if err := readJSON(w, r, maxSendBody, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	rev, err := strconv.ParseInt(req.ConversationRevision, 10, 64)
	if req.Version != 1 || !isUUID(req.ClientMessageID) || err != nil ||
		len(req.Recipients) > maxRecipients || len(req.AttachmentIDs) > 1 {
		s.fail(w, r, errBadRequest)
		return
	}
	seen := map[string]bool{}
	for _, rc := range req.Recipients {
		if !isUUID(rc.TargetDeviceID) || seen[rc.TargetDeviceID] || len(rc.Ciphertext) < 2 || len(rc.Ciphertext) > maxEnvelopeSize {
			s.fail(w, r, errf(400, "bad_envelope", "Некорректный конверт"))
			return
		}
		seen[rc.TargetDeviceID] = true
	}
	for _, id := range req.AttachmentIDs {
		if !isUUID(id) {
			s.fail(w, r, errBadRequest)
			return
		}
	}
	fp := req.fingerprint()

	var ack sendAck
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		notify = nil
		var cur int64
		var archived bool
		var kind, role string
		var rawSettings []byte
		var muted bool
		err := tx.QueryRow(r.Context(), `SELECT c.revision, c.archived, c.kind, c.settings, m.role, COALESCE(m.muted_until > now(), false) FROM conversations c
			JOIN memberships m ON m.conversation_id=c.id AND m.user_id=$2 WHERE c.id=$1 FOR UPDATE OF c`, nullUUID(convID), a.UserID).Scan(&cur, &archived, &kind, &rawSettings, &role, &muted)
		if errors.Is(err, pgx.ErrNoRows) {
			return errNotFound
		}
		if err != nil {
			return err
		}
		if !req.Control && muted {
			return errf(403, "muted", "Вам запрещено писать в этой группе")
		}
		if !req.Control && role == "member" {
			var st groupSettings
			_ = json.Unmarshal(rawSettings, &st)
			if kind == "channel" {
				return errf(403, "post_forbidden", "Публикуют в канале только владелец и админы")
			}
			if st.MembersCanSend != nil && !*st.MembersCanSend {
				return errf(403, "post_forbidden", "Писать в группу могут только админы")
			}
		}
		// Idempotency is checked first: a retry after a membership change still gets its ACK.
		var storedFP []byte
		var storedConv string
		var cseq int64
		err = tx.QueryRow(r.Context(), `SELECT id, conversation_id, fingerprint, conversation_seq, accepted_at FROM messages WHERE sender_device_id=$1 AND client_message_id=$2`,
			a.DeviceID, req.ClientMessageID).Scan(&ack.MessageID, &storedConv, &storedFP, &cseq, &ack.AcceptedAt)
		if err == nil {
			if storedConv != convID || string(storedFP) != string(fp) {
				return errf(409, "idempotency_conflict", "Это сообщение уже отправлено с другим содержимым")
			}
			ack.ClientMessageID = req.ClientMessageID
			ack.ConversationSeq = strconv.FormatInt(cseq, 10)
			return nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		if archived {
			return errf(409, "conversation_archived", "Группа архивирована")
		}
		if cur != rev {
			return &apiError{Status: 409, Code: "conversation_revision_changed", Message: "Обновляем состав группы",
				Details: map[string]string{"revision": strconv.FormatInt(cur, 10)}}
		}
		expected, hash, err := recipientSet(r.Context(), tx, convID, a.DeviceID)
		if err != nil {
			return err
		}
		if len(expected) == 0 {
			return errf(409, "no_recipients", "В беседе нет получателей")
		}
		match := hash == req.RecipientSetRevision && len(expected) == len(req.Recipients)
		for _, e := range expected {
			if !seen[e.DeviceID] {
				match = false
			}
		}
		if !match {
			return &apiError{Status: 409, Code: "recipient_set_changed", Message: "Список устройств получателей изменился",
				Details: map[string]any{"recipients": expected, "recipient_set_revision": hash}}
		}
		if err := tx.QueryRow(r.Context(), `UPDATE conversations SET seq=seq+1 WHERE id=$1 RETURNING seq`, convID).Scan(&cseq); err != nil {
			return err
		}
		ack = sendAck{MessageID: newUUID(), ClientMessageID: req.ClientMessageID, ConversationSeq: strconv.FormatInt(cseq, 10), AcceptedAt: time.Now().UTC().Truncate(time.Millisecond)}
		if _, err := tx.Exec(r.Context(), `INSERT INTO messages(id, conversation_id, sender_device_id, client_message_id, fingerprint, conversation_seq, accepted_at)
			VALUES ($1,$2,$3,$4,$5,$6,$7)`, ack.MessageID, convID, a.DeviceID, req.ClientMessageID, fp, cseq, ack.AcceptedAt); err != nil {
			return err
		}
		for _, id := range req.AttachmentIDs {
			tag, err := tx.Exec(r.Context(), `UPDATE attachments SET state='attached', message_id=$2, expires_at=now() + interval '30 days'
				WHERE id=$1 AND owner_device_id=$3 AND state='ready' AND expires_at>now()`, id, ack.MessageID, a.DeviceID)
			if err != nil {
				return err
			}
			if tag.RowsAffected() == 0 {
				return errf(409, "attachment_not_ready", "Файл не загружен или срок его хранения истёк")
			}
		}
		devices := make([]string, 0, len(req.Recipients))
		cts := map[string][]byte{}
		for _, rc := range req.Recipients {
			devices = append(devices, rc.TargetDeviceID)
			cts[rc.TargetDeviceID] = rc.Ciphertext
		}
		sort.Strings(devices)
		for _, d := range devices {
			if _, err := tx.Exec(r.Context(), `INSERT INTO envelopes(message_id, device_id, ciphertext) VALUES ($1,$2,$3)`, ack.MessageID, d, cts[d]); err != nil {
				return err
			}
			ok, err := appendEvent(r.Context(), tx, d, "message", &ack.MessageID, nil)
			if err != nil {
				return err
			}
			if ok {
				notify = append(notify, d)
			}
		}
		return nil
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	writeJSON(w, 200, ack)
}
