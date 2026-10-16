# ADR-0005 : annuaire Keycloak et affectations métier

**Date :** 2026-10-16
**Statut :** Implémenté et testé localement

## Contexte

Le responsable doit choisir des utilisateurs existants sans saisir des identifiants techniques. Le navigateur ne doit pas accéder aux API administratives Keycloak. Les permissions d’une mission appartiennent au service métier.

## Décision

Utiliser un adaptateur annuaire dans audit-service avec un client technique séparé, limité aux droits de lecture view-users et view-realm. Exposer uniquement les noms et identifiants nécessaires, puis valider les affectations dans Spring. Garder la relation mission-utilisateur dans PostgreSQL métier.

Réserver le remplacement transactionnel au propriétaire disposant du rôle AUDIT_MANAGER. Appliquer le contrôle de périmètre à chaque lecture pour que le retrait soit effectif sans attendre l’expiration du JWT. Le BFF conserve ses contrôles de session et CSRF.

## Conséquences

Un secret serveur supplémentaire est nécessaire. La création avec affectations dépend de la disponibilité de l’annuaire. La consultation du contenu d’une mission reste indépendante de la résolution des noms. L’adaptateur pourra être remplacé par un annuaire projeté lorsque les échanges distribués seront introduits. Le verrou garantit une écriture atomique, sans détection des formulaires périmés.

## Références

- [Détail du jalon](../technique/06-affectations.md)
- [API administratives Keycloak](https://www.keycloak.org/docs-api/latest/rest-api/index.html)
