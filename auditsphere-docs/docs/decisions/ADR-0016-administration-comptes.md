# ADR-0016 : administration applicative des comptes

Date : 2026-10-21
Statut : accepté

## Décision

Étendre le superadmin à la gestion des utilisateurs depuis AuditSphere, en conservant Keycloak comme source des identités. Les appels administratifs passent par Spring et un client confidentiel dédié. Le BFF ne reçoit aucun secret de ce client et ne transmet aucun jeton au navigateur.

Les opérations sont limitées aux profils, activation, quatre rôles applicatifs et mots de passe temporaires. L’interface ne permet ni suppression définitive ni modification de comptes techniques. Un journal SQL durable encadre les appels Keycloak, sans prétendre à une transaction atomique entre les deux systèmes.

## Conséquences

Les changements de compte invalident ses jetons d’audit et ses sessions web. La vérification ajoute une lecture de révocation par requête authentifiée et un appel du BFF au service d’audit. Une indisponibilité de ce service empêche la réutilisation de la session web, plutôt que de réutiliser des privilèges potentiellement obsolètes.

Les opérations incertaines ou interrompues nécessitent une réconciliation opérateur. Les notifications appelées directement conservent la limite de validité des JWT déjà émis. Le secret du client administratif exige la même protection que les autres secrets serveur.
