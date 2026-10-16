# Stack de référence : AuditSphere

**Date :** 2026-10-16  
**Statut :** orientation Spring et systèmes distribués confirmée par l’utilisateur, socle API implémenté, autres composants recommandés. Voir le [jalon vérifié](04-premier-jalon.md).

## Objectif

Livrer un parcours d’audit interne et apprendre à exploiter un système distribué : frontières métier, cohérence, sécurité, résilience, livraison et diagnostic. Les briques sont introduites par étapes mais les services métier sont indépendamment déployables dès leur création.

## Technologies

| Couche | Choix de départ | Utilité |
| --- | --- | --- |
| Backend | Java 21 LTS, Spring Boot, Maven Wrapper | Services métier typés et builds reproductibles |
| API | Spring MVC, Bean Validation, OpenAPI | REST documenté, validation des entrées |
| Accès données | Spring Data JPA / Hibernate, PostgreSQL 17, Flyway | Transactions locales et migrations par service |
| Sécurité | Spring Security OAuth2 Resource Server, Keycloak OIDC | SSO, validation JWT et autorisations par mission |
| Entrée applicative | Spring Cloud Gateway | Routage et politiques d’entrée, chaque service vérifie aussi les accès |
| Événements | Apache Kafka en mode KRaft, Spring for Apache Kafka | Événements métier et consommateurs indépendants |
| Fiabilité événements | Outbox PostgreSQL, relay applicatif, inbox/déduplication | Publication après transaction, livraison au moins une fois |
| Workflows | Temporal Server et SDK Java | Validation humaine, délais, reprises et compensations |
| Interface | Next.js, React, TypeScript, Tailwind CSS | Interface des missions, preuves et plans d’action |
| Preuves | API compatible S3, AWS SDK for Java v2 | Fichiers privés et versionnés, fournisseur à choisir |
| Tests backend | JUnit 5, Mockito, Testcontainers, Awaitility | Règles métier, PostgreSQL/Kafka réels, asynchronisme |
| Tests parcours | Playwright, k6 pour la charge | Parcours autorisés/interdits et performances mesurées |
| Local | Docker Compose et profils de services | Démarrage progressif adapté au PC disponible |
| Kubernetes | kind pour apprentissage local, Helm | Déploiements, probes, ressources, rollouts et secrets |
| CI/CD | GitHub Actions, GHCR, Argo CD | Tests, images et déploiement GitOps, compte/dépôt à créer |
| Sécurité livraison | Gitleaks, Trivy, SBOM | Détection de secrets et vulnérabilités, traçabilité des images |
| Observabilité | Actuator, Micrometer, OpenTelemetry Collector | Santé, métriques, propagation de traces HTTP/Kafka |
| Consultation | Prometheus, Grafana, Loki, Tempo | Métriques, journaux et traces corrélés |

Redis, Elasticsearch, Eureka et Spring Config Server ne sont pas nécessaires au premier parcours. Kubernetes fournit la découverte par DNS, configuration et secrets restent externes aux images. Kafka ne remplace pas Temporal : les événements diffusent les faits, les workflows pilotent les étapes durables.

## Politique de versions

Java 21 et PostgreSQL 17 constituent les bases proposées. À l’initialisation, sélectionner une ligne Spring Boot stable et maintenue avec le release train Spring Cloud compatible, puis figer les versions exactes dans le parent Maven/BOM. Vérifier aussi le SDK Temporal, les clients Kafka et la documentation OpenAPI. Spring Boot 3.5.16 / Spring Cloud 2025.0.3 ont été compilés et testés pour le socle API. Les intégrations Kafka/Temporal restent à vérifier. Utiliser les wrappers, le lockfile frontend et des images versionnées, aucun tag `latest` dans les déploiements reproductibles.

## Découpage et propriété

- `audit-service` : missions, affectations et programmes de test.
- `risk-control-service` : référentiel des risques et contrôles.
- `evidence-service` : métadonnées, versions, stockage privé et statut de contrôle des preuves.
- `findings-actions-service` : constats, recommandations, actions et règles de clôture.
- `workflow-worker` : orchestration Temporal, appels aux API métier, délais et reprises.
- `notification-service` puis `reporting-service` : consommateurs Kafka introduits après le premier parcours.
- `api-gateway` et `web` : accès aux fonctionnalités.

Chaque service métier possède sa base et son compte SQL. Un seul serveur PostgreSQL peut héberger plusieurs bases en local. Keycloak et Temporal ont leurs propres bases. Aucune lecture SQL entre services, contrats API/événements versionnés. Les entités JPA ne sont pas partagées dans une bibliothèque commune.

## Premier scénario distribué

Créer une mission, déclarer un constat et affecter une action. Le service propriétaire écrit son état, sa piste d’audit et son outbox dans une transaction. Le relay publie dans Kafka. Le consommateur déclenche idempotemment un workflow Temporal avec un identifiant stable dérivé de l’action. Une validation autorisée est persistée côté métier puis transmise au workflow, les activités utilisent des clés d’idempotence. En cas de redélivrance ou d’arrêt du worker, ni action ni notification ne doit être doublée. Les compensations sont des actions métier explicites et tracées, pas des annulations SQL globales.

## Organisation envisagée

```text
AuditSphere/
  AGENTS.md
  auditsphere-docs/
  pom.xml                  # parent Maven créé
  services/                # audit-service et api-gateway créés
  apps/web/                # interface Next.js et BFF créés
  contracts/               # contrat OpenAPI des missions
  compose.yaml             # environnement local créé
  infra/helm/              # charts, à créer
  infra/gitops/            # applications Argo CD, à créer
  .github/workflows/       # CI créée, exécution distante non vérifiée
```

Monorepo recommandé pour les premiers développements, avec images et pipelines ciblés par service. Le dépôt Git local est initialisé. Parent Maven, deux services, Compose, contrats et workflow CI existent, aucun remote ni pipeline distant exécuté.

## Hébergement et limites

Le serveur Ubuntu local est un environnement de développement envisagé, pas une production validée. Mesurer RAM, CPU et stockage avant de lancer Kafka, Temporal et l’observabilité ensemble, activer les profils à la demande. Le type d’installation Windows/Ubuntu reste à confirmer. D’abord des données synthétiques. Une sauvegarde hors du disque hôte et une restauration testée sont nécessaires avant toute preuve réelle. Le fournisseur S3, les accès GitHub et les politiques Tailscale restent à choisir/vérifier.

## Références officielles

Consultées le 2026-10-16 :
- [Spring Boot : prérequis](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Cloud : compatibilité des release trains](https://spring.io/projects/spring-cloud/)
- [Temporal : SDK Java](https://docs.temporal.io/develop/java)
- [Keycloak : sécurisation des applications](https://www.keycloak.org/securing-apps/overview)
- [PostgreSQL : cycle de support](https://www.postgresql.org/support/versioning/)

## Versions frontend vérifiées au jalon 2

Les versions exactes sont verrouillées dans [package.json](../../../apps/web/package.json) et package-lock.json. Le frontend utilise Node.js 24 et une base PostgreSQL dédiée à ses sessions. Voir le [guide BFF](05-frontend-bff.md).
