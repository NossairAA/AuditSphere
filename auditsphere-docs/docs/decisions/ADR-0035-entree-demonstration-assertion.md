# ADR-0035 : entrée de démonstration par assertion signée

**Statut :** Accepté le 2026-10-28, implémenté et vérifié dans le cluster  
**Date :** 2026-10-28  
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0031](ADR-0031-profils-demonstration-production.md) prévoit, dans le profil `demo`, une entrée en un clic par rôle : auditeur, validateur et audité. Trois contraintes encadrent ce choix :

- [ADR-0032](ADR-0032-client-demonstration-bascule.md) : aucun mot de passe direct. Le client `auditsphere-demo` reste désactivé au repos.
- [ADR-0034](ADR-0034-console-compte-fermee.md) : qui connaît le mot de passe d'un compte peut le changer par `kc_action`. Le visiteur ne doit donc jamais voir ce mot de passe, et la page ne doit jamais le saisir à sa place.
- Les comptes sont partagés. Un visiteur ne doit pas pouvoir en priver les suivants, ni par un mot de passe, ni par un OTP, ni par un verrouillage de force brute.

[ADR-0033](ADR-0033-origine-publique-unique.md) fixe l'émetteur public des jetons, que le BFF et la gateway vérifient.

Keycloak 26.8 tourne dans le cluster. Inventaire relevé le 2026-10-28 par l'API d'administration : aucun authentificateur intégré n'identifie un compte sans formulaire. La fonction `JWT_AUTHORIZATION_GRANT`, RFC 7523, est active par défaut et prise en charge.

## Options évaluées

1. Flux de connexion dédié à un client, avec le formulaire `Username Form`, `login_hint` et un refus des comptes hors démonstration. Avantages : flux du navigateur, sans code. Inconvénients : deux clics sur une page de Keycloak, nom modifiable. Le visiteur obtient une session Keycloak : `kc_action` relance ce même flux sans mot de passe, donc il peut changer le mot de passe, le profil ou ajouter un OTP. Le formulaire respecte aussi le verrouillage de force brute : quelques mots de passe faux sur la page normale bloquent l'entrée de tous.
2. Authentificateur personnalisé, une extension Java qui lit `login_hint`. Avantages : un clic. Inconvénients : image de Keycloak à construire et à maintenir, mêmes failles de `kc_action` que l'option 1.
3. Échange de jeton avec usurpation d'identité. Inconvénients : version 1 en préversion, déjà écartée par ADR-0032. La version 2 standard ne change pas d'utilisateur.
4. Fournisseur d'identité dont le BFF serait le serveur OIDC. Avantages : flux du navigateur. Inconvénients : le BFF devient un fournisseur d'identité complet, et `kc_action` reste ouvert comme dans l'option 1.
5. Octroi par assertion JWT, RFC 7523. Le BFF signe une assertion courte pour le compte choisi. Keycloak la vérifie avec la clé publique d'un fournisseur `demo-entry` de type `jwt-authorization-grant`, retrouve le compte par son lien à ce fournisseur et rend un jeton d'accès. Avantages : un clic, aucun mot de passe. Aucune session Keycloak dans le navigateur, donc `kc_action` est hors d'atteinte. Le verrouillage de force brute ne s'applique pas à cet octroi. Seuls les comptes liés sont accessibles. Inconvénients : pas de jeton de rafraîchissement, le BFF signe une nouvelle assertion toutes les 5 minutes. Une clé de signature vit dans le cluster.

## Décision

Retenir l'option 5.

- `scripts/k3s-setup-keycloak.py`, profil `demo` : clé ES256 créée une fois dans le secret `web-demo-entry`, fournisseur `demo-entry` avec la clé publique, l'émetteur `APP_ORIGIN/auth/demo`, l'algorithme ES256, des assertions à usage unique et 60 secondes au plus. Liens de `manager`, `auditor`, `validator` et `auditee` à ce fournisseur. Octroi autorisé au seul client `auditsphere-web`.
- BFF : route `POST /auth/demo`, refusée sans l'en-tête `Origin` de l'application. Elle signe l'assertion, appelle le point de jeton avec le secret du client, puis enregistre la session chiffrée comme après une connexion normale. La session garde le compte choisi et le renouvelle par une nouvelle assertion. La déconnexion d'une session de démonstration ne passe pas par Keycloak.
- Boutons et bandeau affichés par la variable `DEMO_ENTRY`, posée par la valeur `demo.enabled` du chart web. Les boutons restent masqués tant que la clé manque.
- Profil `production` : `k3s-setup-keycloak.py` retire le fournisseur, l'octroi du client, le compte `demo-reset` et les secrets de démonstration. `k3s-deploy-services.sh` désinstalle la réinitialisation nocturne.
- Pas d'OTP pour `manager`, `auditor`, `validator` et `auditee` : l'octroi ne le demande pas. `superadmin` n'est lié à aucun fournisseur et garde l'OTP obligatoire.

