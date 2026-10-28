# ADR-0034 : console de compte de Keycloak fermée

**Statut :** Accepté le 2026-10-28, implémenté et vérifié dans le cluster  
**Date :** 2026-10-28  
**Auteur :** équipe AuditSphere

## Contexte

Depuis l'ouverture du tunnel ([ADR-0033](ADR-0033-origine-publique-unique.md)), la console de compte `/realms/auditsphere/account/` répond 200 sur le nom public `auth`. Tout compte du royaume `auditsphere` peut y changer son mot de passe, son OTP et son profil.

[ADR-0031](ADR-0031-profils-demonstration-production.md) prévoit des comptes de démonstration partagés, en un clic. Un visiteur pourrait alors changer le mot de passe d'un compte ou lui ajouter un OTP, et en priver les visiteurs suivants.

L'application n'utilise pas cette console. Le BFF ne lit que les jetons, l'audience `auditsphere-api` et les rôles du royaume. La réinitialisation d'un mot de passe passe par audit-service et l'action requise `UPDATE_PASSWORD`.

La console n'est pas la seule porte. Le paramètre `kc_action` du point d'autorisation lance la même action depuis la page de connexion, par le client `auditsphere-web`. Constaté le 2026-10-28 : avec sa session Keycloak, un compte de démonstration obtient le formulaire du mot de passe et celui de l'OTP sans ressaisir son mot de passe.

## Options évaluées

1. Règle personnalisée Cloudflare sur `/realms/auditsphere/account`, créée par l'utilisateur. Avantages : refus en bordure. Inconvénients : ne ferme que le nom public et ne couvre pas `kc_action`. Filtrer `kc_action` en bordure se contourne : paramètre encodé, POST sur le point d'autorisation, objet de requête `request`.
2. Retirer le rôle par défaut `manage-account`. Essayé le 2026-10-28 : sans effet sur `kc_action`.
3. Désactiver le client `account`, qui sert la console et son API REST. Avantages : Keycloak répond 404 sur tous les chemins `/realms/auditsphere/account`, sur les deux noms, appliqué par `scripts/k3s-setup-keycloak.py`. Inconvénients : `kc_action` reste ouvert.
4. Désactiver les actions requises `UPDATE_PASSWORD` et `CONFIGURE_TOTP`. Inconvénients : casse l'inscription de l'OTP de `superadmin` et la réinitialisation par l'administration des comptes.
5. Réglage `max_auth_age` à 0 sur les actions que `kc_action` peut lancer. Keycloak redemande alors le mot de passe avant le formulaire. Les actions requises posées par un administrateur ne sont pas concernées.

## Décision

Retenir les options 3 et 5, appliquées par `scripts/k3s-setup-keycloak.py` :

- clients `account` et `account-console` désactivés. `account-console` est un client public au flux standard, devenu sans usage.
- `max_auth_age` à 0 pour `UPDATE_PASSWORD`, `CONFIGURE_TOTP`, `CONFIGURE_RECOVERY_AUTHN_CODES`, `webauthn-register`, `webauthn-register-passwordless`, `delete_credential` et `UPDATE_PROFILE`.

La règle Cloudflare n'est pas nécessaire. Elle reste possible en ajoutant le chemin à l'expression de la règle `block-keycloak-admin`, sans consommer une deuxième règle. La sonde accepte 403 comme 404.

## Conséquences et risques

- Plus aucune console de compte dans le royaume `auditsphere`, même par le tailnet. Un mot de passe se change par l'administration des comptes ou par un administrateur.
- La console du royaume `master` reste, refusée sur le nom public par la règle `/realms/master`.
- Qui connaît le mot de passe d'un compte peut encore le changer par `kc_action`. Les mots de passe de démonstration restent sur le poste. L'entrée en un clic d'ADR-0031 ne devra jamais révéler ni saisir ce mot de passe à la place du visiteur. La remise à zéro nocturne devrait aussi rétablir les mots de passe et retirer les OTP des comptes de démonstration.
- `scripts/k3s-setup-keycloak.py` redésactive les deux clients à chaque passage. Revenir en arrière demande de retirer ce bloc du script.
- La sonde `blackbox-public-refused` couvre `/realms/auditsphere/account/` et attend 404.

## Références

- [ADR-0031 : profils de démonstration et de production](ADR-0031-profils-demonstration-production.md)
- [ADR-0032 : client de démonstration désactivé au repos](ADR-0032-client-demonstration-bascule.md)
- [ADR-0033 : origine publique unique et administration par le tailnet](ADR-0033-origine-publique-unique.md)
- [Guide d'hébergement k3s](../technique/28-hebergement-k3s.md)
- [Keycloak : actions lancées par l'application](https://www.keycloak.org/docs/latest/server_admin/#con-aia_server_administration_guide)
