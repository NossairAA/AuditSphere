#!/usr/bin/env bash
# Autorité interne à racine hors ligne (phase 8, lot B, ADR-0026). À lancer par l'utilisateur dans un terminal :
# la phrase secrète de la racine est saisie au clavier et n'est jamais affichée ni enregistrée.
# Crée la racine si elle manque, signe une intermédiaire, la charge dans cert-manager puis supprime sa clé locale.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
dir="$root/.local/ca"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
openssl=${OPENSSL:-/opt/homebrew/bin/openssl}
"$openssl" version | grep -q '^OpenSSL [3-9]' || { echo "OpenSSL 3 ou plus requis (variable OPENSSL)." >&2; exit 1; }

if kubectl --namespace cert-manager get secret internal-ca >/dev/null 2>&1; then
  echo "Le secret cert-manager/internal-ca existe déjà. La rotation de l'intermédiaire est traitée au lot E." >&2
  exit 1
fi

umask 077
mkdir -p "$dir"
chmod 700 "$dir"

if [ ! -f "$dir/root-ca.key" ]; then
  echo "Création de la racine. Choisir une phrase secrète longue et la ranger dans le gestionnaire de mots de passe."
  "$openssl" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -aes256 -out "$dir/root-ca.key"
  echo "Signature du certificat racine, phrase secrète demandée."
  "$openssl" req -x509 -new -key "$dir/root-ca.key" -sha384 -days 3650 \
    -subj "/O=AuditSphere/CN=AuditSphere Root CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash" \
    -out "$dir/root-ca.crt"
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$openssl" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/intermediate.key"
"$openssl" req -new -key "$work/intermediate.key" -subj "/O=AuditSphere/CN=AuditSphere Cluster CA $(date +%Y-%m)" -out "$work/intermediate.csr"
cat > "$work/intermediate.ext" <<'EOF'
basicConstraints=critical,CA:TRUE,pathlen:0
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always
EOF
echo "Signature de l'intermédiaire par la racine, phrase secrète demandée."
"$openssl" x509 -req -in "$work/intermediate.csr" -CA "$dir/root-ca.crt" -CAkey "$dir/root-ca.key" \
  -sha384 -days 730 -extfile "$work/intermediate.ext" -out "$dir/intermediate-ca.crt"
"$openssl" verify -CAfile "$dir/root-ca.crt" "$dir/intermediate-ca.crt" >/dev/null

# tls.crt porte la chaîne complète : les certificats émis reçoivent la racine dans ca.crt.
cat "$dir/intermediate-ca.crt" "$dir/root-ca.crt" > "$work/chain.crt"
kubectl --namespace cert-manager create secret generic internal-ca --type kubernetes.io/tls \
  --from-file=tls.crt="$work/chain.crt" --from-file=tls.key="$work/intermediate.key" \
  --from-file=ca.crt="$dir/root-ca.crt" >/dev/null
kubectl apply -f "$root/deploy/k3s/internal-ca-issuer.yaml" >/dev/null
kubectl wait --for=condition=Ready clusterissuer/auditsphere-internal-ca --timeout=60s >/dev/null
echo "Intermédiaire chargée, ClusterIssuer auditsphere-internal-ca prête. Sa clé n'est plus présente sur ce poste."
echo "Copier aussi .local/ca/root-ca.key sur un support hors ligne : elle est chiffrée par la phrase secrète."
