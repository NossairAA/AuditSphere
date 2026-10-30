# ADR-0029 : supervision par Prometheus et alertes par ntfy

**Statut :** Accepté le 2026-10-27
**Date :** 2026-10-27
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0025](ADR-0025-exposition-publique-cloudflare-tunnel.md) et [ADR-0023](ADR-0023-hebergement-prive-k3s.md) interdisent tout nom public avant une supervision vérifiée. La revue d'[ADR-0020](ADR-0020-deploiement-interne-securise.md) du 2026-10-27 laisse ouvert un seul critère : journaux, métriques d'échec et alertes de saturation. La phase 6 du [guide d'hébergement](../technique/28-hebergement-k3s.md) prévoit Prometheus en permanence, Grafana, Loki et Tempo à la demande, et des alertes envoyées au téléphone de l'utilisateur.

Le nœud unique dispose de 5,2 Gio allouables. Le 2026-10-27, les demandes de mémoire des pods atteignent 60 % de ce total et les limites 87 %. L'hôte garde environ 2,3 Gio disponibles. La pile complète, avec opérateur, Grafana, Loki et Tempo en permanence, ne tient pas.

## Options évaluées

1. kube-prometheus-stack, avec l'opérateur Prometheus : ressources `ServiceMonitor` et règles en CRD, tableaux de bord fournis. L'opérateur, ses CRD et les nombreuses règles par défaut coûtent de la mémoire et beaucoup de séries. Trop lourd pour ce nœud.
2. Chart `prometheus` de prometheus-community, sans opérateur : Prometheus, Alertmanager, node-exporter et kube-state-metrics, configuration et règles en clair dans les valeurs Helm. Moins automatisé, mais léger et lisible.
3. Service de supervision hébergé : rien à faire tourner sur le nœud, mais métriques envoyées à un tiers et compte supplémentaire.

Pour le canal d'alerte, l'utilisateur a choisi le 2026-10-27 un sujet ntfy.sh, parmi ntfy, Telegram et courriel.

## Décision

Option 2. Namespace `monitoring`, chart `prometheus` épinglé, pushgateway désactivée. Prometheus garde deux jours de données et collecte toutes les 60 secondes. Le chart `prometheus-blackbox-exporter` sonde la disponibilité de l'application et de Keycloak depuis le cluster.

Alertmanager envoie les alertes à un sujet ntfy.sh par un webhook. Le nom du sujet est aléatoire et sert de secret. Il est généré sur le poste, conservé dans `.local` et dans un secret Kubernetes, jamais dans Git. Aucune interface de supervision n'est publiée : Prometheus et Alertmanager restent internes au cluster, consultés par `port-forward` depuis le poste.

La phase 6 est découpée en trois lots :

- 6A : socle et alertes d'infrastructure, configuration seule.
- 6B : métriques applicatives et alertes d'abus, avec de nouvelles images.
- 6C : Grafana, Loki et Tempo à la demande, et une requête tracée de la gateway au service.

## Conséquences et risques

- Toute personne qui connaît le nom du sujet peut lire les alertes et en publier de fausses. Les alertes ne contiennent ni secret ni donnée d'audit, seulement des noms de composants et des seuils.
- ntfy.sh devient une dépendance pour les alertes. Sa panne ou un message perdu ne bloque pas l'application, mais retarde l'information.
- Si Prometheus, Alertmanager ou le nœud s'arrête, aucune alerte ne part. Une sonde externe de veille reste à choisir. Choisie le 2026-10-30 par [ADR-0036](ADR-0036-veille-externe-healthchecks.md).
- Pas de tableaux de bord permanents : la consultation se fait par l'interface de Prometheus, ou par Grafana démarré à la demande au lot 6C.

## Références

- [Guide d'hébergement, phase 6](../technique/28-hebergement-k3s.md)
- [ADR-0020](ADR-0020-deploiement-interne-securise.md), [ADR-0023](ADR-0023-hebergement-prive-k3s.md), [ADR-0025](ADR-0025-exposition-publique-cloudflare-tunnel.md)
