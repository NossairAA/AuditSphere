set -eu
# Les règles réseau n'admettent un nouveau pod qu'après quelques secondes : attendre au plus 90 s.
for _ in $(seq 30); do temporal operator cluster health >/dev/null 2>&1 && break; sleep 3; done
running() { temporal workflow count --query 'ExecutionStatus="Running"' | sed -n 's/^Total: *//p'; }
count=$(running)
# Seuls les workflows de revue tournent dans l'espace default. Ils visent des missions qui vont disparaître.
if [ "$count" != 0 ]; then
  temporal workflow terminate --query 'ExecutionStatus="Running"' --reason 'Réinitialisation nocturne de la démonstration' --yes
  for _ in $(seq 60); do [ "$(running)" = 0 ] && break; sleep 2; done
  [ "$(running)" = 0 ]
fi
echo "temporal : $count workflows arrêtés"
