# ADR-0004 : Session web côté BFF

**Date :** 2026-10-16
**Statut :** Implémenté, validation locale

## Contexte

Le deuxième jalon ajoute une interface Next.js au socle Spring. Les jetons d’accès et de renouvellement doivent rester côté serveur et les permissions métier doivent continuer à être imposées par audit-service.

## Décision

Utiliser un client Keycloak confidentiel avec Authorization Code, PKCE S256, state et nonce, via openid-client. Vérifier le jeton d’accès avec jose. Stocker les sessions dans une base PostgreSQL dédiée au BFF avec identifiant opaque en cookie HttpOnly. La clé de recherche en base est le hash de cet identifiant. Verrouiller la ligne pendant le renouvellement pour traiter les requêtes concurrentes.

Pour toute mutation, vérifier l’origine et un jeton CSRF de session. Appliquer SameSite=Lax, Secure en HTTPS et expiration inactive/absolue. Les APIs BFF relayent uniquement les endpoints missions prévus, sans proxy arbitraire ni transmission des jetons au navigateur.

## Conséquences

Une troisième base PostgreSQL locale est nécessaire. Son contenu est sensible, les jetons ne sont pas chiffrés par l’application dans ce jalon. Le frontend fonctionne en processus Node.js sur l’hôte. L’interface garde un état minimal et les permissions restent côté backend. La gestion des affectations par formulaire et le durcissement de production seront livrés séparément.

## Références

- [Guide du frontend](../technique/05-frontend-bff.md)
- [openid-client](https://github.com/panva/openid-client)
- [Cookies Next.js](https://nextjs.org/docs/app/api-reference/functions/cookies)
