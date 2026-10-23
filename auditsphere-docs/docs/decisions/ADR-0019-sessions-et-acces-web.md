# ADR-0019 : actions de mot de passe et protection du BFF

Date : 2026-10-23
Statut : accepté

## Décision

La création et la réinitialisation demandent à Keycloak d’envoyer l’action `UPDATE_PASSWORD` à l’adresse du compte. AuditSphere ne crée plus et ne retourne plus de mot de passe temporaire. L’adresse email d’un compte existant est immuable dans cette interface afin qu’un administrateur ne puisse pas rediriger l’action vers sa propre boîte. Un changement d’adresse passe par une procédure distincte hors de cette interface.

Le profil local sans SMTP peut activer `USER_ADMIN_LOCAL_EMAIL_FALLBACK` uniquement avec un annuaire HTTP local. Keycloak inscrit alors `UPDATE_PASSWORD` dans les actions requises. Un opérateur doit provisionner l’accès initial dans la console Keycloak locale. Cette procédure ne renvoie jamais le mot de passe par l’API AuditSphere. En production HTTPS, un échec d’envoi laisse l’opération incertaine à réconcilier.

Le BFF chiffre les jetons d’accès et de renouvellement dans `web_sessions` avec AES-256-GCM, une nonce aléatoire par écriture et le hachage de session comme donnée authentifiée. `SESSION_ENCRYPTION_KEY` contient une clé aléatoire de 32 octets en base64. La migration `002-encrypted-sessions.sql` supprime les anciennes sessions en clair avant le redémarrage web. Une ancienne ligne encore présente est aussi invalidée à la lecture. La rotation de la clé invalide les sessions existantes et demande une reconnexion. Le profil localhost peut dériver une clé depuis le secret du client OIDC jusqu’au provisionnement de la clé dédiée.

La CSP utilise une nonce par requête et impose `default-src 'self'`, `frame-ancestors 'none'`, `object-src 'none'` et `base-uri 'self'`. HSTS est envoyé seulement lorsque `APP_ORIGIN` est en HTTPS. Le rendu des pages est dynamique pour que Next.js puisse appliquer la nonce aux scripts.

Les limites de connexion sont séparées par adresse client lorsque le proxy de confiance écrase `x-real-ip` ou `x-forwarded-for`. L’en-tête est ignoré par défaut. Le déploiement doit empêcher un accès direct au BFF avant de configurer `TRUSTED_PROXY_IP_HEADER`. En HTTPS, une configuration manquante ou un en-tête IP invalide produit une réponse 503. Dans l’API Next.js actuelle, l’adresse de la socket n’est pas exposée au gestionnaire de route. Sans proxy de confiance, le profil HTTP local conserve une limite de connexion partagée sous la clé `direct`. Après résolution d’une session, les requêtes sont limitées par son jeton CSRF généré par le serveur.

## Vérifications et limites

Les tests ciblés vérifient l’isolation des limites de connexion par adresse, l’absence de jetons en clair dans le JSON, le retour après déchiffrement et le refus d’un échange ou d’une altération de ligne. Les parcours SMTP réels et la migration des sessions en production restent à vérifier dans l’environnement concerné avant exposition.
