# Journal des changements

## [0.6.0] : 2026-10-16

- Retrait du suivi Git des consignes locales pour assistants IA, documentation du projet conservée.

- Préparation de la publication des correctifs, vérification CI distante en attente et accès de maintenance pgAdmin documenté.

- Accès pgAdmin aux trois bases sur la boucle locale, ports retirés du profil production et connexions authentifiées vérifiées.

- Comptes runtime PostgreSQL limités, sauvegardes privées et migrations séparées.
- Protection Keycloak des connexions, rotation des refresh tokens et profil HTTPS distinct.
- Quotas BFF et gateway, corps bornés pendant la lecture.
- Annuaire paginé, validations ciblées et appels externes hors transaction métier.
- Sessions sans réécriture systématique et conflits explicites sur les affectations.
- ADR-0006, tests et guide de durcissement ajoutés.

## Audit sécurité et scalabilité : 2026-10-16

- Revue du code et vérification des droits PostgreSQL et paramètres Keycloak actifs.
- Quinze tests backend relancés, audit npm et scan des secrets sans détection.
- Sonde de 90 lectures BFF sans erreur, limites de portée documentées.
- Risques priorisés et plan de remédiation ajoutés, aucun correctif applicatif.

## [0.5.0] : 2026-10-16

- Sélection des auditeurs, modification des affectations et affichage des participants.
- Annuaire Keycloak en lecture seule côté Spring et validation des comptes actifs.
- Contrôles de propriété, retrait immédiat des accès et tests sur deux sessions.
- Préparation privée de l’import Keycloak compatible avec des UID Linux différents.
- Échec du smoke CI précédent constaté, nouvelle validation distante en attente.
- ADR-0005 et guide des affectations ajoutés.

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

## Dépannage du frontend local : 2026-10-16

- Aucun processus n’écoutait sur le port 3000 alors que les services Docker fonctionnaient.
- Frontend compilé relancé en arrière-plan, réponse HTTP 200 vérifiée.
- Instructions de dépannage ajoutées au README.
