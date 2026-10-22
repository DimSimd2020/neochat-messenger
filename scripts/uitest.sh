#!/usr/bin/env bash
# Desktop UI scenario test: real Go server + PostgreSQL + the Qt widgets offscreen.
# Needs a disposable database in NEOCHAT_E2E_DATABASE_URL (test accounts stay in it) and a
# built apps/desktop/build/neochat_uitest.exe. Screenshots go to NEOCHAT_SHOTS.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${NEOCHAT_E2E_DATABASE_URL:?set NEOCHAT_E2E_DATABASE_URL to a disposable PostgreSQL database}"
QT_DIR="${QT_DIR:-E:/dev/Qt/6.8.3}"
export PATH="$(cygpath -u "$QT_DIR")/bin:$PATH" QT_QPA_PLATFORM=offscreen QT_QPA_FONTDIR="${QT_QPA_FONTDIR:-C:/Windows/Fonts}"
export NEOCHAT_SHOTS="${NEOCHAT_SHOTS:-$PWD/apps/desktop/build/shots}"
export NEOCHAT_NATIVE_TEST_COMMAND
NEOCHAT_NATIVE_TEST_COMMAND=$(python -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "$PWD/apps/desktop/build/neochat_uitest.exe" "$@")
export NEOCHAT_TEST_DATABASE_URL="$NEOCHAT_E2E_DATABASE_URL"
# The Go test hosts the real API with a test-only mailbox for @example.test.
# Production server/client binaries never contain an email-verification bypass.
cd server
go test ./internal/app -run '^TestNativeEmailFlow$' -count=1 -timeout=15m -v
