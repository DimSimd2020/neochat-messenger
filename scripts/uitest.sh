#!/usr/bin/env bash
# Desktop UI scenario test: real Go server + PostgreSQL + the Qt widgets offscreen.
# Needs a disposable database in NEOCHAT_E2E_DATABASE_URL (test accounts stay in it) and a
# built apps/desktop/build/neochat_uitest.exe. Screenshots go to NEOCHAT_SHOTS.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${NEOCHAT_E2E_DATABASE_URL:?set NEOCHAT_E2E_DATABASE_URL to a disposable PostgreSQL database}"
PORT="${NEOCHAT_E2E_PORT:-8098}"
QT_DIR="${QT_DIR:-E:/dev/Qt/6.8.3}"
DATA="$(mktemp -d)"
BIN="$DATA/neochat.exe"

(cd server && go build -o "$BIN" ./cmd/neochat)
export NEOCHAT_DATABASE_URL="$NEOCHAT_E2E_DATABASE_URL" NEOCHAT_DATA_DIR="$DATA/data" NEOCHAT_LISTEN="127.0.0.1:$PORT" NEOCHAT_ARGON_MEMORY_KIB=1024
"$BIN" migrate
"$BIN" serve >"$DATA/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true; rm -rf "$DATA"' EXIT
for _ in $(seq 50); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 0.2; done

export PATH="$(cygpath -u "$QT_DIR")/bin:$PATH" QT_QPA_PLATFORM=offscreen QT_QPA_FONTDIR="${QT_QPA_FONTDIR:-C:/Windows/Fonts}"
NEOCHAT_E2E_SERVER="http://127.0.0.1:$PORT" NEOCHAT_SHOTS="${NEOCHAT_SHOTS:-$PWD/apps/desktop/build/shots}" \
  apps/desktop/build/neochat_uitest.exe -o "$DATA/uitest.txt,txt" "$@" && rc=0 || rc=$?
cat "$DATA/uitest.txt"
exit $rc