Réinitialisation nocturne, chart `infra/helm/demo-reset`, à 5 h, heure de Paris, après la sauvegarde :

1. Comptes, par le compte de service `demo-reset` limité aux utilisateurs du royaume : mot de passe rétabli depuis le secret `demo-accounts`, nom, prénom et adresse, actions requises vidées, OTP, WebAuthn et codes de récupération retirés, sessions fermées, verrouillage levé. `superadmin` n'est jamais touché.
2. Workflows Temporal en cours arrêtés, avec un certificat client dédié.
3. Missions, notifications et suivis de remédiation supprimés par les rôles propriétaires des bases. Les journaux en ajout seul sont conservés, comme dans `scripts/cleanup-test-data.py`.
4. Preuves retirées du stockage, puis 40 missions recréées par `scripts/seed-demo-missions.py`, client `auditsphere-demo` actif pendant cette seule étape.

Le Job n'a aucun accès à l'API Kubernetes et n'exécute rien dans un autre pod. Ses NetworkPolicy ne l'admettent que vers Keycloak, la gateway, PostgreSQL et Temporal.

## Conséquences et risques

- Ce n'est pas le flux par code d'autorisation : la connexion de démonstration passe par le point de jeton. Les jetons ont le même émetteur, la même audience et la même vérification dans le BFF et la gateway.
- La clé `web-demo-entry` permet d'entrer dans les quatre comptes liés, et dans aucun autre. Sa fuite ne donne rien de plus que les boutons publics. La remplacer : supprimer le secret, relancer `k3s-setup-keycloak.py`, puis redémarrer le BFF.
- Le compte `demo-reset` porte `manage-users` : il pourrait modifier tout compte du royaume, y compris `superadmin`. Son secret reste dans le cluster. Les permissions fines de Keycloak pourraient le limiter aux comptes de démonstration plus tard.
- Les mots de passe de démonstration sont copiés dans le secret `demo-accounts` pour que la réinitialisation les rétablisse. Ils ne quittent jamais le cluster ni le poste.
- La réinitialisation n'arrête pas les services. Un événement Kafka en vol pendant la suppression peut recréer une notification orpheline, invisible jusqu'à la nuit suivante.
- Pendant environ 5 minutes chaque nuit, `auditsphere-demo` accepte le mot de passe direct, comme pendant `k3s-check.sh`. Le Job échoue sans nouvel essai si le client est déjà actif.
- Le profil des comptes reste modifiable par l'administration des comptes de `superadmin`. La réinitialisation le rétablit chaque nuit.

- Révisé le 2026-10-28 par l'utilisateur : le responsable `manager` rejoint l'entrée en un clic. C'est le seul rôle qui crée une mission, l'affecte et la soumet au validateur. Sans lui, le visiteur ne voit pas le début du parcours, et les missions en revue s'épuisent dans la journée. `AUDIT_MANAGER` n'a aucun droit d'administration : `/admin`, la dérogation et le journal d'actions restent réservés à `SUPER_ADMIN`. En contrepartie, un visiteur peut supprimer les brouillons, retirer ou clôturer des missions et en créer jusqu'à la réinitialisation suivante. `outsider` reste hors de l'entrée.

## Références

- [ADR-0031 : profils de démonstration et de production](ADR-0031-profils-demonstration-production.md)
- [ADR-0032 : client de démonstration désactivé au repos](ADR-0032-client-demonstration-bascule.md)
- [ADR-0033 : origine publique unique et administration par le tailnet](ADR-0033-origine-publique-unique.md)
- [ADR-0034 : console de compte de Keycloak fermée](ADR-0034-console-compte-fermee.md)
- [Guide d'hébergement k3s](../technique/28-hebergement-k3s.md)
- [Keycloak : JWT Authorization Grant](https://www.keycloak.org/securing-apps/jwt-authorization-grant)
- [RFC 7523 : profil JWT pour les octrois OAuth 2.0](https://datatracker.ietf.org/doc/html/rfc7523)
