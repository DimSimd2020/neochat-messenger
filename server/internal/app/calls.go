package app

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"net/http"
	"strconv"
	"time"
)

// Calls are peer-to-peer WebRTC. Offers and answers travel as ordinary E2EE
// control messages, so the server only hands out TURN credentials. Media is
// DTLS-SRTP between the devices; the TURN relay sees ciphertext only.

const iceTTL = 12 * time.Hour

type iceServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

// turnCredential follows the TURN REST API that coturn checks with
// use-auth-secret: username "<unix expiry>:<device>", password
// base64(HMAC-SHA1(secret, username)). Nothing is stored.
func turnCredential(secret, device string, expiry time.Time) (string, string) {
	user := strconv.FormatInt(expiry.Unix(), 10) + ":" + device
	mac := hmac.New(sha1.New, []byte(secret))
	mac.Write([]byte(user))
	return user, base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

func (s *Server) handleICE(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.limits.allow("ice:"+a.DeviceID, 60, time.Hour) {
		s.fail(w, r, errRateLimited(time.Minute))
		return
	}
	servers := []iceServer{}
	if s.cfg.TurnSecret != "" && len(s.cfg.TurnURIs) > 0 {
		user, pass := turnCredential(s.cfg.TurnSecret, a.DeviceID, time.Now().Add(iceTTL))
		servers = append(servers, iceServer{URLs: s.cfg.TurnURIs, Username: user, Credential: pass})
	}
	writeJSON(w, 200, map[string]any{"ice_servers": servers, "ttl": int(iceTTL.Seconds())})
}
