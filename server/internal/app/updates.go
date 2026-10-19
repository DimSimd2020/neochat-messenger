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
	dir := filepath.Join(s.cfg.DataDir, "updates")
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

func (s *Server) handleUpdateFile(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if !updateFileRe.MatchString(name) {
		s.fail(w, r, errNotFound)
		return
	}
	path := filepath.Join(s.cfg.DataDir, "updates", name)
	if _, err := os.Stat(path); err != nil {
		s.fail(w, r, errNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
	http.ServeFile(w, r, path)
}
