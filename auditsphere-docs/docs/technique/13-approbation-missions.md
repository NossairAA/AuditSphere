# Approbation avant clôture

**Évolution :** la livraison durable à Temporal est désormais décrite dans le [guide suivant](14-temporal-revue.md). Les règles d’approbation ci-dessous restent applicables.

**Date :** 2026-10-18
**Statut :** implémenté et vérifié localement

## Parcours

Le responsable désigne un validateur distinct et démarre la mission. Le bouton Soumettre à validation remplace la clôture directe pour une mission en cours. Le validateur ouvre la mission, saisit un commentaire puis choisit Approuver la mission ou Demander des corrections. Le refus remet la mission en cours et affiche le commentaire. Après correction, le responsable soumet à nouveau. Après approbation, il peut clôturer avec confirmation.

Les rôles cumulés ne permettent pas au responsable d’approuver sa propre mission. Un autre validateur ne peut pas prendre la décision. La règle est imposée côté API et ne dépend pas de l’affichage des boutons.

## API et protections

PUT /missions/{id}/submission reçoit version. La mission doit être en cours, avoir un validateur actif et un propriétaire distinct. PUT /missions/{id}/review reçoit version, decision parmi APPROVED et REJECTED, et comment non vide de 2000 caractères au maximum. La décision exige le rôle AUDIT_VALIDATOR et le sujet désigné. Les conflits de version, répétitions et transitions incompatibles sont refusés avec 409.

PUT /missions/{id}/status avec CLOSED exige désormais APPROVED. Les dates, affectations et désignation ne changent pas pendant la revue ou après approbation. Le BFF conserve session HttpOnly, protections CSRF et origine, contrôle du rôle, taille et débit. Les données de décision viennent du serveur pour le sujet et la date.

## Migration et limites

V6 ajoute les deux états et les champs de soumission et de décision. Elle conserve les missions déjà clôturées. Un déclencheur SQL empêche de passer directement d’un état non approuvé à CLOSED. Les missions nouvelles ou encore en cours doivent suivre la validation.

Seule la décision du cycle courant est conservée. Une nouvelle soumission remet les champs de décision à zéro. Aucun historique complet, notification de revue, Temporal, délai, annulation ou réouverture n’est livré. Voir [ADR-0010](../decisions/ADR-0010-approbation-avant-cloture.md).

Exécuter sh scripts/start-local.sh pour appliquer la migration et reconstruire les services, puis reconstruire et relancer le frontend. Les données restent en place.

## Validation

29 tests Java ciblés réussis et 12 scénarios navigateur réussis. Les contrôles couvrent le refus de clôture directe, les rôles cumulés, le validateur désigné, les commentaires, les versions périmées, le gel des données, le refus puis la nouvelle soumission, l’approbation et la clôture. Le déclencheur SQL est testé avec une tentative directe de clôture sans approbation. La compilation Next.js passe. Les quatre missions précédemment clôturées localement conservent leur état sans décision inventée. Avant publication, la suite complète de 39 tests Java et le contrôle des secrets passent. La CI distante reste à vérifier.
