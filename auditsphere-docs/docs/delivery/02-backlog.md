# 09 : Backlog initial

**Statut :** Proposé, priorités à confirmer.

| ID | Epic / user story | Priorité | État |
| --- | --- | --- | --- |
| US-001 | Comme auditeur, je peux me connecter via SSO. | P0 | Proposé |
| US-002 | Comme responsable, je peux créer et affecter une mission. | P0 | Proposé |
| US-003 | Comme auditeur, je peux lier un contrôle à une mission. | P0 | Proposé |
| US-004 | Comme audité, je dépose une preuve autorisée. | P0 | Proposé |
| US-005 | Comme auditeur, je crée et soumets un constat. | P0 | Proposé |
| US-006 | Comme responsable d'action, je renseigne l'avancement. | P0 | Proposé |
| US-007 | Comme validateur, je clôture un constat après revue. | P0 | Proposé |
| US-008 | Comme responsable, j'exporte un rapport de mission. | P0 | Proposé |
| US-009 | Comme auditeur, je suis notifié d'un retard. | P1 | Proposé |
| US-010 | Comme responsable, je consulte les indicateurs consolidés. | P1 | Proposé |
| US-011 | Comme auditeur, je configure un contrôle récurrent. | P2 | Proposé |
| US-012 | Comme analyste, je compare deux versions de règles sur l'historique. | P2 | Proposé |

Pour chaque user story ajoutée, utiliser [le modèle de fonctionnalité](../templates/fiche-fonctionnalite.md) et renseigner les critères d'acceptation.

## Fondations techniques : ordre de réalisation

| ID | Livrable | Critère d’acceptation | État |
| --- | --- | --- | --- |
| TECH-001 | Stack et convention documentaire | Stack Spring décrite et ADR présents | Vérifié (documentation uniquement) |
| TECH-002 | Monorepo et build Maven | Build local réussi, workflow CI écrit, exécution distante en attente | En cours |
| TECH-003 | Audit service, Flyway et PostgreSQL | Migration sur base vide et test Testcontainers réussi | Vérifié localement |
| TECH-004 | Keycloak, gateway et permissions | Jeton invalide refusé, mission hors périmètre inaccessible | Vérifié localement (API) |
| TECH-005 | Kafka et outbox | Reprise après panne, aucun effet métier doublé après redélivrance | Vérifié localement, jalon notifications |
| TECH-006 | Workflow Temporal | Validation, expiration et reprise après arrêt du worker démontrées | Proposé |
| TECH-007 | Observabilité | Trace corrélée HTTP/Kafka et métriques consultables | Proposé |
| TECH-008 | CI/CD et Kubernetes | Image analysée, déploiement Helm/GitOps et rollback testés | Proposé |

## Avancement fonctionnel du jalon 1

US-001 et US-002 sont **En cours** : authentification API et création/consultation avec affectations démontrées. Connexion web, dates de planification, édition et validation d’existence des affectations restent à réaliser. Voir le [compte rendu technique](../technique/04-premier-jalon.md).

## Jalon 2 : interface locale

| ID | Livrable | État |
| --- | --- | --- |
| WEB-001 | Connexion Keycloak via BFF, sessions, refresh, CSRF et déconnexion | Vérifié localement |
| WEB-002 | Liste des missions filtrée côté backend et pagination | Vérifié localement |
| WEB-003 | Formulaire de création réservé au responsable et fiche mission | Vérifié localement |
| WEB-004 | États vide, refus, erreur et mobile | Vérifié localement |
| WEB-005 | Affectation d’auditeurs depuis l’interface avec annuaire | Vérifié localement |
| WEB-006 | Exécution CI GitHub du jalon frontend | Vérifié sur 69de451, nouvelle tranche Kafka à publier |

US-001 couvre maintenant la connexion web locale. US-002 reste partielle pour les dates métier et la modification des affectations.

## Jalon 3

Affectations web, annuaire, contrôles de propriété et retrait d’accès vérifiés localement. US-002 reste partielle pour les dates métier. La CI distante et les notifications Kafka restent ouvertes. Voir le [jalon affectations](../technique/06-affectations.md).

## Remédiations issues de l’audit

| ID | Action | État |
| --- | --- | --- |
| SEC-01 | Séparer comptes PostgreSQL runtime et migration | À réaliser, P1 |
| SEC-02 | Profil IAM de production, TLS et protection des connexions | À réaliser avant exposition, P1 |
| SEC-03 | Limites de débit, de corps et de concurrence | À réaliser, P1 |
| SEC-04 | Protection des jetons persistés et politique de renouvellement | À réaliser avant données sensibles, P1 |
| SCALE-01 | Transactions courtes sans attente annuaire | À réaliser, P1 |
| SCALE-02 | Validation ciblée et réduction des appels Keycloak | À réaliser, P1 |
| SCALE-03 | Réduire écritures et contention de sessions | À réaliser, P2 |
| SCALE-04 | Mesurer et optimiser les requêtes de liste | À mesurer, P2 |
| SEC-05 | Journal métier, événements IAM et CSP | À réaliser, P2 |
| DATA-01 | Conflit explicite sur formulaire périmé | À réaliser, P2 |
| OPS-01 | CI verte, scans Maven et images, restauration et charge | À réaliser, P2 |

[Preuves, limites et critères de remédiation](../technique/07-audit-securite-scalabilite.md).

## Remédiations livrées dans le jalon de durcissement

SEC-01 est corrigé pour les identifiants effectivement utilisés par les applications. SEC-02 est appliqué pour la protection des connexions, avec profil HTTPS préparé mais déploiement public non vérifié. SEC-03 est appliqué par instance, la limite distribuée reste ouverte. SCALE-01, SCALE-02, SCALE-03 et DATA-01 ont des correctifs vérifiés localement. La purge périodique, la charge durable et le dimensionnement restent ouverts.

SEC-04, SEC-05 et OPS-01 ne sont pas soldés par ce jalon. Voir les [preuves et limites](../technique/08-durcissement.md).

Le dépannage du frontend local est documenté dans le README. Le démarrage automatique après redémarrage du Mac reste hors périmètre.

## Accès local aux données

- DEV-DB-01 : accès pgAdmin aux bases web, audit et keycloak vérifié sur 127.0.0.1, ports 5544, 5545 et 5546. Aucune publication de port PostgreSQL dans le profil production.

Publication du jalon de durcissement terminée, CI verte sur 69de451.

- DEV-REPO-01 : consignes IA exclues des publications futures, documentation fonctionnelle et technique conservée.

## Jalon notifications Kafka

| ID | Livrable | État |
| --- | --- | --- |
| NOTIF-01 | Outbox dans la transaction d’affectation et publication avec bail | Vérifié localement |
| NOTIF-02 | Consommation idempotente et base propriétaire | Vérifié localement |
| NOTIF-03 | Notifications dans le BFF et l’interface, accès depuis l’en-tête et isolation par sujet | Vérifié localement |
| NOTIF-04 | Reprise après panne broker, service et base, rejeu sans doublon | Vérifié localement |
| NOTIF-05 | CI distante de la tranche Kafka | Vérifiée sur 7c15f78 |
| NOTIF-06 | Quarantaine, alertes, rétention et outil de rejeu supervisé | À réaliser |
| NOTIF-07 | TLS, ACL, réplication Kafka et mesures de charge | À réaliser avant production |

Les notifications de retard US-009 restent proposées. Le jalon livré porte uniquement sur les affectations aux missions.

- NOTIF-08 : compteur de non-lues et marquage individuel, implémentés avec isolation par sujet, persistance et protection CSRF. Vérifié localement par tests backend et navigateur ciblés.
