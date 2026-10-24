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

## Workflow GitHub Actions implémenté

**Statut :** Implémenté

Le workflow `Build and permissions` (`.github/workflows/ci.yml`) se déclenche à chaque push et pull request. Il enchaîne le scan gitleaks de l'historique, `./mvnw -B verify`, les tests Python de `scripts`, le démarrage de la pile Docker Compose, le smoke API, les scénarios de pannes Kafka et Temporal, le typecheck et le build Next.js, puis les parcours Playwright.

- Runner explicite `ubuntu-26.04`. Le libellé `ubuntu-latest` bascule vers Ubuntu 26 à partir du 19 octobre 2026 et ne doit plus changer d'image sans commit.
- Actions épinglées par SHA complet, avec la version en commentaire : `actions/checkout` v7.0.1, `actions/setup-java` v6.1.0 et `actions/setup-node` v7.1.0. Ces versions s'exécutent sous Node 24. Les v4 précédentes visaient Node 20, déprécié, et setup-java v4 n'est plus maintenu.
- Chaque SHA a été contrôlé auprès du dépôt de l'action avec `git ls-remote` et `gh api`. Les notes de version des majeures intermédiaires ne signalent aucun changement cassant pour notre usage : déclencheurs push et pull_request, cache Maven et npm explicites, distribution Temurin 21, Node 24.
- `scripts/test_image_pins.py` vérifie l'épinglage par SHA, la présence du commentaire de version et l'absence de libellé de runner en `-latest`.
- Pour une mise à jour : relever la dernière version publiée, contrôler `runs.using` dans son `action.yml`, lire les changements cassants, résoudre le SHA du tag puis relancer les tests Python.

## Pipeline CD

Cible retenue par ADR-0023 : k3s à un nœud sur le serveur privé. Voir le [guide d'hébergement](28-hebergement-k3s.md).

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
