#!/usr/bin/env bash
# Prépare les identités de sauvegarde (phase 7, ADR-0027). Aucune valeur secrète n'est affichée.
#   postgres : mot de passe du rôle backup, ajouté à postgres-roles et copié dans backup/postgres-backup.
#   garage   : clé Garage en lecture seule sur le bucket des preuves, par SSH vers l'hôte de Garage.
#              GARAGE_SSH, destination SSH, et GARAGE_ENDPOINT viennent de .local/k3s.env.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
bucket=auditsphere-evidence-k3s
umask 077

secret_exists() { kubectl --namespace "$1" get secret "$2" >/dev/null 2>&1; }

case "${1:-}" in
  postgres)
    if secret_exists backup postgres-backup; then
      echo "Secret backup/postgres-backup déjà présent, aucun changement." >&2
      exit 0
    fi
    password=$(openssl rand -hex 32)
    encoded=$(printf '%s' "$password" | base64)
    printf '{"data":{"backup":"%s"}}' "$encoded" \
      | kubectl --namespace data patch secret postgres-roles --type merge --patch-file /dev/stdin >/dev/null
    printf '%s' "$password" \
      | kubectl --namespace backup create secret generic postgres-backup --from-file=password=/dev/stdin >/dev/null
    unset password encoded
    echo "Rôle backup prêt dans les secrets. Relancer le Job postgres-bootstrap pour le créer."
    ;;
  garage)
    env_file="$root/.local/k3s.env"
    # shellcheck disable=SC1090
    . "$env_file"
    : "${GARAGE_SSH:?GARAGE_SSH absent de .local/k3s.env}" "${GARAGE_ENDPOINT:?}"
    if secret_exists backup garage-backup; then
      echo "Secret backup/garage-backup déjà présent, aucun changement." >&2
      exit 0
    fi
    # La clé est créée si besoin, puis limitée à la lecture du seul bucket des preuves.
    # Sortie distante : deux lignes, identifiant puis secret, lues sans être affichées.
    remote=$(ssh "$GARAGE_SSH" sh -s -- "$bucket" <<'REMOTE'
set -eu
id=$(docker ps -q --filter label=com.docker.compose.project=auditsphere-storage --filter label=com.docker.compose.service=garage)
[ -n "$id" ] || { echo "Conteneur Garage introuvable." >&2; exit 1; }
garage() { docker exec "$id" /garage "$@"; }
garage key info auditsphere-backup >/dev/null 2>&1 || garage key create auditsphere-backup >/dev/null
garage bucket allow --read "$1" --key auditsphere-backup >/dev/null
garage key info --show-secret auditsphere-backup | awk -F': *' '/^Key ID/{print $2} /^Secret key/{print $2}'
REMOTE
)
    access=$(printf '%s\n' "$remote" | sed -n 1p)
    secret=$(printf '%s\n' "$remote" | sed -n 2p)
    unset remote
    [[ "$access" == GK* && ${#secret} -ge 32 ]] || { echo "Clé Garage illisible, aucun secret créé." >&2; exit 1; }
    printf 'endpoint=%s\naccess-key=%s\nsecret-key=%s\n' "$GARAGE_ENDPOINT" "$access" "$secret" \
      | kubectl --namespace backup create secret generic garage-backup --from-env-file=/dev/stdin >/dev/null
    unset access secret
    echo "Secret backup/garage-backup créé, clé en lecture seule sur $bucket."
    ;;
  *)
    echo "Usage : $0 postgres|garage" >&2
    exit 2
    ;;
esac
