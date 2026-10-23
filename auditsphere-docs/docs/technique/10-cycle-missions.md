# Dates et cycle de vie des missions

**Évolution :** la clôture directe décrite dans cette tranche est remplacée par [l’approbation obligatoire](13-approbation-missions.md). Les règles de gel pendant la revue et après approbation prévalent également.

**Date :** 2026-10-17
**Statut :** implémenté localement

## Parcours livré

Le formulaire de création accepte deux dates prévues, facultatives en brouillon. La fiche propose un bloc Planification et statut. Le responsable propriétaire enregistre les dates, puis clique Démarrer la mission. Une mission en cours peut être clôturée après confirmation. La liste et la fiche affichent Brouillon, En cours ou Clôturée selon l’état enregistré.

Les auditeurs affectés consultent les dates et le statut sans pouvoir les modifier. Seul un utilisateur avec le rôle AUDIT_MANAGER et propriétaire de la mission peut la planifier et effectuer une transition. Le BFF applique aussi la session, l’origine exacte et le jeton CSRF.

## Règles et API

- POST /missions accepte plannedStartDate et plannedEndDate. Elles sont toutes deux absentes ou toutes deux présentes.
- PUT /missions/{id}/schedule exige version et les deux dates. La fin ne précède pas le début, les années vont de 1 à 9999. Une mission clôturée refuse la modification.
- PUT /missions/{id}/status exige version et IN_PROGRESS ou CLOSED. Seules DRAFT vers IN_PROGRESS et IN_PROGRESS vers CLOSED sont acceptées.
- Le démarrage exige les dates enregistrées. Il est manuel, sans contrôle par rapport à la date du jour.
- startedAt et closedAt sont des instants définis par le serveur. Une répétition de transition est refusée.
- Une version périmée ou une opération incompatible avec l’état renvoie 409. La fiche propose un rechargement, sans réessayer automatiquement avec une nouvelle version.
- La propriété est contrôlée dans la transaction verrouillée. Un autre responsable reçoit 404, un auditeur reçoit 403.

Les affectations sont désormais modifiables seulement en brouillon et en cours. Une mission en revue, approuvée ou clôturée refuse le changement avec 409. La règle plus récente prévaut sur le parcours initial de cette page.

## Données et installation

La migration V4 ajoute les dates prévues, started_at et closed_at. Elle étend la contrainte de statut et vérifie la cohérence des dates et des états en PostgreSQL. Elle conserve les données et les versions des brouillons existants. Exécuter sh scripts/start-local.sh pour appliquer la migration avec le compte migrateur, puis reconstruire ou redémarrer le frontend selon son mode de lancement.

Le contrat HTTP est mis à jour dans contracts/audit-api.yaml. Les règles détaillées sont actées dans [ADR-0008](../decisions/ADR-0008-cycle-mission-manuel.md). Les événements d’affectation Kafka conservent leur contrat et leur comportement. Aucun événement de changement de statut n’est encore produit.

## Vérification

Les tests backend couvrent la planification, les bornes et paires de dates, les transitions successives, les conflits, les refus après clôture et les permissions. Le scénario navigateur crée une mission datée, provoque un conflit, recharge la fiche, démarre et clôture la mission, puis vérifie l’affichage pour un auditeur et dans la liste.

Temporal, validation humaine, annulation et réouverture restent hors périmètre. Le parcours cible plus complet reste décrit dans les workflows produit.

Validation locale : 34 tests Java, dont 24 du service audit, et 11 scénarios navigateur réussis, TypeScript et compilation Next.js validés. Migration V4 appliquée avec les données existantes conservées. Aucun déploiement distant effectué pour ce cycle.
