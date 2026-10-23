package app

import (
	"encoding/hex"
	"errors"
	"net/http"

	"github.com/jackc/pgx/v5"
)

func validInstallationID(id string) bool {
	decoded, err := hex.DecodeString(id)
	return err == nil && len(decoded) == 32
}

// Existing authenticated devices register the hint before a later reinstall.
// Knowing this ID alone never authorizes login, replacement or key recovery.
func (s *Server) handleDeviceInstallation(w http.ResponseWriter, r *http.Request, a *authCtx) {
	var req struct {
		ID string `json:"installation_id"`
	}
	if err := readJSON(w, r, 1024, &req); err != nil {
		s.fail(w, r, err)
		return
	}
	if !validInstallationID(req.ID) {
		s.fail(w, r, errBadRequest)
		return
	}
	_, err := s.db.Exec(r.Context(), `UPDATE devices SET installation_id=$3 WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL`, a.DeviceID, a.UserID, req.ID)
	if isUniqueViolation(err) {
		err = errf(409, "installation_exists", "Это устройство уже связано с другой активной сессией")
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type installationLink struct {
	ID       string `json:"installation_id,omitempty"`
	Platform string `json:"platform,omitempty"`
	Replaces string `json:"replace_device_id,omitempty"`
}

func replaceInstallation(r *http.Request, tx pgx.Tx, userID string, d *deviceIn, previous installationLink) error {
	if previous.ID != d.InstallationID || previous.Platform != "" && previous.Platform != d.Platform {
		return errBadRequest
	}
	if previous.Replaces == "" {
		return nil
	}
	// Serialize with every roster/device change. Revocation and adding the new
	// keys commit together, so a failed finish never removes the old device.
	if _, err := tx.Exec(r.Context(), `SELECT 1 FROM users WHERE id=$1 FOR UPDATE`, userID); err != nil {
		return err
	}
	err := tx.QueryRow(r.Context(), `UPDATE devices SET revoked_at=now() WHERE id=$1 AND user_id=$2 AND installation_id=$3 AND platform=$4 AND revoked_at IS NULL RETURNING is_primary`, previous.Replaces, userID, d.InstallationID, d.Platform).Scan(&d.replacesPrimary)
	if errors.Is(err, pgx.ErrNoRows) {
		return errf(409, "device_changed", "Устройство уже заменено. Повторите вход")
	}
	if err != nil {
		return err
	}
	if _, err := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE device_id=$1 AND revoked_at IS NULL`, previous.Replaces); err != nil {
		return err
	}
	if _, err := tx.Exec(r.Context(), `DELETE FROM push_tokens WHERE device_id=$1`, previous.Replaces); err != nil {
		return err
	}
	_, err = tx.Exec(r.Context(), `DELETE FROM one_time_prekeys WHERE device_id=$1`, previous.Replaces)
	return err
}
