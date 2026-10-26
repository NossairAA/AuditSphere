#!/usr/bin/env bash
# Exercice de restauration (phase 7, ADR-0027) depuis le dépôt restic du poste, sans toucher aux données en service.
# Chaque base est restaurée dans un PostgreSQL vide du namespace isolé restore-test, puis chaque preuve
# enregistrée dans la base audit restaurée est comparée par SHA-256 à la copie restaurée de Garage.
# Usage : scripts/k3s-restore-test.sh [instantané restic, latest par défaut]
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
export RESTIC_REPOSITORY="${RESTIC_REPOSITORY:-$HOME/AuditSphere-backups/restic}"
export RESTIC_PASSWORD_COMMAND="security find-generic-password -s auditsphere-restic -w"
snapshot=${1:-latest}
bucket=auditsphere-evidence-k3s
umask 077
work=$(mktemp -d)
cleanup() {
  rm -rf "$work"
  kubectl delete namespace restore-test --ignore-not-found --wait=false >/dev/null
}
trap cleanup EXIT

started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
restic restore "$snapshot" --host auditsphere-k3s --target "$work" >/dev/null
dumps=$(find "$work" -path '*/postgres/current' -type d | head -1)
mirror=$(find "$work" -path "*/garage/$bucket" -type d | head -1)
[ -n "$dumps" ] && [ -n "$mirror" ] || { echo "Instantané incomplet : dumps ou copie Garage absents." >&2; exit 1; }
(cd "$dumps" && shasum -a 256 -c SHA256SUMS >/dev/null)
echo "Instantané $snapshot restauré sur le poste, empreintes des dumps vérifiées."

kubectl delete namespace restore-test --ignore-not-found >/dev/null
kubectl create namespace restore-test --save-config >/dev/null
openssl rand -hex 32 | kubectl --namespace restore-test create secret generic restore-postgres --from-file=password=/dev/stdin >/dev/null
kubectl apply -f "$root/deploy/k3s/tools/restore-test.yaml" >/dev/null
kubectl --namespace restore-test wait --for=condition=Ready pod/postgres --timeout=180s >/dev/null
pg() { kubectl --namespace restore-test exec -i postgres -- "$@"; }
query() { pg psql -X -At -v ON_ERROR_STOP=1 -U postgres -d "$1" -c "$2"; }

for file in "$dumps"/*.dump; do
  db=$(basename "$file" .dump)
  pg createdb -U postgres "$db"
  # Sans propriétaires ni droits : les rôles applicatifs n'existent pas dans l'instance isolée.
  pg pg_restore -U postgres -d "$db" --no-owner --no-privileges --exit-on-error < "$file"
  tables=$(query "$db" "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')")
  [ "$tables" -gt 0 ] || { echo "$db : aucune table restaurée." >&2; exit 1; }
  echo "$db : restaurée, $tables tables."
done

missions=$(query audit "SELECT count(*) FROM missions")
flyway=$(query audit "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
realm=$(query keycloak "SELECT count(*) FROM realm WHERE name = 'auditsphere'")
namespaces=$(query temporal "SELECT count(*) FROM namespaces")
[ "$missions" -gt 0 ] && [ "$realm" = 1 ] && [ "$namespaces" -gt 0 ] \
  || { echo "Contrôles métier en échec : missions $missions, royaume $realm, namespaces Temporal $namespaces." >&2; exit 1; }
echo "audit : $missions missions, migration $flyway. keycloak : royaume auditsphere présent. temporal : $namespaces namespaces."

# Chaque preuve connue de la base doit exister dans la copie Garage avec la même empreinte.
total=0
while IFS='|' read -r key expected; do
  [ -n "$key" ] || continue
  total=$((total + 1))
  object="$mirror/$key"
  [ -f "$object" ] || { echo "Preuve absente de la copie : $key" >&2; exit 1; }
  actual=$(shasum -a 256 "$object" | cut -d' ' -f1)
  [ "$actual" = "$expected" ] || { echo "Empreinte différente : $key" >&2; exit 1; }
done < <(query audit "SELECT object_key, sha256 FROM control_evidence ORDER BY object_key")
[ "$total" -gt 0 ] || { echo "Aucune preuve dans la base restaurée : contrôle d'empreinte sans objet." >&2; exit 1; }
echo "Preuves : $total sur $total présentes, SHA-256 identiques à la base."
echo "Restauration réussie : début $started, fin $(date -u +%Y-%m-%dT%H:%M:%SZ), instantané $snapshot."
