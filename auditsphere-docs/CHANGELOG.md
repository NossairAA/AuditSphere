# Journal des changements

## [0.4.0] : 2026-10-16

- Interface Next.js, React, TypeScript et Tailwind pour les missions.
- Client Keycloak confidentiel, session BFF PostgreSQL, PKCE, CSRF, refresh et déconnexion.
- Endpoint de liste filtrée et paginée, tests backend et navigateur.
- Configuration locale reproductible et extension du workflow CI.
- ADR-0004 et documentation de démarrage et sécurité enrichies.


## Harmonisation documentaire : 2026-10-16

- Remplacement des tirets cadratins et des points-virgules dans tous les fichiers Markdown.
- Ajout de la règle de rédaction dans AGENTS.md pour les prochaines interventions.


## Publication initiale : 2026-10-16

- Contrôle des secrets avant premier commit, exclusions Git/Docker renforcées.
- Ajout du scan Gitleaks de l’historique dans la CI et documentation du faux positif JDBC.
- Préparation de la publication vers `NossairAA/AuditSphere`.


## [0.3.0] : 2026-10-16

- Initialisation du dépôt Git et du monorepo Maven, création audit-service et api-gateway.
- PostgreSQL/Flyway, Keycloak, permissions par mission et contrat OpenAPI.
- Génération privée de la configuration locale, Compose, Dockerfile et script de démonstration.
- Sept tests d’intégration réussis et parcours réel Keycloak/gateway vérifié localement.
- Workflow CI écrit, non exécuté sur GitHub, frontend non implémenté.
- ADR-0003, guide de démarrage, backlog et roadmap actualisés.

## [0.2.0] : 2026-10-16

- Orientation Spring Boot, microservices, Kafka, workflows distribués et Kubernetes confirmée par l’utilisateur.
- Ajout de la stack de référence et ADR-0002, technologies complémentaires explicitement proposées.
- Alignement architecture, DevOps, roadmap et backlog sur cette orientation.
- Ajout de `AGENTS.md` à la racine pour enrichir la documentation à chaque intervention.
- Aucun code applicatif, test d’intégration ou déploiement effectué.

## [0.1.0] : 2026-10-16

- Création du socle documentaire AuditSphere.
- Définition de l'hypothèse métier initiale et du MVP.
- Première architecture cible, flux, exigences et roadmap.
- Ajout de modèles pour les itérations futures.

## À venir

- Validation des cas d'usage métier avec les futurs utilisateurs.
- Choix du périmètre fonctionnel V1 et des premières métriques SLO.
