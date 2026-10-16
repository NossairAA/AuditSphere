# ADR-0007 : Notifications par Kafka et outbox transactionnelle

**Date :** 2026-10-17
**Statut :** Accepté et implémenté localement

## Contexte

Une affectation doit produire une notification, même si Kafka ou le service destinataire est temporairement indisponible. Les données métier restent la propriété du service audit. La panne du consommateur ne doit pas retenir la transaction métier.

## Décision

Créer notification-service avec une base PostgreSQL dédiée. La création de mission et le remplacement des affectations insèrent un événement par nouvel auditeur dans assignment_outbox, dans la même transaction que la mission. Une affectation inchangée ne produit aucun événement. Un retrait suivi d’une réaffectation produit une nouvelle notification.

Un publisher planifié réclame une ligne avec un bail SQL de 30 secondes. La réservation atomique utilise FOR UPDATE SKIP LOCKED, puis libère la connexion avant l’appel Kafka. Après acquittement du broker, il marque la ligne publiée. En cas d’échec, il augmente le délai de reprise jusqu’à 60 secondes. Après un arrêt du processus, le bail expiré rend la ligne récupérable.

Kafka reçoit une clé missionId et le topic audit.mission.assigned.v1. Le message contient les identifiants, la version métier et la date, sans texte de mission ni jeton. La livraison est au moins une fois. La clé primaire event_id des notifications rend les rejeux sans effet supplémentaire. Le consommateur acquitte après validation de la transaction PostgreSQL.

## Options considérées

- Appel HTTP synchrone au service destinataire : couplage de disponibilité et absence de reprise durable.
- Publication Kafka dans la transaction métier : aucune atomicité commune PostgreSQL et Kafka, attente réseau sous transaction.
- Outbox relayée par polling : retenue pour cette première tranche, explicite et testable sans infrastructure CDC.
- Debezium CDC : option future pour réduire la latence et augmenter le débit.

## Conséquences

Les notifications arrivent avec un délai. Un crash après l’acquittement Kafka et avant le marquage SQL peut republier un message. L’idempotence du consommateur est obligatoire. La version métier est transmise, sans promesse d’ordre strict entre réplicas du publisher, les notifications sont des faits indépendants.

Le broker unique et les trois partitions conviennent au développement local, pas à la haute disponibilité. Kafka reste interne à Docker, sans port hôte, TLS ni ACL pour ce jalon. Les erreurs de consommation sont réessayées sans abandon silencieux. Un message invalide peut bloquer sa partition, une file de quarantaine supervisée et un outil de rejeu restent à livrer.
