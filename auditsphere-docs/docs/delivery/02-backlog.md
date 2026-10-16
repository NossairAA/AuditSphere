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
| TECH-001 | Stack et convention documentaire | Stack Spring décrite, ADR et AGENTS.md présents | Vérifié (documentation uniquement) |
| TECH-002 | Monorepo et build Maven | Build local réussi, workflow CI écrit, exécution distante en attente | En cours |
| TECH-003 | Audit service, Flyway et PostgreSQL | Migration sur base vide et test Testcontainers réussi | Vérifié localement |
| TECH-004 | Keycloak, gateway et permissions | Jeton invalide refusé, mission hors périmètre inaccessible | Vérifié localement (API) |
| TECH-005 | Kafka et outbox | Reprise après panne, aucun effet métier doublé après redélivrance | Proposé |
| TECH-006 | Workflow Temporal | Validation, expiration et reprise après arrêt du worker démontrées | Proposé |
| TECH-007 | Observabilité | Trace corrélée HTTP/Kafka et métriques consultables | Proposé |
| TECH-008 | CI/CD et Kubernetes | Image analysée, déploiement Helm/GitOps et rollback testés | Proposé |

## Avancement fonctionnel du jalon 1

US-001 et US-002 sont **En cours** : authentification API et création/consultation avec affectations démontrées. Connexion web, dates de planification, édition et validation d’existence des affectations restent à réaliser. Voir le [compte rendu technique](../technique/04-premier-jalon.md).
