# Programme d’audit : risques et contrôles

## Périmètre livré

La fiche de mission contient une section Programme d’audit et un lien de navigation direct. Le responsable propriétaire et le superadministrateur peuvent préparer les risques et les contrôles. Les auditeurs affectés et le validateur désigné consultent le même programme, sans le modifier.

Un risque comprend un titre, une description, une probabilité et un impact. Les échelles vont de 1 à 5. La probabilité va de Très faible à Très élevée, l’impact de Mineur à Critique. Ces échelles ordinales constituent une convention initiale à confirmer avec les utilisateurs métier. Aucun score n’est calculé.

Un contrôle comprend un objectif et une procédure de vérification. Il couvre au moins un risque, éventuellement plusieurs, appartenant tous à sa mission. Exemple : Paiement en double, couvert par Vérifier les doublons de factures.

La création et la modification sont disponibles. La suppression, l’archivage, l’exécution, les résultats et les preuves restent hors de cette tranche. Un programme vide ne bloque pas la soumission d’une mission, les règles de complétude restent à définir.

## Permissions et concurrence

Les écritures sont autorisées uniquement en Brouillon et En cours. Pendant la validation, après approbation et après clôture, le programme est en lecture seule, y compris pour le superadministrateur. Une réouverture explicite de la mission peut rétablir la modification selon les règles existantes.

Chaque écriture prend le même verrou PostgreSQL que les transitions de mission, contrôle sa version puis incrémente celle-ci. Une écriture concurrente ou un statut verrouillé retourne 409. L’utilisateur peut actualiser la fiche et reprendre sa modification. Les permissions sont contrôlées dans audit-service, en plus du BFF. Les accès hors mission et aux missions supprimées retournent 404. Un utilisateur autorisé en lecture mais sans droit d’écriture reçoit 403.

La lecture du programme utilise une transaction avec une vue cohérente de la mission, des risques et des contrôles. Les liens entre missions sont refusés par l’API et par des clés étrangères composites.

## Données et traces

La migration V11 ajoute mission_risks, mission_controls, mission_control_risks et program_change_log, sans remplacer les données existantes. Le journal conserve la cible, l’acteur, la date, la version et les valeurs avant et après modification, dans la même transaction. Le compte applicatif peut uniquement lire et insérer dans ce journal. Son affichage et sa politique de conservation restent à définir.

Le programme reste dans audit-service. Il n’ajoute pas de microservice, de workflow Temporal ou d’événement Kafka, aucun consommateur asynchrone n’étant nécessaire pour cette tranche.

## API et limites

- GET /missions/{id}/program : mission, risques et contrôles.
- PUT /missions/{id}/risks : créer sans id ou modifier avec id, version de mission obligatoire.
- PUT /missions/{id}/controls : même convention, avec riskIds non vide.

Chaque mission est limitée à 100 risques et 100 contrôles. Les champs sont bornés, les protections de taille, débit, session et CSRF existantes restent appliquées. La lecture est bornée par ces plafonds, sans pagination pour cette première tranche. Les textes métier ne sont pas publiés dans Kafka.

## Vérification locale

50 tests du service d’audit réussis, dont quatre nouveaux scénarios couvrant persistance et journal, permissions et isolation, verrouillage et suppression logique, échelles et liens multiples. Un parcours navigateur vérifie la création, la modification, la consultation par un auditeur et le verrouillage après soumission. Compilation frontend réussie. Les données créées par le test navigateur sont nettoyées par identifiant.

Les changements précédents ont été publiés dans 6ca0b6c, avec CI GitHub réussie. Le programme d’audit est vérifié localement, sa validation distante reste à faire après publication.
