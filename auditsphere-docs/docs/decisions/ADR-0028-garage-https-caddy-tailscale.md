# ADR-0028 : Garage en HTTPS par Caddy et un certificat du tailnet

**Statut :** Accepté le 2026-10-26
**Date :** 2026-10-26
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0020](ADR-0020-deploiement-interne-securise.md) exige le chiffrement des flux internes. Garage tourne dans Docker sur l'hôte, hors de k3s. Jusqu'au lot E de la phase 8, son API S3 était publiée en HTTP clair sur l'adresse Tailscale de l'hôte. audit-service, le CronJob de sauvegarde et le poste la joignent par ce nom.

Trois voies ont été examinées :

- Un certificat de l'autorité interne d'[ADR-0026](ADR-0026-autorite-interne-racine-hors-ligne.md). cert-manager ne gère que des secrets du cluster : il faudrait copier le certificat sur l'hôte à chaque renouvellement, soit tous les 60 jours au plus, et faire confiance à la racine sur le poste.
- `tailscale serve` sur l'hôte, choix initial de l'utilisateur. Essayé le 2026-10-26 : HTTPS fonctionne depuis le poste, mais les pods reçoivent `Connection refused`. Le trafic d'un pod vers l'adresse Tailscale de son propre hôte est livré localement par le noyau, sans passer par `tailscaled`, qui ne le voit donc jamais.
- Un proxy Caddy dans le même projet Compose que Garage, publié sur l'adresse Tailscale de l'hôte.

## Décision

Choix de l'utilisateur, le 2026-10-26 : Caddy sur l'hôte.

- Le service `tls` de [deploy/garage/compose.yaml](../../../deploy/garage/compose.yaml) publie le port 3900 sur l'adresse Tailscale de l'hôte et transmet à Garage par le réseau du projet. Garage n'est plus publié sur aucun port.
- Le certificat est celui du nom de l'hôte dans le tailnet, émis par Let's Encrypt. Caddy le demande à Tailscale par le socket de `tailscaled`, monté dans le conteneur, et le renouvelle seul.
- Le nom vient de `GARAGE_TLS_NAME` dans le `.env` privé de l'hôte, jamais du dépôt.
- `tailscale serve` est désactivé sur ce port.

## Conséquences

- Les clients n'ont rien à configurer pour la confiance : la chaîne publique est reconnue par la JVM, rclone, curl et le poste. Le nom du certificat est vérifié comme pour tout site public.
- Le HTTP clair sur 3900 reçoit une erreur 400 de Caddy. Il n'existe plus d'accès en clair à Garage hors du réseau Docker du projet.
- Le socket de `tailscaled` donne la main sur Tailscale. Seule l'image Caddy, épinglée par empreinte, y accède. Un conteneur Caddy compromis pourrait modifier la configuration Tailscale de l'hôte.
- Le nom de l'hôte dans le tailnet figure dans les journaux publics de transparence des certificats. C'est déjà le cas des entrées HTTPS de l'opérateur Tailscale.
- Au redémarrage de l'hôte, Docker peut démarrer le proxy avant que l'adresse Tailscale existe. Le 2026-10-26, le conteneur est resté en marche sans réseau ni port publié. Correctif retenu le 2026-10-27 : `net.ipv4.ip_nonlocal_bind = 1` sur l'hôte, qui permet de publier le port avant l'attribution de l'adresse. Ce réglage vaut pour tout l'hôte.
- Le 2026-10-29, une mise à jour automatique de Tailscale a redémarré `tailscaled`, qui a recréé son socket. Le montage du seul fichier pointait encore vers l'ancien : plus de certificat, poignée de main TLS refusée, sonde de Garage en échec. Correctif du même jour : le dossier `/run/tailscale` est monté à la place du fichier, et un drop-in systemd `RuntimeDirectoryPreserve=yes` empêche sa suppression à l'arrêt de `tailscaled`. L'exposition est inchangée : ce dossier ne contient que le socket. Détails dans l'[hébergement k3s](../technique/28-hebergement-k3s.md).
- La sécurité de Garage repose toujours aussi sur ses clés d'accès S3 et sur le tailnet. Ce choix ne change pas les droits des clés.
