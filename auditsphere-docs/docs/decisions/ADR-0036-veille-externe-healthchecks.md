# ADR-0036 : veille externe de la supervision par healthchecks.io

**Statut :** Accepté le 2026-10-30
**Date :** 2026-10-30
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0029](ADR-0029-supervision-prometheus-ntfy.md) place Prometheus et Alertmanager dans le cluster qu'ils surveillent. Il note qu'aucune alerte ne part si le nœud s'arrête, et qu'une sonde externe de veille reste à choisir.

Le 2026-10-30, une coupure de courant a éteint le serveur pendant environ 14 heures. `NodeDown` et `TargetDown` ne pouvaient pas se déclencher. L'utilisateur l'a découvert le matin. La première alerte est arrivée au retour du courant, pour un Job de réinitialisation de la démonstration en échec.

Le principe retenu est un signal de vie : une alerte toujours active, `Watchdog`, qu'Alertmanager envoie à intervalle fixe à un service extérieur. Ce service prévient l'utilisateur quand le signal cesse. Le signal prouve à la fois que le nœud, Prometheus et Alertmanager tournent, et que l'envoi vers Internet fonctionne.

## Options évaluées

1. healthchecks.io, offre gratuite : 20 checks, ping par une adresse secrète, période et délai de grâce réglables, intégration ntfy native. Rien à faire tourner ni à maintenir. Un compte tiers de plus, qui ne voit que l'heure des pings et leur contenu.
2. Worker Cloudflare sur le compte déjà utilisé pour le tunnel : il garde l'heure du dernier signal dans KV et un déclencheur planifié publie sur ntfy quand le signal est trop ancien. Pas de nouveau fournisseur. Du code à écrire, tester, déployer et maintenir, plus un secret partagé.
3. Vérificateur sur le poste de l'utilisateur : Alertmanager publie le signal sur un second sujet ntfy privé, une tâche launchd lit l'heure du dernier message et alerte le sujet principal. Pas de compte. Ne fonctionne que si le poste est allumé et en ligne : une panne de nuit comme celle du 2026-10-30 pourrait encore passer inaperçue.

## Décision

Option 1, choisie par l'utilisateur le 2026-10-30.

- Règle `Watchdog` d'expression `vector(1)`, gravité `none`, dans le groupe `supervision`.
- Route dédiée placée avant la route critique : `group_wait` nul, `group_interval` d'une minute, `repeat_interval` de cinq minutes. `Watchdog` ne part jamais vers ntfy directement.
- Récepteur `heartbeat` : webhook vers l'adresse de ping du check, sans message de résolution. L'adresse contient l'identifiant du check et sert de secret. Elle est conservée dans `.local/healthchecks.env` et dans le secret Kubernetes `alertmanager-heartbeat`, lue par `url_file`, jamais dans Git.
- Check healthchecks.io créé par l'utilisateur : période de 5 minutes, grâce de 10 minutes, intégration ntfy vers le sujet d'alerte existant.

## Conséquences et risques

- Une panne du nœud, de Prometheus, d'Alertmanager ou de l'accès Internet du serveur est signalée en 15 minutes au plus après le dernier ping.
- Le message arrive par healthchecks.io, pas par Alertmanager : il n'a pas le gabarit habituel et sa priorité est réglée dans l'intégration.
- healthchecks.io devient une dépendance. Sa panne peut produire une fausse alerte ou masquer une vraie. ntfy.sh reste aussi une dépendance pour la notification finale.
- healthchecks.io connaît le nom du sujet ntfy, qui sert de secret depuis ADR-0029. Il peut donc lire les alertes, qui ne contiennent ni secret ni donnée d'audit.
- Toute personne qui connaît l'adresse de ping peut simuler un signal de vie et masquer une panne. L'adresse est traitée comme un secret.
- Le signal ne prouve pas que les autres règles fonctionnent : une règle mal écrite reste silencieuse. Il ne prouve pas non plus que l'application répond, ce que font les sondes blackbox.
- Le compte healthchecks.io appartient à l'utilisateur. Sa perte, ou la fin de l'offre gratuite, retire la veille sans bruit côté cluster.

## Références

- [Guide d'hébergement, lot 6A et veille externe](../technique/28-hebergement-k3s.md)
- [ADR-0029](ADR-0029-supervision-prometheus-ntfy.md)
- [API de ping de healthchecks.io](https://healthchecks.io/docs/http_api/), consultée le 2026-10-30
