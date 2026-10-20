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
scp -q "$OUT/neochat" infra/neochat.service infra/nginx-neochat.conf infra/neochat-backup.service infra/neochat-backup.timer infra/postgresql-neochat.conf infra/turnserver.conf "$HOST:/tmp/"

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
# TURN relay for calls. The shared secret is generated here once and stays on the host.
command -v turnserver >/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -y -q coturn >/dev/null
install -d -m 755 /etc/neochat
if [ ! -s /etc/neochat/turn.env ]; then
  TURN_HOST=$(sed -n 's/^realm=//p' /tmp/turnserver.conf)
  (umask 077; printf 'NEOCHAT_TURN_SECRET=%s\nNEOCHAT_TURN_URIS="turn:%s:3478?transport=udp turn:%s:3478?transport=tcp"\n' \
    "$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')" "$TURN_HOST" "$TURN_HOST" > /etc/neochat/turn.env)
fi
(umask 027; { cat /tmp/turnserver.conf; sed -n 's/^NEOCHAT_TURN_SECRET=/static-auth-secret=/p' /etc/neochat/turn.env; } > /etc/turnserver.conf)
chgrp turnserver /etc/turnserver.conf
systemctl enable -q coturn
systemctl restart coturn
for rule in 3478/udp 3478/tcp 49160:49200/udp; do ufw status | grep -q "^$rule " || ufw allow "$rule" >/dev/null; done
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
rm -f /tmp/neochat /tmp/neochat.service /tmp/nginx-neochat.conf /tmp/neochat-backup.* /tmp/postgresql-neochat.conf /tmp/turnserver.conf
sleep 1
systemctl is-active neochat coturn
REMOTE
