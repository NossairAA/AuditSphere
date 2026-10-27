# ADR-0030 : traces et journaux à la demande

**Statut :** Accepté le 2026-10-27
**Date :** 2026-10-27
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0029](ADR-0029-supervision-prometheus-ntfy.md) découpe la phase 6 et place Grafana, Loki et Tempo au lot 6C, démarrés à la demande. La sortie de la phase 6 exige une requête tracée de la gateway au service. Les lots 6A et 6B laissent le nœud avec environ 2 Gio disponibles et les demandes de mémoire des pods à 74 % de la mémoire allouable. Une pile de traces et de journaux permanente n'y tient pas.

Les services n'émettent aucune trace. Tempo ne tournant qu'à la demande, des services qui exporteraient en permanence vers lui échoueraient le reste du temps.

## Options évaluées

1. Export permanent vers un petit collecteur toujours présent, qui jette les traces quand Tempo est arrêté : aucun redémarrage, mais environ 50 Mio de mémoire permanente sur un nœud déjà serré.
2. Export coupé par défaut et activé à la demande : le script de session démarre la pile, puis active l'export dans les services, ce qui les redémarre. L'arrêt de la session fait l'inverse. Aucun coût hors session.

L'utilisateur a choisi l'option 2 le 2026-10-27.

## Décision

- Services Spring : `spring-boot-starter-opentelemetry`. Export coupé par `management.tracing.export.enabled=false`. Chart `spring-service`, option `tracing.enabled` : export activé, échantillonnage à 100 %, point d'accès OTLP de Tempo. Le contexte de trace suit les requêtes de la gateway vers les services et les événements Kafka.
- Session d'observation par un script : installation de Tempo, Loki, Grafana et Alloy dans le namespace `monitoring`, puis activation des traces dans les services. La fin de session désinstalle la pile et coupe l'export.
- Stockage éphémère : les traces et journaux d'une session disparaissent avec elle. Alloy lit les journaux des pods par l'API Kubernetes, ce qui reprend aussi les journaux récents encore conservés par le nœud.
- Grafana n'est pas publié. Il est consulté par `port-forward` depuis le poste.

## Conséquences et risques

- Ouvrir ou fermer une session redémarre les quatre services Spring. Mesure du 2026-10-27 : 19 minutes en série à l'ouverture, 51 secondes en parallèle à la fermeture. À éviter une fois l'application publique, ou à réserver aux moments calmes.
- Hors session, aucune trace n'existe : un incident passé ne peut être tracé qu'en le reproduisant.
- Échantillonnage complet pendant une session : acceptable pour une session courte, coûteux s'il restait actif.
- Journaux limités à ce que le nœud conserve encore. Les journaux plus anciens sont perdus.

## Références

- [Guide d'hébergement, phase 6](../technique/28-hebergement-k3s.md)
- [ADR-0029](ADR-0029-supervision-prometheus-ntfy.md)
