#!/usr/bin/env bash
# Supervision dans k3s (phase 6, lot 6A, ADR-0029). Aucune valeur secrète n'est affichée.
#   apply : sujet ntfy aléatoire dans .local/ntfy.env s'il est absent, secrets d'alerte et cibles privées,
#           puis deploy/k3s/monitoring/monitoring.yaml. GARAGE_ENDPOINT, APP_ORIGIN et OIDC_ORIGIN viennent de .local/k3s.env.
#   test  : alerte de test envoyée par Alertmanager, puis lecture du sujet pour confirmer sa publication.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
ntfy_env="$root/.local/ntfy.env"
umask 077

load_topic() {
  # shellcheck disable=SC1090
  . "$ntfy_env"
  : "${NTFY_TOPIC:?NTFY_TOPIC absent de .local/ntfy.env}"
}

case "${1:-}" in
  apply)
    # shellcheck disable=SC1090
    . "$root/.local/k3s.env"
    : "${GARAGE_ENDPOINT:?GARAGE_ENDPOINT absent de .local/k3s.env}"
    if [ ! -f "$ntfy_env" ]; then
      printf 'NTFY_TOPIC=auditsphere-%s\n' "$(openssl rand -hex 16)" > "$ntfy_env"
      echo "Sujet ntfy créé dans .local/ntfy.env. S'y abonner dans l'application ntfy, serveur ntfy.sh."
    fi
    load_topic
    kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    # Le gabarit alertmanager de ntfy met en forme le message. Les alertes critiques sont en priorité maximale.
    url="https://ntfy.sh/$NTFY_TOPIC?template=alertmanager"
    kubectl --namespace monitoring create secret generic alertmanager-ntfy \
      --from-file=url=<(printf '%s' "$url") --from-file=url-urgent=<(printf '%s&priority=5' "$url") \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    # Sondes du tunnel (phase 10, lot B) : seulement quand les origines sont les noms publics, sinon listes vides.
    tunnel='[]' refused='[]'
    case "${APP_ORIGIN:-}${OIDC_ORIGIN:-}" in
      *.ts.net*|'') ;;
      *)
        tunnel=$(printf '[{"targets":["%s/","%s/realms/auditsphere/.well-known/openid-configuration"]}]' "$APP_ORIGIN" "$OIDC_ORIGIN")
        refused=$(printf '[{"targets":["%s/admin/","%s/realms/master/"]}]' "$OIDC_ORIGIN" "$OIDC_ORIGIN")
        ;;
    esac
    kubectl --namespace monitoring create secret generic prometheus-private-targets \
      --from-file=garage.json=<(printf '[{"targets":["%s"]}]' "$GARAGE_ENDPOINT") \
      --from-file=tunnel.json=<(printf '%s' "$tunnel") --from-file=public-refused.json=<(printf '%s' "$refused") \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    unset url
    kubectl apply -f "$root/deploy/k3s/monitoring/monitoring.yaml"
    ;;
  test)
    load_topic
    since=$(date +%s)
    kubectl --namespace monitoring exec prometheus-alertmanager-0 -c alertmanager -- \
      amtool --alertmanager.url=http://localhost:9093 alert add MonitoringTest severity=warning \
      --annotation=summary="Alerte de test de la supervision" \
      --annotation=description="Envoyée par scripts/k3s-monitoring.sh test." >/dev/null
    # Alertmanager attend group_wait, 30 secondes, avant le premier envoi.
    for _ in $(seq 12); do
      sleep 10
      if curl -fsS "https://ntfy.sh/$NTFY_TOPIC/json?poll=1&since=$since" | grep -q MonitoringTest; then
        echo "Alerte de test publiée sur le sujet ntfy. Vérifier sa réception sur le téléphone."
        exit 0
      fi
    done
    echo "Alerte de test absente du sujet ntfy après deux minutes." >&2
    exit 1
    ;;
  *)
    echo "Usage : $0 apply|test" >&2
    exit 2
    ;;
esac
