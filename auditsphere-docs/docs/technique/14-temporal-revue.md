# Temporal et reprise des validations

**Date :** 2026-10-18
**Statut :** implémenté et vérifié localement

## Démarrer et consulter

Exécuter sh scripts/start-local.sh. Le lanceur prépare Temporal, construit les services, arrête audit-service et le worker pendant la migration, applique les droits SQL puis redémarre les services et la gateway. Le frontend conserve son mode de lancement habituel.

L’interface Temporal est disponible sur http://localhost:8233 dans le namespace default. Le port gRPC 7233 est réservé au réseau Docker. Le volume temporal-data conserve SQLite lors d’un redémarrage. docker compose down conserve les volumes, contrairement à une suppression explicite des volumes.

Créer et démarrer une mission, désigner validator puis soumettre avec manager. Un workflow MissionReviewV1 apparaît avec le préfixe mission-review-. La décision du validateur termine ce workflow avec APPROVED ou REJECTED. Après un refus, une nouvelle soumission crée un autre workflow. Le parcours web et les permissions ne changent pas.

## Traitement durable

V7 ajoute review_cycle_id aux missions et review_workflow_outbox. Elle crée aussi un cycle et un message START pour les missions déjà en attente de validation, sans inventer de workflow pour les anciennes approbations. Les messages ne contiennent ni commentaire ni jeton.

Le publieur est activé par TEMPORAL_ENABLED, avec TEMPORAL_TARGET=temporal:7233 dans Compose. Il réserve un événement à la fois avec un bail de 30 secondes et attend 500 ms entre passages. Les appels RPC ont un délai de 3 secondes et une seule tentative par passage. Les échecs sont reportés avec un délai croissant plafonné à 60 secondes. Les appels Temporal sont hors transaction SQL. Deux threads de planification empêchent ces attentes de bloquer le publieur Kafka.

La réponse API reste immédiate après commit PostgreSQL. Temporal enregistre ensuite le démarrage et le signal. Un worker arrêté peut retarder le traitement du signal, sans perdre la décision acceptée. Au redémarrage, le worker reconstruit son état depuis l’historique. La clôture reste contrôlée par les règles métier existantes dans PostgreSQL, indépendamment du retard de Temporal.

Les propriétés de l’outbox sont visibles avec les colonnes event_type, attempts et published_at. Un message sans published_at reste en attente. Les conflits ou pertes d’historique sont réessayés et journalisés sans exposer de jetons. Le workflow n’est pas accessible directement par l’API publique. L’environnement Docker local est considéré de confiance.

## Vérifier les pannes

python3 scripts/test-temporal.py vérifie deux cycles de revue, un refus puis une approbation, ainsi que la redélivrance des messages sans nouvelle exécution dans la fenêtre de rétention.

python3 scripts/test-temporal.py --faults arrête le worker, enregistre une décision, redémarre Temporal avec le worker arrêté, puis relance le worker et vérifie le résultat. Le script arrête ensuite Temporal, soumet un nouveau cycle et enregistre son approbation, redémarre audit-service, puis vérifie la livraison après reprise. Les services arrêtés par le test sont relancés en sortie. Ces tests créent des missions synthétiques.

Le test de pannes doit finir avant de lancer les tests navigateur. La CI exécute ces étapes séquentiellement. La vérification de disponibilité après redémarrage interroge les missions via la gateway, pas uniquement son endpoint de santé.

## Limites

Le serveur est destiné au développement local. Les historiques terminés sont conservés 24 heures par le namespace local. L’outbox n’est pas purgée automatiquement. Il n’y a pas encore de délai, notification de revue, Activity de mise à jour métier ni reprise après perte de volume. Voir [ADR-0011](../decisions/ADR-0011-temporal-revue-durable.md).

## Résultats locaux

32 tests Java ciblés réussis, dont le rejeu de l’historique Temporal, la redélivrance après clôture du workflow et le rollback en cas d’échec de l’outbox. Les 12 scénarios navigateur passent. Le script de panne avec de vrais jetons Keycloak valide le redémarrage du worker et de Temporal, l’enregistrement de la décision pendant une interruption, le redémarrage d’audit-service et le rejeu sans nouvelle exécution. La migration V7 est appliquée. La CI contient ce scénario, son exécution distante reste à faire après publication.
