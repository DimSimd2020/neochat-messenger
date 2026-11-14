#!/usr/bin/env bash
# End-to-end check: real Go server + PostgreSQL + several neocore clients.
# Needs a disposable database in NEOCHAT_TEST_DATABASE_URL (it is wiped).
#
#   NEOCHAT_TEST_DATABASE_URL=postgres://... bash scripts/e2e.sh              # the whole suite
#   NEOCHAT_TEST_DATABASE_URL=postgres://... bash scripts/e2e.sh spoilers_and_one_time_media
#
# One fresh server per Rust test: registration asks the server to confirm a mailbox, and the
# server allows six of those per hour per IP. The test-only mailbox in the Go test binary
# confirms @example.test itself, so a new process per test is also what keeps the limiter out
# of the way. The same shape runs in CI in .github/workflows/tests.yml.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${NEOCHAT_TEST_DATABASE_URL:?set NEOCHAT_TEST_DATABASE_URL to a disposable PostgreSQL database}"

cd core
cargo test --test e2e --no-run
BIN=$(cargo test --test e2e --no-run --message-format=json 2>/dev/null \
  | jq -r 'select(.executable != null) | .executable' | tail -n1)
[ -n "$BIN" ] || { echo "e2e binary not found (is jq installed?)"; exit 1; }

if [ $# -gt 0 ]; then NAMES=("$@"); else NAMES=$("$BIN" --list --format=terse | sed -n 's/: test$//p'); fi

cd ../server
failed=()
for t in $NAMES; do
  printf '== %s ' "$t"
  cmd=$(jq -cn --arg b "$BIN" --arg t "$t" '[$b, $t, "--exact"]')
  if NEOCHAT_NATIVE_TEST_COMMAND="$cmd" go test ./internal/app -run '^TestNativeEmailFlow$' -count=1 -timeout=10m >"/tmp/e2e-$t.log" 2>&1; then
    echo ok
  else
    echo FAIL
    tail -n 25 "/tmp/e2e-$t.log"
    failed+=("$t")
  fi
done

echo "run: $(echo "$NAMES" | wc -w), failed: ${#failed[@]}"
[ "${#failed[@]}" -eq 0 ]
