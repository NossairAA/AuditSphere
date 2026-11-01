#!/usr/bin/env bash
# Sécurité de Kafka dans k3s (phase 8, lot D). Idempotent, aucun secret affiché.
#   keystore : mot de passe du magasin de clés du broker, à créer avant le premier démarrage en SASL_SSL.
#   apply    : topics, comptes SCRAM-SHA-512, ACL et secrets des clients dans le namespace auditsphere.
# Les outils passent par l'écoute locale 127.0.0.1:9094 du pod, seule à admettre le principal ANONYMOUS.
set -euo pipefail
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
umask 077
bootstrap=127.0.0.1:9094
bin=/opt/kafka/bin
events=(audit.mission.assigned.v1 audit.mission.review-requested.v1 audit.mission.findings-issued.v1 audit.mission.findings-withdrawn.v1)
# Affectations des actions correctives, publiées par remediation-service (F-006 lot 6).
action_events=(audit.action.assigned.v1)
kafka() { kubectl --namespace data exec -i kafka-0 -c kafka -- "$@"; }
secret_exists() { kubectl --namespace "$1" get secret "$2" >/dev/null 2>&1; }

case "${1:-}" in
  keystore)
    if secret_exists data kafka-keystore-password; then
      echo "Secret data/kafka-keystore-password déjà présent."
    else
      openssl rand -hex 32 | tr -d '\n' \
        | kubectl --namespace data create secret generic kafka-keystore-password --from-file=password=/dev/stdin >/dev/null
      echo "Secret data/kafka-keystore-password créé."
    fi
    ;;
  apply)
    for _ in $(seq 60); do
      kafka "$bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --list >/dev/null 2>&1 && break
      sleep 3
    done
    for topic in "${events[@]}" "${events[@]/%/.DLT}" "${action_events[@]}" "${action_events[@]/%/.DLT}"; do
      kafka "$bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --create --if-not-exists --topic "$topic" \
        --partitions 3 --replication-factor 1 --config retention.ms=604800000 >/dev/null
    done
    echo "Topics : $((${#events[@]} + ${#action_events[@]})) topics métier et leurs DLT."

    # Un compte par service. Le mot de passe existant est réutilisé, sinon il est généré puis rangé
    # dans le secret kafka-<service>, avec la ligne JAAS lue par Spring.
    for user in audit-service notification-service remediation-service; do
      if secret_exists auditsphere "kafka-$user"; then
        password=$(kubectl --namespace auditsphere get secret "kafka-$user" -o jsonpath='{.data.password}' | base64 -d)
      else
        password=$(openssl rand -hex 32)
        dir=$(mktemp -d)
        printf '%s' "$password" > "$dir/password"
        printf 'org.apache.kafka.common.security.scram.ScramLoginModule required username="%s" password="%s";' \
          "$user" "$password" > "$dir/jaas"
        kubectl --namespace auditsphere create secret generic "kafka-$user" --from-file="$dir" >/dev/null
        rm -rf "$dir"
      fi
      # Le mot de passe passe par l'entrée standard. Les erreurs de l'outil ne sont pas affichées :
      # elles recopient la valeur refusée, mot de passe compris.
      if ! printf '%s\n' "$password" | kafka sh -c '
        read -r password
        /opt/kafka/bin/kafka-configs.sh --bootstrap-server 127.0.0.1:9094 --alter --entity-type users \
          --entity-name "$1" --add-config "SCRAM-SHA-512=[iterations=8192,password=$password]" >/dev/null 2>&1' sh "$user"
      then
        echo "Compte SCRAM $user refusé par kafka-configs, détail masqué." >&2
        exit 1
      fi
      unset password
      echo "Compte SCRAM $user à jour."
    done

    acl() {
      kafka "$bin/kafka-acls.sh" --bootstrap-server "$bootstrap" --add --allow-principal "User:$1" "${@:2}" >/dev/null
    }
    # audit-service publie les quatre événements, sans rien lire.
    for topic in "${events[@]}"; do
      acl audit-service --operation Write --operation Describe --topic "$topic"
    done
    # notification-service lit les affectations, celles des actions et les demandes de revue, et met en quarantaine leurs rejets.
    for topic in audit.mission.assigned.v1 audit.mission.review-requested.v1 audit.action.assigned.v1; do
      acl notification-service --operation Read --operation Describe --topic "$topic"
      acl notification-service --operation Write --operation Describe --topic "$topic.DLT"
    done
    acl notification-service --operation Read --group notification-service-v1
    # remediation-service lit les émissions et retraits de constats, met en quarantaine leurs rejets
    # et publie les affectations d'actions depuis son outbox (F-006 lots 3 et 6).
    for topic in audit.mission.findings-issued.v1 audit.mission.findings-withdrawn.v1; do
      acl remediation-service --operation Read --operation Describe --topic "$topic"
      acl remediation-service --operation Write --operation Describe --topic "$topic.DLT"
    done
    for topic in "${action_events[@]}"; do
      acl remediation-service --operation Write --operation Describe --topic "$topic"
    done
    acl remediation-service --operation Read --group remediation-service-v1
    echo "ACL à jour :"
    kafka "$bin/kafka-acls.sh" --bootstrap-server "$bootstrap" --list 2>/dev/null \
      | grep -o 'principal=User:[a-z-]*, host=[^,]*, operation=[A-Z_]*' | sort | uniq -c
    ;;
  *)
    echo "Usage : $0 keystore|apply" >&2
    exit 2
    ;;
esac
