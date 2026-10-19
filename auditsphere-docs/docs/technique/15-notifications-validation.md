# Notifications de demande de validation

**Date :** 2026-10-19
**Statut :** implémenté et vérifié localement

## Parcours

Avec manager, démarrer une mission, désigner validator puis soumettre. Avec validator, ouvrir la cloche dans l’en-tête. La page Votre activité affiche Une mission attend votre validation avec un lien vers la mission. La livraison est asynchrone, le rafraîchissement automatique reste de 30 secondes et le bouton Actualiser est disponible.

Une désignation seule ne produit pas cette notification. Après refus, une nouvelle soumission crée un nouvel événement et une nouvelle notification. Une notification historique reste visible après une décision, elle ne représente pas le statut courant de la mission. Ouvrir la mission pour connaître son état. Les permissions sont vérifiées à chaque consultation.

## Transaction et livraison

La soumission enregistre dans la même transaction PostgreSQL le statut PENDING_REVIEW, le message START de Temporal et l’événement audit.mission.review-requested.v1 dans assignment_outbox. Une erreur d’insertion annule toute la soumission. Le destinataire est le sujet Keycloak du validateur désigné, validé par le backend.

Le publieur Kafka existant choisit le topic à partir du type persisté. Le nouveau topic conserve les mêmes paramètres locaux que les affectations, trois partitions et sept jours de rétention. Temporal et Kafka sont publiés indépendamment après commit. Une panne de l’un n’annule pas la soumission ni ne doit empêcher la reprise de l’autre.

Le consommateur persiste event_type et déduplique par event_id. Un rejeu conserve la notification et sa date de lecture. Le compteur, le marquage comme lue et l’isolation par sujet JWT couvrent les deux types. Aucun titre, commentaire ni jeton n’est transmis dans le message.

Le contrat est contracts/mission-review-requested-v1.schema.json. GET /notifications ajoute eventType, avec les valeurs audit.mission.assigned.v1 et audit.mission.review-requested.v1.

## Migration et vérification

Le lancement habituel sh scripts/start-local.sh prépare les topics et applique audit V8 et notification V3. Les lignes existantes reçoivent le type affectation, sans perte des dates de lecture. Les missions déjà soumises avant cette migration ne reçoivent pas de notification rétroactive.

Les tests Java vérifient la transaction globale, deux soumissions distinctes, le destinataire, le rejeu et la conservation de la lecture. Le parcours navigateur rejoint la mission depuis sa notification avant la décision. python3 scripts/test-temporal.py --faults vérifie la livraison après arrêt du consommateur et le rejeu sans doublon, en plus des pannes Temporal. Ne pas exécuter ces pannes en parallèle des tests navigateur.

## Limites

Le canal reste interne. Aucune relance, échéance, notification de décision ou email n’est ajouté. Les limites de rétention, de débit et de sécurité du Kafka local restent celles du guide notifications. Cette extension réutilise la décision outbox existante de l’ADR-0007, sans changer la responsabilité métier de PostgreSQL décrite dans l’ADR-0011.

## Résultats locaux

44 tests Java de tous les services réussis, 12 scénarios navigateur réussis et compilation Next.js réussie. Le scénario de pannes a validé les deux cycles, la reprise du consommateur et le rejeu sans doublon. Aucun secret détecté par Gitleaks sur les fichiers publiables et par comparaison avec les secrets locaux. Cette extension reste non publiée.
