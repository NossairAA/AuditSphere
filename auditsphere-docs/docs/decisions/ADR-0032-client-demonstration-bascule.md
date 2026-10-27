# ADR-0032 : client de démonstration désactivé au repos

**Statut :** Accepté le 2026-10-28, implémenté et vérifié  
**Date :** 2026-10-28  
**Auteur :** équipe AuditSphere

## Contexte

Le client Keycloak `auditsphere-demo` accepte le mot de passe direct : un programme envoie un identifiant et un mot de passe en une seule requête, sans page de connexion ni OTP. Les scripts de vérification et de peuplement du cluster en dépendent : `smoke.py`, `seed-demo-missions.py`, `seed-backup-evidence.py`, `test-notifications.py` et `test-temporal.py`.

La phase 10 prévoit de désactiver ce client avant l'ouverture publique, selon C17. Une fois le nom public de Keycloak ouvert, ce client permettrait à n'importe qui de tenter des mots de passe en boucle. Le désactiver pour de bon casserait pourtant ces scripts.

## Options évaluées

1. Désactivation définitive. Avantages : aucune surface. Inconvénients : smoke et peuplement impossibles sur le cluster.
2. Client confidentiel permanent avec un secret. Avantages : simple. Inconvénients : mot de passe direct ouvert en permanence sur le nom public, contraire à C17.
3. Filtrage par Cloudflare. Inconvénients : l'offre gratuite n'inspecte pas le corps des requêtes et ne distingue pas le client demandé.
4. Échange de jeton avec usurpation d'identité. Inconvénients : fonction encore en préversion dans Keycloak, trop lourde pour ce besoin.
5. Client désactivé au repos, activé le temps d'une exécution. Avantages : aucune surface hors des exécutions, scripts inchangés. Inconvénients : fenêtre ouverte pendant chaque exécution, risque d'oubli.

## Décision

Retenir l'option 5.

- `auditsphere-demo` est désactivé au repos. `scripts/k3s-setup-keycloak.py` le remet désactivé à chaque passage.
- `scripts/k3s-check.sh` l'active avant le script lancé et le désactive à la sortie, y compris après une erreur ou une interruption par Ctrl+C.
- L'activation est refusée si le client est déjà actif : une exécution précédente ne l'a pas refermé, ou une autre tourne. Deux exécutions simultanées ne sont donc pas possibles.
- La bascule passe par `scripts/k3s-demo-client.py` et un compte de service dédié, `k3s-demo-switch`, limité à `view-clients` et `manage-clients` du royaume `auditsphere`. Le client `k3s-provisioner`, administrateur de `master`, n'est plus nécessaire pour cette opération.
- Une sonde blackbox vérifie toutes les 5 minutes que Keycloak répond `invalid_client` pour ce client. L'alerte `DemoClientEnabled` part si le client reste actif plus de 30 minutes.

## Conséquences et risques

- Pendant une exécution, les comptes de démonstration acceptent un mot de passe direct. Aucun n'a de rôle d'administration. Le `superadmin` exige désormais l'OTP, ce qui ferme aussi le mot de passe direct sans code.
- `manage-clients` permet de modifier tous les clients du royaume, dont `auditsphere-web`. Le secret du compte reste dans le cluster, accessible aux seuls administrateurs de Kubernetes. Les permissions fines de Keycloak pourraient le restreindre à un seul client plus tard.
- Chaque sonde laisse un événement `CLIENT_LOGIN_ERROR` dans le journal de Keycloak, soit environ 290 par jour. L'alerte `LoginFailures` ne compte que les événements `login` et n'est pas déclenchée.
- Un arrêt brutal du poste pendant une exécution laisse le client actif. L'alerte le signale au bout de 30 minutes, puis `scripts/k3s-demo-client.py disable` le referme.
- La réinitialisation nocturne envisagée par ADR-0031 ne pourra pas s'appuyer sur ce client sans placer un secret de bascule dans le cluster. Elle sera traitée dans son propre lot.

## Références

- [ADR-0025 : exposition publique par Cloudflare Tunnel](ADR-0025-exposition-publique-cloudflare-tunnel.md)
- [ADR-0031 : profils de démonstration et de production](ADR-0031-profils-demonstration-production.md)
- [ADR-0029 : supervision par Prometheus et alertes par ntfy](ADR-0029-supervision-prometheus-ntfy.md)
- [Guide d'hébergement k3s](../technique/28-hebergement-k3s.md)
