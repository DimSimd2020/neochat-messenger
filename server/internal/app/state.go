package app

import (
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// maxStateBlob caps the account state document (contacts, pins, preferences).
const maxStateBlob = 512 << 10

// handleGetState returns the account state: an opaque blob sealed by the client
// with a key derived from the password, so the server cannot read it.
func (s *Server) handleGetState(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var rev int64
	var blob []byte
	err := s.db.QueryRow(r.Context(), `SELECT revision, blob FROM account_state WHERE user_id=$1`, a.UserID).Scan(&rev, &blob)
	if errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, errNotFound)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"revision": strconv.FormatInt(rev, 10), "blob": blob})
}

// handlePutState replaces the state only when the client saw the current revision,
// so concurrent edits from two devices are merged by the client instead of lost.
func (s *Server) handlePutState(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.limits.allow("state:"+a.UserID, 600, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	var req struct {
		Revision string `json:"revision"`
		Blob     []byte `json:"blob"`
	}
	if err := readJSON(w, r, maxStateBlob*2, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(req.Blob) == 0 || len(req.Blob) > maxStateBlob {
		s.fail(w, r, errf(400, "invalid_state", "Некорректное состояние аккаунта"))
		return
	}
	base, err := strconv.ParseInt(req.Revision, 10, 64)
	if err != nil || base < 0 {
		s.fail(w, r, errf(400, "invalid_state", "Некорректная ревизия"))
		return
	}
	var notify []string
	err = s.inTx(r.Context(), func(tx pgx.Tx) error {
		var tag pgconn.CommandTag
		var err error
		if base == 0 {
			tag, err = tx.Exec(r.Context(), `INSERT INTO account_state(user_id, revision, blob) VALUES ($1, 1, $2) ON CONFLICT DO NOTHING`, a.UserID, req.Blob)
		} else {
			tag, err = tx.Exec(r.Context(), `UPDATE account_state SET revision=revision+1, blob=$3, updated_at=now() WHERE user_id=$1 AND revision=$2`, a.UserID, base, req.Blob)
		}
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return errf(409, "state_conflict", "Состояние аккаунта изменилось на другом устройстве")
		}
		rows, err := tx.Query(r.Context(), `SELECT id FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND id<>$2`, a.UserID, a.DeviceID)
		if err != nil {
			return err
		}
		devices, err := pgx.CollectRows(rows, pgx.RowTo[string])
		if err != nil {
			return err
		}
		notify, err = emitToDevices(r.Context(), tx, devices, "state", nil)
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	writeJSON(w, 200, map[string]any{"revision": strconv.FormatInt(base+1, 10)})
}
