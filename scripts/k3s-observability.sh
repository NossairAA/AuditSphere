#!/usr/bin/env bash
# Session d'observation dans k3s (phase 6, lot 6C, ADR-0030) : traces et journaux à la demande.
#   up      : Tempo, Loki, Alloy et Grafana dans monitoring, règles d'entrée, puis traces activées dans les
#             quatre services Spring, ce qui les redémarre.
#   down    : traces coupées, ce qui redémarre les services, puis pile et règles retirées. Données perdues.
#   grafana : Grafana sur http://127.0.0.1:13000 par port-forward, jusqu'à Ctrl-C.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
values="$root/deploy/k3s/observability"
services=(api-gateway audit-service notification-service remediation-service)

repos() {
  helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
  helm repo add grafana-community https://grafana-community.github.io/helm-charts >/dev/null 2>&1 || true
  helm repo update grafana grafana-community >/dev/null
}

install() {
  helm upgrade --install "$1" "$2" --version "$3" --namespace monitoring \
    --values "$values/$1.yaml" "${@:4}" --wait --timeout 10m >/dev/null
  echo "$1 installé"
}

# Les valeurs déjà déployées sont reprises : seule l'option des traces change.
# Les quatre services redémarrent en parallèle : l'un après l'autre, la bascule prenait 19 minutes.
tracing() {
  local pids=() failed=0
  for service in "${services[@]}"; do
    helm upgrade "$service" "$root/infra/helm/spring-service" --namespace auditsphere \
      --reuse-values --set "tracing.enabled=$1" --wait --timeout 10m >/dev/null &
    pids+=("$!")
  done
  for i in "${!services[@]}"; do
    if wait "${pids[$i]}"; then
      echo "${services[$i]} : traces $1"
    else
      echo "${services[$i]} : échec de la bascule des traces" >&2
      failed=1
    fi
  done
  return "$failed"
}

case "${1:-}" in
  up)
    repos
    install tempo grafana-community/tempo 3.1.0
    install loki grafana/loki 7.3.0
    install alloy grafana/alloy 1.13.1
    install grafana grafana-community/grafana 13.5.1 \
      --set-file "dashboards.auditsphere.vue-ensemble.json=$values/dashboards/vue-ensemble.json"
    kubectl apply -f "$values/network-policies.yaml" >/dev/null
    tracing true
    echo "Session ouverte. Grafana : $0 grafana"
    ;;
  down)
    tracing false
    kubectl delete -f "$values/network-policies.yaml" --ignore-not-found >/dev/null
    for release in grafana alloy loki tempo; do
      helm uninstall "$release" --namespace monitoring --wait >/dev/null 2>&1 || true
      echo "$release retiré"
    done
    echo "Session fermée."
    ;;
  grafana)
    kubectl --namespace monitoring port-forward svc/grafana 13000:80
    ;;
  *)
    echo "Usage : $0 up|down|grafana" >&2
    exit 2
    ;;
esac
