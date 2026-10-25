# ADR-0025 : exposition publique par Cloudflare Tunnel

**Statut :** Accepté le 2026-10-25, non implémenté
**Date :** 2026-10-25
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0023](ADR-0023-hebergement-prive-k3s.md) place AuditSphere sur un cluster k3s privé, joignable uniquement par Tailscale. L'utilisateur veut désormais rendre l'application accessible à tout visiteur d'Internet, sous un nom de domaine qu'il possède, enregistré chez Namecheap.

Le serveur est sur un réseau domestique, sans adresse fixe garantie ni alimentation secourue. Le pare-feu de l'hôte refuse toute connexion entrante hors de l'interface Tailscale. L'opérateur Tailscale fournit déjà l'entrée HTTPS privée, vérifiée le 2026-10-25.

L'application gère des données d'audit. Une exposition publique rend joignables la page de connexion, Keycloak et le BFF Next.js par n'importe qui.

## Options évaluées

1. Tailscale Funnel. Avantages : déjà en place, aucun port ouvert. Inconvénients : noms limités à `*.ts.net`, aucun domaine personnalisé, peu de contrôle sur le filtrage.
2. Redirection des ports 80 et 443 de la box vers le serveur, avec Traefik et cert-manager. Avantages : pratique classique de l'exploitation. Inconvénients : adresse IP du domicile publiée dans le DNS, ports entrants ouverts, attaques et déni de service reçus directement, impossible derrière un CGNAT.
3. Cloudflare Tunnel. Un connecteur `cloudflared` dans le cluster ouvre une connexion sortante vers Cloudflare, qui reçoit le trafic public et le relaie. Avantages : aucun port entrant, adresse du domicile masquée, filtrage et limitation de débit gratuits en amont, certificat public géré. Inconvénients : zone DNS du domaine déléguée à Cloudflare, Cloudflare termine le TLS et voit le trafic en clair, dépendance à un fournisseur.
4. Petit serveur virtuel public servant de relais vers le tailnet. Avantages : contrôle complet. Inconvénients : coût récurrent, un serveur public de plus à maintenir et durcir.

## Décision

Retenir l'option 3.

- Le domaine reste enregistré chez Namecheap. Sa zone DNS est déléguée à Cloudflare, offre gratuite, par changement des serveurs de noms. Le tunnel exige cette délégation complète sur l'offre gratuite.
- Un tunnel géré à distance relie Cloudflare au cluster. `cloudflared` tourne dans un Deployment du namespace `cloudflared`, image épinglée par empreinte, mémoire limitée. Son jeton est un secret Kubernetes créé à la main, jamais versionné.
- Deux noms publics seulement : l'application, servie par le frontend Next.js, et l'identité, servie par Keycloak pour les redirections OIDC du navigateur. La gateway, les services Java, Kafka, Temporal et PostgreSQL ne sont jamais publiés.
- La console d'administration de Keycloak reste sur un nom du tailnet. Les chemins `/admin` du nom public sont refusés par une règle Cloudflare et par la configuration de Keycloak.
- Tailscale reste le seul accès d'administration : `kubectl`, Argo CD, Grafana et console Keycloak.
- Côté Cloudflare : proxy actif sur les deux noms, TLS 1.2 minimum, HSTS, règles gérées gratuites du pare-feu applicatif, limitation de débit sur les chemins de connexion.
- Côté application : inscription libre désactivée dans Keycloak, détection de force brute active, MFA obligatoire pour les comptes administrateur et superadministrateur.
- Données de démonstration uniquement. Aucune donnée d'audit réelle n'est chargée sur l'instance publique.

Cet ADR complète ADR-0023 sans le remplacer. La règle « aucun port entrant vers Internet » reste vraie : le tunnel est sortant. La condition d'ADR-0023 reste aussi en vigueur : aucun nom public ne sert l'application avant la vérification des sauvegardes, de l'observabilité et de C7. La délégation DNS et la création du tunnel peuvent être préparées avant.

## Conséquences et risques

- Cloudflare déchiffre le trafic à sa périphérie. C'est acceptable avec des données de démonstration, pas avec des données d'audit réelles sans nouvel ADR.
- Une panne de Cloudflare ou du serveur rend l'application indisponible. Aucun engagement de disponibilité n'est pris.
- Les noms publics apparaissent dans les journaux Certificate Transparency et dans le DNS public. Ils ne révèlent ni le lieu ni le matériel.
- Le changement de serveurs de noms déplace toute la zone DNS du domaine. Les enregistrements existants, par exemple pour la messagerie, doivent être recopiés dans Cloudflare avant la bascule.
- Le jeton du tunnel donne le droit de servir les noms publics. Sa fuite impose sa rotation immédiate dans Cloudflare.
- Le budget mémoire gagne environ 50 Mio pour `cloudflared`, à mesurer.
- Le contenu exposé aux visiteurs anonymes, par exemple un compte de démonstration, reste à décider avant l'ouverture.

## Références

- [ADR-0023 : hébergement privé avec k3s](ADR-0023-hebergement-prive-k3s.md)
- [ADR-0020 : sécurisation du déploiement interne](ADR-0020-deploiement-interne-securise.md)
- [Guide d'hébergement k3s](../technique/28-hebergement-k3s.md)
- [Cloudflare : Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
- [Cloudflare : configuration complète d'une zone](https://developers.cloudflare.com/dns/zone-setups/full-setup/)
- [Keycloak : configuration des noms d'hôte](https://www.keycloak.org/server/hostname)
