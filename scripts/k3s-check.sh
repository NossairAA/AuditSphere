#!/usr/bin/env bash
# Lance un script de vérification contre k3s (phase 4, lot E), par exemple : scripts/k3s-check.sh test-temporal.py --faults
# Gateway jointe par un port-forward temporaire, CLI Temporal dans un pod éphémère, aucun secret affiché.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
[ $# -ge 1 ] || { echo "Usage : $0 <script.py> [arguments]" >&2; exit 2; }
env_file="$root/.local/k3s.env"
accounts="$root/.local/k3s-demo-accounts.env"
[ -f "$env_file" ] && [ -f "$accounts" ] || { echo "Fichiers absents : .local/k3s.env ou .local/k3s-demo-accounts.env." >&2; exit 1; }
# shellcheck disable=SC1090
. "$env_file"
: "${APP_ORIGIN:?}" "${OIDC_ORIGIN:?}"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
port=${GATEWAY_PORT:-18080}

kubectl --namespace auditsphere port-forward svc/api-gateway "$port:8080" >/dev/null 2>&1 &
forward=$!
disown "$forward"
cleanup() {
  kill "$forward" 2>/dev/null || true
  kubectl --namespace data delete pod temporal-cli --ignore-not-found --wait=false >/dev/null
}
trap cleanup EXIT
# Un pod en cours de suppression après un lancement précédent ne doit pas être réutilisé.
kubectl --namespace data delete pod temporal-cli --ignore-not-found >/dev/null
kubectl apply -f "$root/deploy/k3s/tools/temporal-cli.yaml" >/dev/null
kubectl --namespace data wait --for=condition=Ready pod/temporal-cli --timeout=120s >/dev/null
for _ in $(seq 30); do
  curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 && break
  sleep 1
done

AUDITSPHERE_TARGET=k3s AUDITSPHERE_API_BASE="http://127.0.0.1:$port" AUDITSPHERE_OIDC_BASE="$OIDC_ORIGIN" \
AUDITSPHERE_ACCOUNTS_FILE="$accounts" AUDITSPHERE_WAIT=300 APP_ORIGIN="$APP_ORIGIN" \
REMEDIATION_SYNC_CLIENT_SECRET=$(kubectl --namespace auditsphere get secret app-secrets -o jsonpath='{.data.REMEDIATION_SYNC_CLIENT_SECRET}' | base64 -d) \
  python3 "$root/scripts/$1" "${@:2}"
