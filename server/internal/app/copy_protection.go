package app

import (
	"errors"
	"net/http"

	"github.com/jackc/pgx/v5"
)

type copyRule struct {
	By        string `json:"by"`
	Requested bool   `json:"requested,omitempty"`
}

type copyProtection struct {
	Revision int64               `json:"revision"`
	Rules    map[string]copyRule `json:"rules,omitempty"`
}

func (s *Server) handleCopyProtection(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		Rule     string `json:"rule"`
		Action   string `json:"action"`
		Revision *int64 `json:"revision"`
	}
	if err := readJSON(w, r, 1024, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if req.Revision == nil ||
		(req.Rule != "copy" && req.Rule != "forward" && req.Rule != "screenshot") ||
		(req.Action != "block" && req.Action != "allow" && req.Action != "request") {
		s.fail(w, r, errBadRequest)
		return
	}
	ctx, id := r.Context(), r.PathValue("id")
	var policy copyProtection
	var notify []string
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		var archived bool
		err := tx.QueryRow(ctx, `SELECT c.copy_protection, c.archived FROM conversations c
			JOIN memberships m ON m.conversation_id=c.id AND m.user_id=$2
			WHERE c.id=$1 FOR UPDATE OF c`, nullUUID(id), a.UserID).Scan(&policy, &archived)
		if errors.Is(err, pgx.ErrNoRows) {
			return errNotFound
		}
		if err != nil {
			return err
		}
		// Recheck membership after taking the lock shared with membership changes.
		var member bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM memberships
			WHERE conversation_id=$1 AND user_id=$2)`, id, a.UserID).Scan(&member); err != nil {
			return err
		}
		if !member {
			return errNotFound
		}
		if archived {
			return errf(409, "conversation_archived", "Беседа архивирована")
		}
		if *req.Revision != policy.Revision {
			return errf(409, "copy_protection_changed", "Настройки копирования изменились. Повторите действие")
		}
		if policy.Rules == nil {
			policy.Rules = make(map[string]copyRule)
		}
		rule, blocked := policy.Rules[req.Rule]
		switch req.Action {
		case "block":
			if blocked {
				return nil
			}
			policy.Rules[req.Rule] = copyRule{By: a.UserID}
		case "allow":
			if !blocked {
				return nil
			}
			if rule.By != a.UserID {
				return errf(403, "copy_protection_owner_required", "Разрешить действие может тот, кто его запретил")
			}
			delete(policy.Rules, req.Rule)
		case "request":
			if !blocked || rule.By == a.UserID || rule.Requested {
				return nil
			}
			rule.Requested = true
			policy.Rules[req.Rule] = rule
		}
		policy.Revision++
		if _, err := tx.Exec(ctx, `UPDATE conversations SET copy_protection=$2 WHERE id=$1`, id, policy); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT d.id FROM memberships m JOIN devices d ON d.user_id=m.user_id
			WHERE m.conversation_id=$1 AND d.revoked_at IS NULL`, id)
		if err != nil {
			return err
		}
		devices, err := pgx.CollectRows(rows, pgx.RowTo[string])
		if err != nil {
			return err
		}
		notify, err = emitToDevices(ctx, tx, devices, "copy_protection", map[string]any{
			"conversation_id": id, "copy_protection": policy,
		})
		return err
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.notify(notify)
	writeJSON(w, 200, policy)
}
