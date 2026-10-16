# ADR-0003 : Première tranche API sécurisée

**Date :** 2026-10-16  
**Statut :** Implémenté et vérifié localement.

## Contexte

Le premier jalon doit démontrer la création et la consultation d’une mission avec contrôle des permissions avant d’ajouter le frontend et les échanges distribués.

## Décision

Utiliser Spring Boot 3.5.16 et Spring Cloud 2025.0.3, Java 21 et Maven. Deux processus applicatifs : Spring MVC/JPA pour les missions et Spring Cloud Gateway WebFlux pour l’entrée. Deux instances PostgreSQL locales isolent les identités et les données métier. Flyway possède le schéma, Hibernate valide sans le modifier.

Chaque API valide le jeton (issuer, audience, signature, expiration), les autorisations par mission résident dans audit-service. L’identité est le `sub` OIDC. Une mission non autorisée renvoie `404` pour limiter la divulgation. Aucun rôle n’accorde une lecture globale implicite.

La démonstration utilise un client Keycloak local dédié avec grant mot de passe et secrets aléatoires ignorés par Git. Ce client n’est pas un modèle d’authentification de production. Le choix PKCE/BFF du frontend reste ouvert. Les conteneurs ne publient que deux ports sur localhost.

## Conséquences

Le parcours peut être testé sans frontend et prouve une véritable intégration Keycloak/gateway/API/base. Les limites sont documentées dans le [guide du jalon](../technique/04-premier-jalon.md). Les contrats et tests évolueront avec les dates métier, les affectations et la piste d’audit. Aucun déploiement distant ni CI GitHub exécutée à ce stade.
