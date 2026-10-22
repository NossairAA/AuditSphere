#!/bin/sh
# Consistent offline backup. Run from the dedicated Garage deployment directory.
set -eu
umask 077
sudo -v
mkdir -p backups
file="backups/garage-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
docker compose stop garage
trap 'docker compose start garage >/dev/null' EXIT
sudo tar -czf "$file" meta data .local/garage.toml .env compose.yaml
sudo chmod 600 "$file"
echo "$file"
