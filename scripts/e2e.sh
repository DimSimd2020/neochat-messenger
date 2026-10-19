#!/usr/bin/env bash
# End-to-end check: real Go server + PostgreSQL + several neocore clients.
# Needs a disposable database in NEOCHAT_E2E_DATABASE_URL (it is wiped).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${NEOCHAT_E2E_DATABASE_URL:?set NEOCHAT_E2E_DATABASE_URL to a disposable PostgreSQL database}"
PORT="${NEOCHAT_E2E_PORT:-8099}"
DATA="$(mktemp -d)"
BIN="$DATA/neochat$( [ "${OS:-}" = "Windows_NT" ] && echo .exe )"

(cd server && go build -o "$BIN" ./cmd/neochat)
psql_url="$NEOCHAT_E2E_DATABASE_URL"
export NEOCHAT_DATABASE_URL="$psql_url" NEOCHAT_DATA_DIR="$DATA/data" NEOCHAT_LISTEN="127.0.0.1:$PORT" NEOCHAT_ARGON_MEMORY_KIB=1024
"$BIN" migrate
INVITES=$("$BIN" invite -n 40 | paste -sd, -)
"$BIN" serve >"$DATA/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true; rm -rf "$DATA"' EXIT
for _ in $(seq 50); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 0.2; done

cd core
NEOCHAT_E2E_SERVER="http://127.0.0.1:$PORT" NEOCHAT_E2E_INVITES="$INVITES" cargo test --test e2e -- --test-threads=3 "$@"
