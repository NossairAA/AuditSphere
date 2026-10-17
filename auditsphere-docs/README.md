# AuditSphere : Documentation vivante

> Plateforme interne de gestion des audits, risques, contrôles, preuves et plans d'action, avec trajectoire vers l'audit continu.

**Version documentaire :** 0.8.0
**Statut :** missions, interface et notifications Kafka vérifiées localement / hypothèses métier à valider
**Mise à jour :** 2026-10-17
**Responsables :** à définir
**Langue :** français

## Orientation technique

L’utilisateur confirme un objectif Spring Boot, microservices, Kafka, sécurité, workflows distribués, CI/CD, Kubernetes et observabilité. Voir la [stack de référence](docs/technique/03-stack.md) et [ADR-0002](docs/decisions/ADR-0002-stack-spring-distribuee.md). Le premier socle API est créé et vérifié localement : [guide du jalon](docs/technique/04-premier-jalon.md). Le frontend et son BFF sont implémentés et vérifiés localement : [deuxième jalon](docs/technique/05-frontend-bff.md). Le déploiement distant reste à réaliser.

## Vision

Centraliser et fiabiliser le cycle de vie de l'audit interne, de la planification à la clôture des actions correctives. À terme, AuditSphere pourra automatiser certains contrôles et rejouer des règles sur des données historiques sans modifier les systèmes sources.

## Périmètre retenu pour démarrer

L'hypothèse de travail est **l'audit interne d'entreprise** (finance, achats, RH, opérations) avec extension future vers l'audit IT et la conformité. Ce choix est **à confirmer** avec les utilisateurs métier. Le MVP couvre : gestion des missions, référentiel risques/contrôles, dépôt des preuves, constats, plans d'action et piste d'audit.

## Principes

1. **Valeur métier d'abord** : ne créer un microservice que si sa frontière métier et son autonomie sont justifiées.
2. **Security by design** : SSO/OIDC, séparation des rôles, autorisations par mission et traçabilité.
3. **Propriété des données** : chaque service gère ses données, pas de lecture directe de la base d'un autre service.
4. **Événements fiables** : transactional outbox, consommation idempotente, contrats versionnés.
5. **Évolutivité démontrée** : tests de charge, scénarios de panne, instrumentation et objectifs mesurables.
6. **Documentation comme code** : chaque fonctionnalité livrée met à jour les documents concernés.

## Carte de lecture

| Document | Contenu |
| --- | --- |
| [01 : Vision produit](docs/produit/01-vision.md) | Problème, utilisateurs, valeur, différenciation |
| [02 : Périmètre et exigences](docs/produit/02-exigences.md) | MVP, hors périmètre, règles métier, exigences non fonctionnelles |
| [03 : Parcours et workflows](docs/produit/03-workflows.md) | Cycle de vie d'une mission et d'un constat |
| [04 : Architecture](docs/architecture/01-architecture.md) | Contextes métier, flux, phases de découpage |
| [05 : Données et événements](docs/architecture/02-donnees-evenements.md) | Entités, événements, cohérence et intégrité |
| [06 : Sécurité](docs/technique/01-securite.md) | IAM, RBAC/ABAC, preuves, traçabilité |
| [07 : DevOps et exploitation](docs/technique/02-devops.md) | CI/CD, Kubernetes, observabilité, fiabilité |
| [08 : Roadmap](docs/delivery/01-roadmap.md) | Phases, critères d'acceptation, priorités |
| [09 : Backlog](docs/delivery/02-backlog.md) | Epics et user stories initiales |
| [10 : Journal des changements](CHANGELOG.md) | Évolution de la documentation |
| [Décisions](docs/decisions/README.md) | ADR et arbitrages architecturaux |
| [Modèles](docs/templates/) | Fiches fonctionnalité, ADR, comptes rendus |

## Convention de mise à jour

À chaque étape :

1. Modifier le document de référence, sans dupliquer la définition ailleurs.
2. Ajouter ou préciser les critères d'acceptation et les hypothèses.
3. Enregistrer toute décision architecturale significative dans un ADR.
4. Mettre à jour le backlog, la roadmap et `CHANGELOG.md`.
5. Référencer une issue ou PR dans la description du changement, si disponible.

**États utilisés** : `Proposé` → `Validé` → `En cours` → `Implémenté` → `Vérifié` (ou `Abandonné`).
**Marqueurs** : `À confirmer`, `Hypothèse`, `Décidé`, `Hors MVP`.

## Prochaine décision

Confirmer le type d'audit prioritaire, les profils utilisateurs, le niveau de sensibilité des documents et les contraintes d'hébergement avant de verrouiller l'architecture.

Le [jalon 3](docs/technique/06-affectations.md) ajoute les affectations web et un annuaire Keycloak en lecture seule.

Un [audit de sécurité et scalabilité](docs/technique/07-audit-securite-scalabilite.md) documente les risques avant exposition publique et les limites des mesures locales.

Le [guide de durcissement](docs/technique/08-durcissement.md) décrit les remédiations locales et les limites restant avant production.

Le [jalon notifications Kafka](docs/technique/09-notifications-kafka.md) livre l’outbox, un deuxième service métier et des tests de reprise après panne.

Le [cycle de vie manuel des missions](docs/technique/10-cycle-missions.md) ajoute les dates prévues, le démarrage et la clôture avec permissions et contrôle de version.
