# ADR-0009 : Désignation du validateur

**Date :** 2026-10-18
**Statut :** accepté

## Décision

Le responsable propriétaire désigne un seul compte actif disposant du rôle effectif AUDIT_VALIDATOR. Il peut remplacer ou retirer cette désignation avant clôture. Il ne peut pas se désigner lui-même, même avec plusieurs rôles. Une contrainte SQL protège également cette séparation. Cette règle ne prohibe pas encore le cumul auditeur et validateur sur la même mission.

La désignation donne un accès de lecture à la mission si le jeton contient AUDIT_VALIDATOR. Elle ne donne aucun droit de gestion. Le retrait prend effet dès les nouvelles requêtes, sauf si un autre accès subsiste comme auditeur ou propriétaire. Un rôle retiré dans Keycloak peut rester présent jusqu’au renouvellement ou à l’expiration du jeton déjà émis.

La validation du compte Keycloak s’effectue hors transaction SQL. Le propriétaire, la version et l’état sont vérifiés avant puis après cet appel, sous verrou pour la mise à jour. La validation distante ne peut pas être atomique avec PostgreSQL. Le futur workflow devra recontrôler l’éligibilité lors des décisions.

## Limites

La clôture directe reste disponible. Aucune approbation, aucun événement Kafka de désignation et aucun workflow Temporal ne sont ajoutés. Les missions existantes conservent leurs données et commencent sans validateur. Les anciennes contraintes de gestion restent applicables.
