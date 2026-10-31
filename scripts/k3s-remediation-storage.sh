#!/usr/bin/env bash
# Bucket Garage des justificatifs d'actions correctives pour k3s (F-006 lot 5, HOST-013). Idempotent, aucun secret affiché.
# Garage tourne dans Docker sur l'hôte, hors du cluster : bucket et clé sont créés par SSH, comme la clé de sauvegarde.
# La clé n'a que la lecture et l'écriture sur ce bucket. Le secret auditsphere/remediation-s3 la transmet à remediation-service.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
env_file="$root/.local/k3s.env"
[ -f "$env_file" ] || { echo "Fichier absent : .local/k3s.env (GARAGE_SSH)." >&2; exit 1; }
# shellcheck disable=SC1090
. "$env_file"
: "${GARAGE_SSH:?GARAGE_SSH absent de .local/k3s.env}"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
umask 077
bucket=auditsphere-remediation-k3s
key=auditsphere-remediation-k3s

if kubectl --namespace auditsphere get secret remediation-s3 >/dev/null 2>&1; then
  echo "Secret auditsphere/remediation-s3 déjà présent, aucun changement." >&2
  exit 0
fi
# Sortie distante : deux lignes, identifiant puis secret, lues sans être affichées.
remote=$(ssh "$GARAGE_SSH" sh -s -- "$bucket" "$key" <<'REMOTE'
set -eu
id=$(docker ps -q --filter label=com.docker.compose.project=auditsphere-storage --filter label=com.docker.compose.service=garage)
[ -n "$id" ] || { echo "Conteneur Garage introuvable." >&2; exit 1; }
garage() { docker exec "$id" /garage "$@"; }
garage bucket info "$1" >/dev/null 2>&1 || garage bucket create "$1" >/dev/null
garage key info "$2" >/dev/null 2>&1 || garage key create "$2" >/dev/null
garage bucket allow --read --write "$1" --key "$2" >/dev/null
garage key info --show-secret "$2" | awk -F': *' '/^Key ID/{print $2} /^Secret key/{print $2}'
REMOTE
)
access=$(printf '%s\n' "$remote" | sed -n 1p)
secret=$(printf '%s\n' "$remote" | sed -n 2p)
unset remote
[[ "$access" == GK* && ${#secret} -ge 32 ]] || { echo "Clé Garage illisible, aucun secret créé." >&2; exit 1; }
printf 'REMEDIATION_S3_ACCESS_KEY=%s\nREMEDIATION_S3_SECRET_KEY=%s\n' "$access" "$secret" \
  | kubectl --namespace auditsphere create secret generic remediation-s3 --from-env-file=/dev/stdin >/dev/null
unset access secret
echo "Secret auditsphere/remediation-s3 créé, clé en lecture et écriture sur $bucket seulement."
