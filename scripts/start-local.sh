#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 scripts/setup-web.py --configure-only
python3 scripts/prepare-notifications.py
docker compose up -d --wait audit-db web-db keycloak-db notification-db kafka
docker compose run --rm kafka-init
python3 scripts/harden-databases.py
docker compose build audit-service api-gateway notification-service
# The migration process receives a separate non-superuser owner credential.
python3 scripts/migrate-local.py
python3 scripts/harden-databases.py
docker compose up -d
python3 scripts/setup-web.py
