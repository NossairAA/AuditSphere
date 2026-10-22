#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 scripts/setup-web.py --configure-only
python3 scripts/prepare-notifications.py
python3 scripts/setup-garage.py --configure-only
docker compose up -d garage
python3 scripts/setup-garage.py
docker compose up -d --wait audit-db web-db keycloak-db notification-db kafka temporal
docker compose run --rm kafka-init
python3 scripts/harden-databases.py
docker compose build audit-service api-gateway notification-service
docker compose stop audit-service workflow-worker
# The migration process receives a separate non-superuser owner credential.
python3 scripts/migrate-local.py
python3 scripts/harden-databases.py
docker compose up -d
# Refresh gateway connections after service containers have been replaced.
docker compose restart api-gateway
python3 scripts/setup-web.py
