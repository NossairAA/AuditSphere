#!/usr/bin/env bash
# Publie la racine hors ligne (ADR-0026) dans la ConfigMap internal-ca des namespaces clients (phase 8, lot C).
# La source est la copie locale de la racine, jamais un certificat lu dans le cluster. Seul le certificat
# public est publié, la clé racine ne quitte pas .local/ca.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
cert="$root/.local/ca/root-ca.crt"
[ -f "$cert" ] || { echo "Racine absente : $cert. Voir scripts/k3s-internal-ca.sh." >&2; exit 1; }
openssl x509 -in "$cert" -noout -checkend 0 >/dev/null || { echo "Racine expirée." >&2; exit 1; }
for namespace in data auditsphere backup; do
  kubectl --namespace "$namespace" create configmap internal-ca --from-file=ca.crt="$cert" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null
  echo "internal-ca à jour dans $namespace."
done
