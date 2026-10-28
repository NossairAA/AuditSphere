# ADR-0033 : origine publique unique et administration par le tailnet

**Statut :** Proposé le 2026-10-28, implémenté dans le dépôt, non déployé  
**Date :** 2026-10-28  
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0025](ADR-0025-exposition-publique-cloudflare-tunnel.md) publie l'application et Keycloak sous deux noms publics, par Cloudflare Tunnel. La console de Keycloak doit rester sur le tailnet et ses chemins sont refusés sur le nom public.

Jusqu'ici, tout passe par les noms du tailnet. `APP_ORIGIN` et `OIDC_ORIGIN`, dans `.local/k3s.env`, donnent une seule origine pour le navigateur. Keycloak n'accepte qu'une adresse de retour, celle de `APP_ORIGIN`. L'émetteur des jetons, vérifié par le BFF et par la gateway, découle de `OIDC_ORIGIN`.

La mise en œuvre du tunnel soulève quatre questions :

- quelle origine sert de référence au navigateur et à l'émetteur des jetons
- par où passent les scripts d'administration, qui appellent l'API d'administration et le royaume `master`
- comment garder une limite de débit par visiteur, alors que tout le trafic du tunnel arrive de l'adresse du pod `cloudflared`
- comment le BFF joint l'émetteur public pour la découverte OIDC

## Options évaluées

1. Deux origines actives, tailnet et publique, avec deux adresses de retour. Avantages : l'accès par le tailnet reste complet. Inconvénients : deux émetteurs possibles, or le BFF et la gateway n'en vérifient qu'un. Les cookies et les redirections dépendent du nom utilisé. Configuration plus fragile.
2. Origine publique unique pour le navigateur et l'émetteur, administration par le tailnet. Avantages : un seul émetteur, un seul chemin à tester, celui des visiteurs. Inconvénients : la connexion à l'application dépend de Cloudflare, même depuis le tailnet.
3. Garder le tailnet comme référence et ne publier qu'une copie. Inconvénients : impossible avec un seul émetteur, les visiteurs ne joignent pas le tailnet.

## Décision

Retenir l'option 2.

- `APP_ORIGIN` et `OIDC_ORIGIN` deviennent les noms publics. Keycloak reçoit le nom public dans `KC_HOSTNAME`. L'émetteur des jetons devient public.
- Nouvelle valeur facultative `KEYCLOAK_ADMIN_ORIGIN`, nom `auth` du tailnet. Elle alimente `KC_HOSTNAME_ADMIN` par la valeur `adminOrigin` du chart Keycloak : la console reste sur le tailnet.
- `scripts/k3s-setup-keycloak.py` et `scripts/k3s-demo-client.py` appellent Keycloak par `KEYCLOAK_ADMIN_ORIGIN` quand elle existe, sinon par `OIDC_ORIGIN`. La règle Cloudflare qui refuse `/admin` et `/realms/master` sur le nom public ne les bloque donc pas.
- Les entrées Tailscale `app` et `auth` restent en place. `auth` sert la console. `app` reste utile au diagnostic, mais la connexion renvoie vers le nom public.
- Limite de débit de Traefik par l'en-tête `Cf-Connecting-Ip`, que Cloudflare écrase avec l'adresse réelle du visiteur. Par le tailnet, l'en-tête manque et la limite est commune aux quelques utilisateurs.
- Limite de débit du BFF : elle lit `X-Real-Ip`. Une règle de transformation Cloudflare fixe cet en-tête à l'adresse réelle du visiteur, `ip.src`, et écrase toute valeur envoyée par le client.
- Le BFF joint l'émetteur public par Internet et le tunnel. Les services Spring ne vérifient que la chaîne de l'émetteur et lisent les clés par le service interne de Keycloak.

## Conséquences et risques

- Une panne de Cloudflare ou du tunnel empêche toute connexion à l'application, y compris depuis le tailnet. La console de Keycloak, `kubectl`, Grafana et les sondes internes restent joignables.
- Les appels du BFF à Keycloak sortent du cluster puis reviennent par le tunnel. Ils viennent tous de l'adresse du domicile. La règle de limitation de débit de Cloudflare ne doit donc viser que les formulaires de connexion, jamais le point de jeton. Bot Fight Mode reste désactivé pour ne pas bloquer ces appels.
- Revenir au seul tailnet demande de rétablir les anciennes valeurs dans `.local/k3s.env`, de redéployer, puis de relancer `scripts/k3s-setup-keycloak.py`. Les sessions ouvertes sont perdues.
- Le comportement de `KC_HOSTNAME_ADMIN` avec un jeton du royaume `master` demandé par le nom du tailnet reste à vérifier au déploiement.
- La règle de transformation sur `X-Real-Ip` et sa conservation par Traefik restent à vérifier au déploiement. Sans elle, le BFF voit tous les visiteurs sous une seule adresse et sa limite de connexion devient commune.

## Références

- [ADR-0025 : exposition publique par Cloudflare Tunnel](ADR-0025-exposition-publique-cloudflare-tunnel.md)
- [ADR-0032 : client de démonstration désactivé au repos](ADR-0032-client-demonstration-bascule.md)
- [Guide d'hébergement k3s](../technique/28-hebergement-k3s.md)
- [Keycloak : configuration des noms d'hôte](https://www.keycloak.org/server/hostname)
- [Cloudflare : règles de modification des en-têtes de requête](https://developers.cloudflare.com/rules/transform/request-header-modification/)
- [Cloudflare : en-têtes HTTP ajoutés par Cloudflare](https://developers.cloudflare.com/fundamentals/reference/http-headers/)
