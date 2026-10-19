#!/usr/bin/env bash
# Builds the server for linux/amd64 and installs it on the host reachable as
# `ssh neochat`. Idempotent: re-running upgrades the binary and restarts the unit.
# First run also installs PostgreSQL, creates the neochat user/database and the
# nginx site on port 8443 (the existing nginx sites are not modified).
set -euo pipefail
cd "$(dirname "$0")/.."
HOST="${NEOCHAT_HOST:-neochat}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

(cd server && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o "$OUT/neochat" ./cmd/neochat)
scp -q "$OUT/neochat" infra/neochat.service infra/nginx-neochat.conf infra/neochat-backup.service infra/neochat-backup.timer infra/postgresql-neochat.conf "$HOST:/tmp/"

ssh "$HOST" 'sudo bash -s' <<'REMOTE'
set -euo pipefail
if ! command -v psql >/dev/null; then
  apt-get update -q >/dev/null; DEBIAN_FRONTEND=noninteractive apt-get install -y -q postgresql >/dev/null
fi
PGCONF=$(ls -d /etc/postgresql/*/main | head -1)
install -m 644 /tmp/postgresql-neochat.conf "$PGCONF/conf.d/neochat.conf"
systemctl restart postgresql
id neochat >/dev/null 2>&1 || useradd --system --home-dir /var/lib/neochat --shell /usr/sbin/nologin neochat
sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='neochat'" | grep -q 1 || sudo -u postgres createuser neochat
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='neochat'" | grep -q 1 || sudo -u postgres createdb -O neochat neochat
install -d -m 755 /opt/neochat
install -m 755 /tmp/neochat /opt/neochat/neochat
install -m 644 /tmp/neochat.service /etc/systemd/system/neochat.service
install -m 644 /tmp/neochat-backup.service /tmp/neochat-backup.timer /etc/systemd/system/
install -d -m 700 -o postgres -g postgres /var/backups/neochat
install -m 644 /tmp/nginx-neochat.conf /etc/nginx/sites-available/neochat
ln -sf /etc/nginx/sites-available/neochat /etc/nginx/sites-enabled/neochat
nginx -t -q || { rm -f /etc/nginx/sites-enabled/neochat; exit 1; }
systemctl daemon-reload
systemctl enable -q --now neochat-backup.timer
systemctl enable -q neochat
systemctl restart neochat
systemctl reload nginx
ufw status | grep -q '8443/tcp' || ufw allow 8443/tcp >/dev/null
rm -f /tmp/neochat /tmp/neochat.service /tmp/nginx-neochat.conf /tmp/neochat-backup.* /tmp/postgresql-neochat.conf
sleep 1
systemctl is-active neochat
REMOTE
