# Retrait de soumission et suppression de brouillon

## Parcours

Le responsable propriétaire peut supprimer une mission encore en brouillon. Le formulaire exige une confirmation et un motif non blanc de 2 000 caractères maximum. La suppression retire la mission, ses affectations et ses événements d’affectation encore présents dans l’outbox. Une trace minimale reste dans `mission_action_log` avec identifiant de mission, acteur, motif, version et date.

Une mission en attente de validation peut être retirée par son responsable propriétaire. Elle revient en cours et affiche la date et le motif du dernier retrait. Une nouvelle soumission crée un nouveau cycle de validation. Les missions approuvées et clôturées ne peuvent être ni retirées ni supprimées par ces actions.

Le rôle responsable seul ne donne pas accès aux missions des autres responsables. Un auditeur ou le validateur désigné ne peut pas exécuter ces actions.

## Cohérence et Temporal

Le verrou de ligne et la version protègent la concurrence entre retrait et décision du validateur. Une seule opération gagne, l’autre reçoit un conflit 409. Le journal, le changement de statut et la décision `WITHDRAWN` dans l’outbox Temporal sont enregistrés dans la même transaction.

Temporal termine le cycle avec le résultat `WITHDRAWN`. Son état technique est Completed, le résultat métier indique le retrait. Il peut rester Running tant que le worker ou Temporal est indisponible. L’outbox permet la reprise. Il ne faut pas annuler manuellement le workflow dans la console pour modifier la mission.

Le journal accepte uniquement lecture et insertion pour le compte applicatif. Il n’a pas de clé étrangère vers la mission, afin de survivre à sa suppression. Ce journal ciblé ne constitue pas encore un historique complet de toutes les modifications métier.

## API et limites

- `DELETE /missions/{id}` avec version et motif, réponse 204.
- `PUT /missions/{id}/withdrawal` avec version et motif, réponse mission mise à jour.
- BFF authentifié, contrôle CSRF, rôle, taille de corps et limites de débit, y compris pour DELETE.
- Les notifications déjà publiées restent historiques. Une notification vers un brouillon supprimé ne donne plus accès à la mission.
- Aucun événement Kafka de retrait ni notification spécifique de retrait dans cette tranche.
- Aucun mécanisme de restauration d’un brouillon supprimé dans l’interface.

## Déploiement

Sauvegarder les bases avec `scripts/harden-databases.py`, arrêter audit-service et workflow-worker, appliquer V9 avec le compte migrateur et réappliquer les droits limités. Déployer le worker comprenant `WITHDRAWN` avec l’API avant de proposer le retrait aux utilisateurs.

## Vérification

53 tests Java réussis, incluant permissions, statut, motif, version, concurrence retrait contre approbation, rollback de l’outbox et rejeu Temporal pour les trois résultats possibles. Compilation frontend et 14 tests navigateur réussis. Le scénario réel avec arrêts du worker et de Temporal vérifie trois cycles, dont le retrait, leur reprise et le rejeu sans nouveau workflow. La reprise après changement d’adresse peut prendre plusieurs dizaines de secondes.

Les tests navigateur enregistrent leurs identifiants de mission dans un manifeste privé et déclenchent leur nettoyage à la fin. Le script sauvegarde les bases, arrête temporairement les producteurs, attend la consommation Kafka, supprime uniquement les workflows et données associés aux identifiants enregistrés, puis redémarre les services. Le journal des actions reste conservé. Aucun filtrage global par nom ni remise à zéro des bases. Si le nettoyage échoue, le manifeste reste disponible pour reprise.

Les anciennes données synthétiques des vérifications ont été retirées. Les six missions de démonstration et la mission créée par l’utilisateur ont été préservées.
