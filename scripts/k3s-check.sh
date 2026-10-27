#!/usr/bin/env bash
# Lance un script de vérification contre k3s (phase 4, lot E), par exemple : scripts/k3s-check.sh test-temporal.py --faults
# Gateway jointe par un port-forward temporaire, CLI Temporal dans un pod éphémère, aucun secret affiché.
# Le client auditsphere-demo, désactivé au repos, n'est actif que pendant le script (phase 10, ADR-0032).
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
# Port de gestion de la gateway : santé d'Actuator depuis la phase 6, lot 6B.
management_port=${GATEWAY_MANAGEMENT_PORT:-19464}

# Refus si le client est déjà actif : exécution précédente non refermée ou autre exécution en cours.
python3 "$root/scripts/k3s-demo-client.py" enable >/dev/null
forward=
cleanup() {
  python3 "$root/scripts/k3s-demo-client.py" disable >/dev/null || echo "Client auditsphere-demo NON désactivé : lancer scripts/k3s-demo-client.py disable." >&2
  [ -z "$forward" ] || kill "$forward" 2>/dev/null || true
  kubectl --namespace data delete pod temporal-cli --ignore-not-found --wait=false >/dev/null
}
trap cleanup EXIT

kubectl --namespace auditsphere port-forward svc/api-gateway "$port:8080" "$management_port:9464" >/dev/null 2>&1 &
forward=$!
disown "$forward"
# Un pod en cours de suppression après un lancement précédent ne doit pas être réutilisé.
kubectl --namespace data delete pod temporal-cli --ignore-not-found >/dev/null
kubectl apply -f "$root/deploy/k3s/tools/temporal-cli.yaml" >/dev/null
kubectl --namespace data wait --for=condition=Ready pod/temporal-cli --timeout=120s >/dev/null
# Un nouveau pod n'est admis par les règles réseau qu'après quelques secondes.
for _ in $(seq 30); do
  kubectl --namespace data exec temporal-cli -- temporal operator cluster health >/dev/null 2>&1 && break
  sleep 2
done
for _ in $(seq 30); do
  curl -fsS "http://127.0.0.1:$management_port/actuator/health" >/dev/null 2>&1 && break
  sleep 1
done

AUDITSPHERE_TARGET=k3s AUDITSPHERE_API_BASE="http://127.0.0.1:$port" AUDITSPHERE_OIDC_BASE="$OIDC_ORIGIN" \
AUDITSPHERE_HEALTH_BASE="http://127.0.0.1:$management_port" \
AUDITSPHERE_ACCOUNTS_FILE="$accounts" AUDITSPHERE_WAIT=300 APP_ORIGIN="$APP_ORIGIN" \
SEED_API_BASE="http://127.0.0.1:$port" SEED_TOKEN_URL="$OIDC_ORIGIN/realms/auditsphere/protocol/openid-connect/token" SEED_ACCOUNTS_FILE="$accounts" \
REMEDIATION_SYNC_CLIENT_SECRET=$(kubectl --namespace auditsphere get secret app-secrets -o jsonpath='{.data.REMEDIATION_SYNC_CLIENT_SECRET}' | base64 -d) \
  python3 "$root/scripts/$1" "${@:2}"
