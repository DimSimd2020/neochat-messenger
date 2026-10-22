// Package app is the NeoChat server: one process with HTTP JSON API,
// WebSocket sync hints and PostgreSQL as the source of truth.
package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Config struct {
	DataDir        string // encrypted attachment objects
	ArgonMemoryKiB uint32
	ArgonTime      uint32
	ArgonParallel  int        // concurrent password hashes
	TurnSecret     string     // coturn static-auth-secret; empty disables TURN
	TurnURIs       []string   // e.g. turn:host:3478?transport=udp
	FCM            *FCMClient // nil keeps push delivery disabled
	Email          EmailSender
	EmailBaseURL   string // public HTTPS origin for email confirmation
}

type Server struct {
	db     *pgxpool.Pool
	cfg    Config
	hub    *hub
	log    *slog.Logger
	argon  chan struct{}
	limits *limiter
	mux    *http.ServeMux
}

func New(db *pgxpool.Pool, cfg Config, log *slog.Logger) *Server {
	if cfg.ArgonMemoryKiB == 0 {
		cfg.ArgonMemoryKiB = 64 * 1024
	}
	if cfg.ArgonTime == 0 {
		cfg.ArgonTime = 3
	}
	if cfg.ArgonParallel <= 0 {
		cfg.ArgonParallel = 2
	}
	s := &Server{
		db:     db,
		cfg:    cfg,
		hub:    newHub(),
		log:    log,
		argon:  make(chan struct{}, cfg.ArgonParallel),
		limits: newLimiter(),
		mux:    http.NewServeMux(),
	}
	s.routes()
	if dummyHash == "" {
		dummyHash, _ = s.hashPassword(context.Background(), "neochat-timing-equalizer")
	}
	return s
}

func (s *Server) routes() {
	m := s.mux
	m.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]string{"status": "ok"})
	})

	m.HandleFunc("GET /v1/app/manifest", s.handleUpdateManifest)
	m.HandleFunc("GET /v1/app/files/{name}", s.handleUpdateFile)

	m.HandleFunc("POST /v1/accounts", s.handleRegister)
	m.HandleFunc("POST /v1/accounts/email", s.handleRegistrationEmail)
	m.HandleFunc("POST /v1/accounts/email/status", s.handleEmailTicket)
	m.HandleFunc("GET /v1/accounts/email/confirm", s.handleEmailConfirm)
	m.HandleFunc("POST /v1/accounts/email/confirm", s.handleEmailConfirm)
	m.HandleFunc("GET /v1/accounts/current/email", s.authed(s.handleEmailStatus))
	m.HandleFunc("POST /v1/accounts/current/email", s.authed(s.handleLinkEmail))
	m.HandleFunc("POST /v1/accounts/recover", s.handleRecover)
	m.HandleFunc("POST /v1/accounts/password-salt", s.handlePasswordSalt)
	m.HandleFunc("POST /v1/sessions", s.handleLogin)
	m.HandleFunc("POST /v1/sessions/refresh", s.handleRefresh)
	m.HandleFunc("DELETE /v1/sessions/current", s.authed(s.handleLogout))

	m.HandleFunc("GET /v1/devices", s.authed(s.handleListDevices))
	m.HandleFunc("DELETE /v1/devices/{id}", s.authed(s.handleRevokeDevice))
	m.HandleFunc("POST /v1/devices/current/prekeys", s.authed(s.handleUploadPrekeys))
	m.HandleFunc("GET /v1/devices/current/prekeys", s.authed(s.handlePrekeyCount))
	m.HandleFunc("PUT /v1/devices/current/push", s.authed(s.handlePushToken))
	m.HandleFunc("POST /v1/devices/new/start", s.handleNewDeviceStart)
	m.HandleFunc("POST /v1/devices/new/finish", s.handleNewDeviceFinish)
	m.HandleFunc("POST /v1/devices/qr", s.handleQRStart)
	m.HandleFunc("GET /v1/devices/qr/{id}", s.handleQRPoll)
	m.HandleFunc("GET /v1/devices/qr/{id}/device", s.authed(s.handleQRDevice))
	m.HandleFunc("POST /v1/devices/qr/{id}/approve", s.authed(s.handleQRApprove))
	m.HandleFunc("PUT /v1/accounts/current/profile", s.authed(s.handlePutProfile))
	m.HandleFunc("PUT /v1/accounts/current/avatar", s.authed(s.handleUploadAvatar))
	m.HandleFunc("DELETE /v1/accounts/current/avatar", s.authed(s.handleDeleteAvatar))
	m.HandleFunc("PUT /v1/accounts/current/photos", s.authed(s.handleUploadPhoto))
	m.HandleFunc("DELETE /v1/accounts/current/photos/{photo}", s.authed(s.handleDeletePhoto))
	m.HandleFunc("GET /v1/accounts/current/settings", s.authed(s.handleGetSettings))
	m.HandleFunc("PUT /v1/accounts/current/settings", s.authed(s.handlePutSettings))
	m.HandleFunc("GET /v1/accounts/current/history", s.authed(s.handleGetHistory))
	m.HandleFunc("PUT /v1/accounts/current/history", s.authed(s.handlePutHistory))
	m.HandleFunc("PUT /v1/accounts/current/roster", s.authed(s.handleUpdateRoster))
	m.HandleFunc("GET /v1/accounts/current/state", s.authed(s.handleGetState))
	m.HandleFunc("PUT /v1/accounts/current/state", s.authed(s.handlePutState))

	m.HandleFunc("POST /v1/contacts/resolve", s.authed(s.handleResolve))
	m.HandleFunc("GET /v1/users/{id}", s.authed(s.handleGetUser))
	m.HandleFunc("GET /v1/users/{id}/avatar/{avatar}", s.authed(s.handleGetAvatar))
	m.HandleFunc("GET /v1/users/{id}/photos", s.authed(s.handleListPhotos))
	m.HandleFunc("GET /v1/users/{id}/photos/{photo}", s.authed(s.handleGetPhoto))
	m.HandleFunc("POST /v1/users/{id}/bundles", s.authed(s.handleClaimBundles))

	m.HandleFunc("GET /v1/conversations", s.authed(s.handleListConversations))
	m.HandleFunc("GET /v1/conversations/{id}", s.authed(s.handleGetConversation))
	m.HandleFunc("POST /v1/conversations/direct", s.authed(s.handleDirect))
	m.HandleFunc("POST /v1/conversations/groups", s.authed(s.handleCreateGroup))
	m.HandleFunc("POST /v1/conversations/{id}/membership", s.authed(s.handleMembership))
	m.HandleFunc("POST /v1/conversations/{id}/leave", s.authed(s.handleLeave))
	m.HandleFunc("GET /v1/conversations/{id}/states", s.authed(s.handleStates))
	m.HandleFunc("PUT /v1/conversations/{id}/settings", s.authed(s.handleSettings))
	m.HandleFunc("POST /v1/conversations/{id}/typing", s.authed(s.handleTyping))
	m.HandleFunc("POST /v1/presence", s.authed(s.handlePresence))
	m.HandleFunc("POST /v1/conversations/{id}/messages", s.authed(s.handleSend))

	m.HandleFunc("GET /v1/sync", s.authed(s.handleSync))
	m.HandleFunc("GET /v1/events", s.authed(s.handleEvents))
	m.HandleFunc("GET /v1/calls/ice", s.authed(s.handleICE))

	m.HandleFunc("POST /v1/attachments", s.authed(s.handleReserveAttachment))
	m.HandleFunc("PUT /v1/attachments/{id}/content", s.authed(s.handleUploadAttachment))
	m.HandleFunc("GET /v1/attachments/{id}/content", s.authed(s.handleDownloadAttachment))
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	rid := newRequestID()
	w.Header().Set("X-Request-Id", rid)
	w.Header().Set("Cache-Control", "no-store")
	ctx := context.WithValue(r.Context(), ctxRequestID{}, rid)
	start := time.Now()
	rec := &statusRecorder{ResponseWriter: w, status: 200}
	s.mux.ServeHTTP(rec, r.WithContext(ctx))
	// Never log the full URL, bodies or tokens: only the route pattern.
	s.log.Info("request", "rid", rid, "route", r.Pattern, "status", rec.status, "ms", time.Since(start).Milliseconds())
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// Unwrap lets http.ResponseController and websocket.Accept reach the original writer.
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

