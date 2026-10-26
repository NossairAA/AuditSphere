#!/usr/bin/env bash
# Copies hors machine des sauvegardes du cluster (phase 7, ADR-0027), lancées depuis le poste macOS.
#   init     : crée le mot de passe restic dans le trousseau, puis le dépôt restic.
#   run      : déclenche tout de suite le CronJob backup et attend sa fin.
#   pull     : tire le dernier jeu du volume de sauvegarde, vérifie ses empreintes et l'ajoute au dépôt restic.
#   schedule : installe un agent launchd qui lance pull chaque jour.
# Le dépôt vient de RESTIC_REPOSITORY, par défaut ~/AuditSphere-backups/restic, hors du dépôt Git.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/auditsphere.yaml}"
export RESTIC_REPOSITORY="${RESTIC_REPOSITORY:-$HOME/AuditSphere-backups/restic}"
keychain_service=auditsphere-restic
export RESTIC_PASSWORD_COMMAND="security find-generic-password -s $keychain_service -w"
staging="${BACKUP_STAGING:-$HOME/Library/Caches/auditsphere-backup/staging}"
umask 077

case "${1:-}" in
  init)
    command -v restic >/dev/null || { echo "restic absent : brew install restic" >&2; exit 1; }
    if ! security find-generic-password -s "$keychain_service" >/dev/null 2>&1; then
      security add-generic-password -s "$keychain_service" -a "$USER" -w "$(openssl rand -base64 33)"
      echo "Mot de passe restic créé dans le trousseau, service $keychain_service."
    fi
    if restic cat config >/dev/null 2>&1; then
      echo "Dépôt restic déjà initialisé."
    else
      mkdir -p "$(dirname "$RESTIC_REPOSITORY")"
      restic init >/dev/null
      echo "Dépôt restic initialisé."
    fi
    echo "Copier le mot de passe hors du poste : security find-generic-password -s $keychain_service -w"
    ;;
  run)
    job="backup-manual-$(date -u +%Y%m%d%H%M%S)"
    kubectl --namespace backup create job "$job" --from=cronjob/backup >/dev/null
    if ! kubectl --namespace backup wait --for=condition=Complete "job/$job" --timeout=900s >/dev/null; then
      kubectl --namespace backup logs "job/$job" --all-containers >&2 || true
      echo "Job $job en échec." >&2
      exit 1
    fi
    kubectl --namespace backup logs "job/$job" --all-containers
    ;;
  pull)
    command -v restic >/dev/null || { echo "restic absent : brew install restic" >&2; exit 1; }
    cleanup() {
      rm -rf "$staging"
      kubectl --namespace backup delete pod backup-reader --ignore-not-found --wait=false >/dev/null
    }
    trap cleanup EXIT
    rm -rf "$staging"
    mkdir -p "$staging"
    kubectl --namespace backup delete pod backup-reader --ignore-not-found >/dev/null
    kubectl apply -f "$root/deploy/k3s/tools/backup-reader.yaml" >/dev/null
    kubectl --namespace backup wait --for=condition=Ready pod/backup-reader --timeout=120s >/dev/null
    reader() { kubectl --namespace backup exec backup-reader -- "$@"; }
    stamp=$(reader cat /backup/postgres/LATEST | tr -d '[:space:]')
    [[ "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || { echo "Marqueur LATEST illisible." >&2; exit 1; }
    reader tar -C /backup -cf - "postgres/$stamp" garage | tar -xf - -C "$staging"
    mv "$staging/postgres/$stamp" "$staging/postgres/current"
    (cd "$staging/postgres/current" && shasum -a 256 -c SHA256SUMS >/dev/null)
    # Chemin fixe et horodatage du serveur en étiquette : restic déduplique d'un jeu à l'autre.
    (cd "$staging" && restic backup --host auditsphere-k3s --tag "postgres-$stamp" postgres garage)
    restic forget --host auditsphere-k3s --keep-daily 7 --keep-weekly 8 --keep-monthly 12 --prune >/dev/null
    restic check >/dev/null
    echo "Jeu $stamp ajouté au dépôt restic, empreintes vérifiées, dépôt contrôlé."
    ;;
  schedule)
    plist="$HOME/Library/LaunchAgents/com.auditsphere.backup-pull.plist"
    logs="$HOME/Library/Logs/auditsphere-backup.log"
    cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.auditsphere.backup-pull</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>$root/scripts/k3s-backup.sh</string><string>pull</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
  <key>StartCalendarInterval</key><dict><key>Hour</key><integer>9</integer><key>Minute</key><integer>0</integer></dict>
  <key>StandardOutPath</key><string>$logs</string>
  <key>StandardErrorPath</key><string>$logs</string>
</dict>
</plist>
PLIST
    launchctl bootout "gui/$(id -u)" "$plist" 2>/dev/null || true
    launchctl bootstrap "gui/$(id -u)" "$plist"
    echo "Agent launchd installé : pull chaque jour à 9 h, journal dans $logs."
    ;;
  *)
    echo "Usage : $0 init|run|pull|schedule" >&2
    exit 2
    ;;
esac
