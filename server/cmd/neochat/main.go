// Command neochat runs the NeoChat server and its maintenance tasks.
//
//	neochat serve           run the HTTP API (migrates first)
//	neochat migrate         apply database migrations
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"neochat/server/internal/app"
	"neochat/server/migrations"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: neochat serve|migrate")
		os.Exit(2)
	}
	log := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := pgxpool.New(ctx, env("NEOCHAT_DATABASE_URL", "postgres:///neochat?host=/run/postgresql"))
	if err != nil {
		fatal(log, "db", err)
	}
	defer db.Close()

	switch os.Args[1] {
	case "migrate":
		if err := app.Migrate(ctx, db, migrations.FS); err != nil {
			fatal(log, "migrate", err)
		}
	case "serve":
		if err := app.Migrate(ctx, db, migrations.FS); err != nil {
			fatal(log, "migrate", err)
		}
		dataDir := env("NEOCHAT_DATA_DIR", "data")
		for _, d := range []string{"objects", "tmp"} {
			if err := os.MkdirAll(filepath.Join(dataDir, d), 0o700); err != nil {
				fatal(log, "data dir", err)
			}
		}
		mem, _ := strconv.Atoi(env("NEOCHAT_ARGON_MEMORY_KIB", "65536"))
		par, _ := strconv.Atoi(env("NEOCHAT_ARGON_PARALLEL", "2"))
		fcm, err := app.NewFCMClient(ctx, os.Getenv("GOOGLE_APPLICATION_CREDENTIALS"))
		if err != nil {
			fatal(log, "FCM configuration", err)
		}
		email, err := app.NewFirebaseEmailSender(ctx, os.Getenv("GOOGLE_APPLICATION_CREDENTIALS"), os.Getenv("NEOCHAT_FIREBASE_API_KEY"))
		if err != nil {
			fatal(log, "email configuration", err)
		}
		srv := app.New(db, app.Config{
			DataDir: dataDir, ArgonMemoryKiB: uint32(mem), ArgonParallel: par,
			TurnSecret: os.Getenv("NEOCHAT_TURN_SECRET"), TurnURIs: strings.Fields(os.Getenv("NEOCHAT_TURN_URIS")),
			FCM:   fcm,
			Email: email, EmailBaseURL: os.Getenv("NEOCHAT_EMAIL_BASE_URL"),
		}, log)
		go srv.RunCleanup(ctx)
		go srv.RunPush(ctx)
		hs := &http.Server{
			Addr:              env("NEOCHAT_LISTEN", "127.0.0.1:8090"),
			Handler:           srv,
			ReadHeaderTimeout: 10 * time.Second,
			IdleTimeout:       120 * time.Second,
		}
		go func() {
			<-ctx.Done()
			sctx, c := context.WithTimeout(context.Background(), 10*time.Second)
			defer c()
			hs.Shutdown(sctx)
		}()
		log.Info("listening", "addr", hs.Addr)
		if err := hs.ListenAndServe(); err != http.ErrServerClosed {
			fatal(log, "serve", err)
		}
	default:
		fmt.Fprintln(os.Stderr, "unknown command")
		os.Exit(2)
	}
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func fatal(log *slog.Logger, what string, err error) {
	log.Error(what, "err", err)
	os.Exit(1)
}
