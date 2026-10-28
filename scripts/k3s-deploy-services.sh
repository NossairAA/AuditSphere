#!/usr/bin/env bash
# Déploie les services Spring dans k3s (phase 4, lot A). Les valeurs propres au tailnet viennent de .local/k3s.env.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
env_file="$root/.local/k3s.env"
[ -f "$env_file" ] || { echo "Fichier absent : .local/k3s.env (APP_ORIGIN, OIDC_ORIGIN, GARAGE_ENDPOINT)." >&2; exit 1; }
# shellcheck disable=SC1090
. "$env_file"
: "${OIDC_ORIGIN:?}" "${GARAGE_ENDPOINT:?}"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"

chart="$root/infra/helm/spring-service"
values="$root/infra/helm/values"
deploy() {
  name=$1
  shift
  helm upgrade --install "$name" "$chart" --namespace auditsphere \
    --values "$values/$name.yaml" --set-string oidc.origin="$OIDC_ORIGIN" \
    --wait --wait-for-jobs --timeout 10m "$@"
}

# Keycloak d'abord : les services valident les jetons qu'il émet.
# KEYCLOAK_ADMIN_ORIGIN, facultatif : console sur le nom du tailnet quand OIDC_ORIGIN est public (ADR-0033).
helm upgrade --install keycloak "$root/infra/helm/keycloak" --namespace auditsphere \
  --set-string origin="$OIDC_ORIGIN" --set-string adminOrigin="${KEYCLOAK_ADMIN_ORIGIN:-}" --set-file realm="$root/deploy/keycloak/production-realm.json" \
  --wait --timeout 10m

# audit-service porte les migrations dont dépendent les autres services, il passe en premier.
deploy audit-service --set-string env.EVIDENCE_S3_ENDPOINT="$GARAGE_ENDPOINT"
deploy notification-service
deploy remediation-service
deploy workflow-worker
deploy api-gateway

# Frontend et Traefik en bordure, derrière l'entrée Tailscale app.
: "${APP_ORIGIN:?}"
helm upgrade --install web "$root/infra/helm/web" --namespace auditsphere \
  --set-string appOrigin="$APP_ORIGIN" --set-string oidcOrigin="$OIDC_ORIGIN" \
  --set-file sessionsSql="$root/apps/web/db/001-sessions.sql" \
  --set-file encryptedSessionsSql="$root/apps/web/db/002-encrypted-sessions.sql" \
  --wait --wait-for-jobs --timeout 10m
kubectl --namespace auditsphere get pods
