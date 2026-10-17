# ADR-0008 : Premier cycle de vie manuel des missions

**Date :** 2026-10-17
**Statut :** Accepté pour la première tranche locale

## Contexte

Le parcours cible prévoit plusieurs validations métier. Cette tranche livre uniquement la planification et trois états explicites avant d’introduire Temporal.

## Décision

Conserver la création en DRAFT, avec une paire de dates prévues facultative. Le responsable propriétaire peut enregistrer les dates avant clôture, démarrer vers IN_PROGRESS après planification puis clôturer vers CLOSED. Les transitions inverses, répétées et directes de brouillon vers clôturée sont refusées. Les dates de démarrage et de clôture réels sont enregistrées côté serveur.

Les dates sont calendaires, sans fuseau. Début et fin sont renseignés ensemble, fin égale ou postérieure au début, années de 1 à 9999. Les dates prévues ne déclenchent aucune transition automatique et ne bloquent pas un démarrage anticipé ou une clôture tardive.

Les mutations exigent le rôle responsable, la propriété et une version fraîche. Elles verrouillent la mission dans une transaction courte. Le statut et la planification sont figés à la clôture. La gestion des affectations reste disponible au propriétaire pour administrer les accès, conformément au comportement existant.

## Conséquences

Les brouillons existants et leurs données sont conservés. Le parcours proposé avec revue, rapport et validation humaine reste hors de cette tranche. Aucun événement Kafka de statut ni journal métier complet n’est ajouté ici. Temporal, réouverture et règles de clôture liées aux preuves seront définis séparément.
