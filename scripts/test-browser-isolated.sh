#!/bin/sh
# Isolate in-memory rate limits between browser suites without weakening them.
set -eu
cd "$(dirname "$0")/.."
mkdir -p .local/browser-logs
if curl -fsS http://localhost:3000/ >/dev/null 2>&1; then
  echo 'Le port 3000 est déjà utilisé. Arrêtez le frontend avant cette suite isolée.' >&2
  exit 1
fi
WEB_PID=''
cleanup() {
  if [ -n "$WEB_PID" ]; then
    kill "$WEB_PID" 2>/dev/null || true
    wait "$WEB_PID" 2>/dev/null || true
    WEB_PID=''
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
for spec in apps/web/tests/*.spec.ts; do
  name=$(basename "$spec" .spec.ts)
  echo "Suite isolée : $name"
  docker compose restart api-gateway >/dev/null
  node apps/web/node_modules/next/dist/bin/next start apps/web --hostname 127.0.0.1 > ".local/browser-logs/$name.log" 2>&1 &
  WEB_PID=$!
  ready=false
  attempt=0
  while [ "$attempt" -lt 60 ]; do
    if ! kill -0 "$WEB_PID" 2>/dev/null; then
      echo "Le frontend s’est arrêté. Voir .local/browser-logs/$name.log" >&2
      exit 1
    fi
    if curl -fsS http://localhost:3000/ >/dev/null 2>&1 && curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
      ready=true
      break
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  if [ "$ready" != true ]; then
    echo "Frontend ou gateway indisponible après 60 secondes : $name" >&2
    exit 1
  fi
  npm --prefix apps/web run test:e2e -- "$(basename "$spec")"
  cleanup
done
