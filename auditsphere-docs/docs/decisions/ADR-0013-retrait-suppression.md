# ADR-0013 : Retrait de validation et suppression des brouillons

**État : accepté le 2026-10-20.**

## Contexte

Le responsable doit pouvoir corriger une mission soumise par erreur et retirer un brouillon sans effacer la trace de son action.

## Décision

Autoriser la suppression physique des seuls brouillons du propriétaire, avec motif et journal conservé sans dépendance vers la mission. Autoriser le retrait des seules missions en attente, via une transaction métier enregistrant une décision Temporal `WITHDRAWN` dans l’outbox existante.

Le workflow se termine normalement avec ce résultat métier. Une soumission ultérieure utilise un nouveau cycle. Version et verrou SQL arbitrent les décisions concurrentes. Les missions approuvées et clôturées restent protégées.

## Conséquences

Le journal est limité aux deux nouvelles actions, sans interface de consultation générale. Les notifications historiques sont conservées. Le déploiement coordonne le worker et l’API. La réouverture après approbation et la restauration des brouillons restent hors périmètre.
