package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Migrate applies embedded migrations in name order, each in its own transaction.
func Migrate(ctx context.Context, db *pgxpool.Pool, files fs.FS) error {
	if _, err := db.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	names, err := fs.Glob(files, "*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	for _, name := range names {
		var done bool
		if err := db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE name=$1)`, name).Scan(&done); err != nil {
			return err
		}
		if done {
			continue
		}
		body, err := fs.ReadFile(files, name)
		if err != nil {
			return err
		}
		err = pgx.BeginFunc(ctx, db, func(tx pgx.Tx) error {
			if _, err := tx.Exec(ctx, string(body)); err != nil {
				return fmt.Errorf("%s: %w", name, err)
			}
			_, err := tx.Exec(ctx, `INSERT INTO schema_migrations(name) VALUES ($1)`, name)
			return err
		})
		if err != nil {
			return err
		}
	}
	// The feed incarnation changes only when an operator restores a backup (see infra/README.md).
	b := make([]byte, 8)
	rand.Read(b)
	_, err = db.Exec(ctx, `INSERT INTO meta(key, value) VALUES ('incarnation', $1) ON CONFLICT DO NOTHING`, hex.EncodeToString(b))
	return err
}

// inTx runs fn in a transaction and retries a bounded number of times on
// deadlock or serialization failure. Exhausted retries surface as retryable 503.
func (s *Server) inTx(ctx context.Context, fn func(pgx.Tx) error) error {
	var err error
	for attempt := 0; attempt < 4; attempt++ {
		err = pgx.BeginFunc(ctx, s.db, func(tx pgx.Tx) error {
			e := fn(tx)
			var ct *commitThen
			if errors.As(e, &ct) {
				if ce := tx.Commit(ctx); ce != nil {
					return ce
				}
			}
			return e
		})
		var pe *pgconn.PgError
		if errors.As(err, &pe) && (pe.Code == "40001" || pe.Code == "40P01") {
			time.Sleep(time.Duration(10*(attempt+1)) * time.Millisecond)
			continue
		}
		return err
	}
	return err
}

func isUniqueViolation(err error) bool {
	var pe *pgconn.PgError
	return errors.As(err, &pe) && pe.Code == "23505"
}

func constraintName(err error) string {
	var pe *pgconn.PgError
	if errors.As(err, &pe) {
		return pe.ConstraintName
	}
	return ""
}

func (s *Server) incarnation(ctx context.Context) (string, error) {
	var v string
	err := s.db.QueryRow(ctx, `SELECT value FROM meta WHERE key='incarnation'`).Scan(&v)
	return v, err
}
