# 07 : DevOps, CI/CD et exploitation

**Statut :** Proposé

## Outillage recommandé

GitHub Actions et GHCR pour la CI et les images, Docker Compose en local, kind, Helm et Argo CD pour l’apprentissage Kubernetes/GitOps. Actuator, Micrometer et OpenTelemetry alimentent Prometheus, Grafana, Loki et Tempo. Voir la [stack de référence](03-stack.md). Ces outils ne sont pas encore configurés.

## Environnements

`local → dev → staging → production`, staging représente au mieux les contraintes de production sans recopier les preuves sensibles.

## Pipeline CI (à chaque PR)

1. Lint, format, compilation, tests unitaires.
2. Tests d'intégration (Testcontainers pour PostgreSQL/Kafka) et tests de contrats.
3. Analyse statique, détection de secrets, audit des dépendances.
4. Construction image Docker, scan de vulnérabilités, SBOM.
5. Publication après validation, signatures et tags immuables.

## Pipeline CD

- GitOps avec Argo CD, charts Helm et overlays par environnement.
- Migrations compatibles ascendantes de base (expand/contract).
- Deployments progressifs, smoke tests, health checks, rollback applicatif.
- Terraform pour les ressources infrastructure nécessaires.
- Gestion des secrets hors Git en clair.

## Observabilité

- OpenTelemetry pour traces distribuées et correlation IDs.
- Prometheus / Grafana pour métriques, Loki pour logs et Tempo/Jaeger pour traces.
- Alertes sur erreurs, saturation, lag Kafka, jobs bloqués, temps de réponse.
- SLO à établir après profilage réel, surveiller p95/p99 et disponibilité des parcours critiques.

## Résilience et performance

- Timeouts, retries bornés avec jitter, circuit breakers et DLQ surveillées.
- Idempotence des commandes et projections reconstruites si possible.
- Backups périodiques et exercices de restauration, avec RPO/RTO explicités.
- Tests de charge (k6), tests de chaos en environnement non productif.
- HPA/KEDA si les mesures montrent un bénéfice, limites CPU/mémoire documentées.

## Déploiement sécurisé en interne

Privilégier réseau privé/VPN/Zero Trust, segmentation, ingress TLS, politique réseau Kubernetes, accès administrateur minimal et intégration SSO. Ne pas rendre les preuves accessibles publiquement.

## Contenu publié dans Git

Le dépôt publie le code, les tests, les configurations nécessaires et la documentation du projet. Les consignes et configurations locales des assistants IA restent exclues du suivi Git et des images Docker. Les fichiers déjà présents dans les anciens commits restent dans l’historique.