type ctxRequestID struct{}

func newRequestID() string {
	b := make([]byte, 8)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// apiError is the only error shape returned to clients.
type apiError struct {
	Status       int    `json:"-"`
	Code         string `json:"code"`
	Message      string `json:"message"`
	RequestID    string `json:"request_id,omitempty"`
	RetryAfterMs int64  `json:"retry_after_ms,omitempty"`
	Details      any    `json:"details,omitempty"`
}

func (e *apiError) Error() string { return e.Code }

func errf(status int, code, msg string) *apiError {
	return &apiError{Status: status, Code: code, Message: msg}
}

var (
	errNotFound     = errf(404, "not_found", "Объект не найден")
	errUnauthorized = errf(401, "unauthorized", "Требуется вход")
	errForbidden    = errf(403, "forbidden", "Недостаточно прав")
	errBadRequest   = errf(400, "bad_request", "Некорректный запрос")
)

func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	var ae *apiError
	if !errors.As(err, &ae) {
		if errors.Is(err, context.Canceled) {
			return
		}
		s.log.Error("internal", "rid", r.Context().Value(ctxRequestID{}), "route", r.Pattern, "err", err)
		ae = errf(503, "unavailable", "Сервис временно недоступен")
		ae.RetryAfterMs = 1000
	}
	out := *ae
	out.RequestID, _ = r.Context().Value(ctxRequestID{}).(string)
	writeJSON(w, ae.Status, out)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// readJSON decodes a size-limited body and rejects unknown fields.
func readJSON(w http.ResponseWriter, r *http.Request, limit int64, v any) error {
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			return errf(413, "too_large", "Слишком большой запрос")
		}
		return errBadRequest
	}
	return nil
}

func clientIP(r *http.Request) string {
	// nginx on the same host sets X-Real-IP; the service only listens on loopback.
	if ip := r.Header.Get("X-Real-IP"); ip != "" {
		return ip
	}
	host := r.RemoteAddr
	if i := strings.LastIndexByte(host, ':'); i > 0 {
		host = host[:i]
	}
	return host
}
