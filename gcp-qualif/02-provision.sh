#!/usr/bin/env bash
# One-time provisioning of the qualification VM.
# Run ON THE VM as root (sudo bash 02-provision.sh).
# Installs: JDK 21, PostgreSQL 16, Caddy; creates databases, users, app layout, systemd units.
set -euo pipefail

DB_PASSWORD="${DB_PASSWORD:-CHANGE_ME}"   # single app db password for qualification

# --- Packages -----------------------------------------------------------
apt-get update
apt-get install -y openjdk-21-jre-headless postgresql-16 rsync curl \
  debian-keyring debian-archive-keyring apt-transport-https

# Caddy (official repo)
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  > /etc/apt/sources.list.d/caddy-stable.list
apt-get update && apt-get install -y caddy

# --- Service user and app layout ---------------------------------------
useradd -r -m -d /opt/apps -s /usr/sbin/nologin apps || true
mkdir -p /opt/apps/{impos,imvaluation,imfid}
mkdir -p /opt/apps/impos/data          # H2 file database (one DB per till, by design)
mkdir -p /opt/apps/impos/simulator     # hardware simulator static page (served by Caddy)
mkdir -p /opt/apps/imvaluation/data    # H2 file database (engine is a till-local component)
chown -R apps:apps /opt/apps

# --- PostgreSQL: database for imfid only --------------------------------
# imfid is the only central service by nature (shared accounts, one active
# reservation per card). impos and imvaluation stay on H2 file: the sale and
# its engine must not depend on any server, per the target architecture.
sudo -u postgres psql <<SQL
CREATE ROLE imfid LOGIN PASSWORD '${DB_PASSWORD}';
CREATE DATABASE imfid OWNER imfid;
SQL

# --- systemd units + Caddyfile (shipped alongside this script) ----------
cp impos.service imvaluation.service imfid.service /etc/systemd/system/
cp Caddyfile /etc/caddy/Caddyfile
sed -i "s/__DB_PASSWORD__/${DB_PASSWORD}/" /etc/systemd/system/imfid.service

systemctl daemon-reload
systemctl enable impos imvaluation imfid caddy
systemctl restart caddy

echo "Provisioning done. Deploy the jars with 03-deploy.sh, then services will start."
