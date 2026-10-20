# Résultats des contrôles

## Parcours

Dans une mission En cours, un auditeur affecté peut renseigner chaque contrôle du programme. La conclusion est Conforme, Non conforme ou Non applicable. Une justification non vide, limitée à 4000 caractères, est obligatoire dans les trois cas. Le nom de l’auteur et la date de saisie sont affichés avec le résultat.

L’auteur authentifié et la date sont déterminés par le serveur. Le navigateur ne peut pas les choisir. Le nom affiché provient du jeton au moment de la saisie et constitue une information historique. L’identifiant de compte est conservé pour la traçabilité.

Un auditeur affecté peut corriger le résultat courant, y compris celui d’un autre auditeur de la même mission. La nouvelle saisie remplace le résultat visible, avec son auteur et sa date. Les versions précédentes restent dans le journal transactionnel. Le superadministrateur dispose également de cette action, sous les mêmes contraintes de statut et de version.

## Permissions et verrouillage

Le rôle AUDITOR et l’affectation à la mission sont tous deux nécessaires. Le responsable et le validateur consultent les résultats, leurs seuls rôles ne donnent pas le droit de saisie. Une personne cumulant le rôle auditeur et une affectation conserve le droit associé.

La saisie est refusée en Brouillon, pendant la validation, après approbation et après clôture. Le retrait de soumission ou la réouverture autorisée rétablit la saisie lorsque la mission revient En cours.

L’API verrouille la mission et vérifie sa version comme les transitions métier. Une soumission concurrente ou un formulaire périmé ne peut donc pas écraser le résultat. Un contrôle d’une autre mission est refusé. Les accès à une mission supprimée ou hors périmètre restent refusés.

## Changements du programme

Modifier un contrôle efface son résultat courant afin de demander une nouvelle exécution. L’interface prévient le responsable avant l’enregistrement. Le contrôle et son ancien résultat sont conservés dans la trace de modification. Une simple modification d’un risque ne réinitialise pas les résultats des contrôles associés, la revue de leur pertinence reste à la charge du responsable.

## Stockage et API

La migration V12 ajoute result_data sur mission_controls et le type RESULT dans program_change_log. Le résultat courant et sa trace sont écrits dans la même transaction PostgreSQL. Le journal conserve acteur, cible, version de mission et valeurs avant et après. Le compte applicatif ne peut ni modifier ni supprimer ce journal.

PUT /missions/{id}/results reçoit version, controlId, outcome et comment. Les valeurs techniques sont CONFORMING, NON_CONFORMING et NOT_APPLICABLE. GET /missions/{id}/program expose le résultat courant dans result pour chaque contrôle, ou null lorsqu’il n’est pas renseigné. La réponse à une saisie contient le programme et la nouvelle version de mission.

Les protections BFF existantes restent appliquées : jetons côté serveur, session, contrôle CSRF, limitation de débit et taille de corps. Cette tranche n’ajoute aucun fichier de preuve, événement Kafka ou workflow Temporal.

## Limites

Aucune règle de complétude n’empêche encore de soumettre une mission avec des contrôles non renseignés. Il n’y a pas encore de fichier justificatif, de journal consultable dans l’interface, ni de constat automatiquement créé à partir d’un résultat non conforme. Ces fonctionnalités restent distinctes.

## Vérification

52 tests du service d’audit réussis, dont deux nouveaux scénarios sur les corrections, la trace, l’invalidation lors de la modification d’un contrôle, les permissions, les statuts, les références entre missions et les champs invalides. Compilation frontend réussie. Migration V12 appliquée en local et 16 tests navigateur réussis, dont la saisie et la correction par un auditeur puis la consultation par le responsable. Contrôle visuel du résultat effectué. Les données de test sont nettoyées et les sept missions existantes sont conservées.

La CI du commit précédent a1c7391 a échoué à l’étape navigateur. Les logs reçus identifient une réponse 429 à la place du conflit 409 attendu. Le lanceur de CI isole désormais les quotas entre fichiers de tests, sans modifier les limites applicatives. Sa validation distante reste à faire après publication. Cette nouvelle tranche n’est pas encore publiée.
