package app

import (
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
)

// App updates are published by infra/publish-update.py into DataDir/updates. The manifest
// is signed offline; clients verify the signature with a key built into the core, so the
// server only stores and serves bytes it cannot forge.

var updateFileRe = regexp.MustCompile(`^NeoChat-[0-9A-Za-z.\-]+\.(msi|apk|ipa)$`)

func (s *Server) handleUpdateManifest(w http.ResponseWriter, r *http.Request) {
	s.serveManifest(w, r, filepath.Join(s.cfg.DataDir, "updates"))
}

func (s *Server) handleUpdateFile(w http.ResponseWriter, r *http.Request) {
	s.serveUpdateFile(w, r, filepath.Join(s.cfg.DataDir, "updates"))
}

// Alpha builds live in updates/alpha, a directory the public handlers never read, and are
// served only to accounts listed in alpha_testers. Everyone else gets the same 404 as when
// nothing is published, so the channel does not reveal itself.

func (s *Server) handleAlphaManifest(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.alphaTester(w, r, a) {
		return
	}
	s.serveManifest(w, r, filepath.Join(s.cfg.DataDir, "updates", "alpha"))
}

func (s *Server) handleAlphaFile(w http.ResponseWriter, r *http.Request, a *authCtx) {
	if !s.alphaTester(w, r, a) {
		return
	}
	s.serveUpdateFile(w, r, filepath.Join(s.cfg.DataDir, "updates", "alpha"))
}

// alphaTester answers false after writing the response when the account has no access.
func (s *Server) alphaTester(w http.ResponseWriter, r *http.Request, a *authCtx) bool {
	var ok bool
	if err := s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM alpha_testers WHERE user_id=$1)`, a.UserID).Scan(&ok); err != nil {
		s.fail(w, r, err)
		return false
	}
	if !ok {
		s.fail(w, r, errNotFound)
	}
	return ok
}

func (s *Server) serveManifest(w http.ResponseWriter, r *http.Request, dir string) {
	manifest, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if errors.Is(err, os.ErrNotExist) {
		s.fail(w, r, errNotFound)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	sig, err := os.ReadFile(filepath.Join(dir, "manifest.sig"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, 200, map[string][]byte{"manifest": manifest, "signature": sig})
}

func (s *Server) serveUpdateFile(w http.ResponseWriter, r *http.Request, dir string) {
	name := r.PathValue("name")
	if !updateFileRe.MatchString(name) {
		s.fail(w, r, errNotFound)
		return
	}
	path := filepath.Join(dir, name)
	if _, err := os.Stat(path); err != nil {
		s.fail(w, r, errNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
	http.ServeFile(w, r, path)
}
