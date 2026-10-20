# Superadministrateur des missions

**État : implémenté et vérifié en local le 2026-10-20.**

## Compte et périmètre

Le rôle de realm Keycloak `SUPER_ADMIN` donne accès à toutes les missions actives de l’application, indépendamment du propriétaire ou des affectations. Le compte local `superadmin`, affiché Alexandre Bernard, utilise la valeur privée `SUPERADMIN_PASSWORD` du fichier `.env`. Les mots de passe existants ne sont pas réinitialisés. `setup-web.py` prépare le rôle dans tous les environnements, mais crée le compte de démonstration uniquement en mode local.

Le rôle est vérifié côté Spring à partir du JWT signé et côté BFF à partir de la session serveur. Le nom de connexion seul n’accorde aucun privilège. Il permet la création, la lecture globale, la planification, les affectations, la désignation du validateur, la soumission, le retrait et les actions ordinaires du responsable sur toutes les missions. Le propriétaire original ne change pas lorsqu’un administrateur intervient.

L’administration des utilisateurs et des rôles reste dans Keycloak. Le rôle applicatif ne donne aucun accès administrateur à Keycloak, PostgreSQL ou au serveur. Les notifications et leur état de lecture restent personnels. Aucun écran de gestion des comptes ni modification globale des notifications dans cette tranche.

## Dérogations explicites

L’utilisateur a demandé de permettre des dérogations aux règles métier, y compris l’approbation de sa propre mission. Ces dérogations utilisent un panneau distinct dans la fiche. Elles exigent un choix d’action, un motif non blanc de 2 000 caractères maximum et une confirmation dans l’interface.

| Action | Statuts autorisés | Effet |
| --- | --- | --- |
| APPROVE | En cours, en attente de validation | Approbation par le superadmin, même propriétaire ou non désigné |
| CLOSE | En cours, en attente de validation, approuvée | Clôture même sans approbation préalable |
| REOPEN | En attente de validation, approuvée, clôturée | Retour en cours, ancienne décision effacée de l’état courant mais conservée dans les traces existantes |
| DELETE | Tous les statuts actifs | Suppression logique, mission masquée pour tous les utilisateurs |

Un brouillon doit être planifié et démarré avant une approbation ou une clôture. Les dérogations ne fabriquent pas de dates de travail. La réouverture d’une mission supprimée n’est pas proposée. Les API ordinaires continuent à refuser l’autoapprobation et la clôture sans approbation, même pour un superadmin. Il doit utiliser l’action de dérogation explicite.

## Trace et cohérence

Le journal enregistre auteur, opération, motif, date, version, statut avant et statut après. L’interface montre la dernière dérogation aux personnes ayant accès à la mission. Le superadmin peut consulter les 100 dernières entrées via la fiche ou `GET /missions/{id}/action-log`, y compris par identifiant après suppression. Les mutations ordinaires du superadmin produisent aussi une entrée ADMIN_CHANGE avec l’opération et la version obtenue.

Le compte SQL applicatif peut insérer et lire le journal, sans le modifier ni le supprimer. Cette protection concerne le compte applicatif, le journal ne constitue pas un stockage inviolable face à un administrateur de base.

La migration V10 conserve les contraintes de dates et exige une référence de journal cohérente pour une approbation ou clôture exceptionnelle. Le verrou SQL et la version préviennent les écrasements concurrents. Une erreur annule ensemble la dérogation, le journal et l’événement Temporal.

La suppression forcée utilise `deleted_at`. Toutes les lectures métier et les mutations refusent ensuite cette mission, même au superadmin. Les données, le journal et l’outbox Temporal restent conservés. La suppression ordinaire d’un brouillon reste physique, comme au jalon précédent. Les notifications déjà reçues restent historiques et leur lien ne permet plus de consulter la mission supprimée.

## Temporal

Pour une mission en attente, APPROVE émet la décision APPROVED. CLOSE, REOPEN et DELETE émettent WITHDRAWN pour terminer le cycle courant. L’outbox est conservée après suppression logique afin que la décision soit livrée même après une panne. Aucun workflow n’est inventé lorsqu’une mission en cours est approuvée directement par dérogation. Une soumission ultérieure crée un nouveau cycle.

## Vérifications

- 59 tests Java réussis, avec accès global, isolation des utilisateurs ordinaires, refus sans rôle, motif et version, autoapprobation explicite, journal et rollback transactionnel.
- 11 tests navigateur réussis pour les parcours affectés, dont connexion superadmin, création, accès global, confirmation, CSRF, dérogations et consultation du journal.
- `scripts/test-superadmin.py` vérifie avec de vrais jetons Keycloak les résultats Temporal APPROVED et WITHDRAWN après approbation, clôture et suppression administratives.
- Compilation frontend réussie et nettoyage automatique des données synthétiques vérifié.

Les six missions réalistes et la mission utilisateur sont conservées. Aucun déploiement distant ni publication de cette tranche.
