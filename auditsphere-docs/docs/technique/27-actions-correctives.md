# Actions correctives : implémentation proposée

**Statut :** proposé le 2026-10-24, découpage accepté par ADR-0022. Aucun code, aucune migration et aucun test n’existent pour cette tranche.
**Tranche :** F-006, US-006 et US-007. Règles métier dans le [plan métier](../produit/04-plan-metier.md). Découpage du service dans [ADR-0022](../decisions/ADR-0022-actions-correctives-service-dedie.md), accepté le 2026-10-24.

Ce document décrit le découpage technique prévu. Chaque lot sera marqué implémenté puis vérifié avec ses preuves de test. Les noms de tables, de chemins et de variables sont des propositions.

## Emplacement

Un nouveau service Spring Boot, `remediation-service`, porte les suivis de constats, les actions, les justificatifs et leur journal. Il possède sa base PostgreSQL `remediation`, sa migration Flyway, son compte applicatif `remediation_runtime` et son bucket Garage. La gateway lui transmet `/remediation/**`.

audit-service reste propriétaire des missions et des constats. Il publie l’émission des constats à l’approbation et expose une lecture interne de l’instantané émis. remediation-service publie l’affectation d’une action, consommée par notification-service. Les raisons de ce découpage sont dans ADR-0022.

## Synchronisation avec audit-service

### Événements produits par audit-service

| Topic | Moment | Contenu |
| --- | --- | --- |
| `audit.mission.findings-issued.v1` | Transaction d’approbation de la mission : décision APPROVED du validateur ou dérogation APPROVE | eventId, eventType, eventVersion, occurredAt, missionId, issueCycle, aggregateVersion |
| `audit.mission.findings-withdrawn.v1` | Dérogation REOPEN ou DELETE d’une mission approuvée ou clôturée | eventId, eventType, eventVersion, occurredAt, missionId, issueCycle, reason parmi REOPENED et DELETED |

- Migration V18 d’audit-service : colonne `missions.issue_cycle`, entier nul par défaut, incrémentée à chaque approbation. Table `findings_issue_outbox` sur le modèle de `assignment_outbox` et `review_workflow_outbox`, publiée par `OutboxPublisher`.
- La clé Kafka est `missionId`. L’ordre des deux événements d’une même mission est donc conservé dans sa partition.
- Les messages ne contiennent aucun texte de constat, aucun titre de mission et aucun sujet utilisateur. Kafka n’est pas encore chiffré ni authentifié, voir C7 et ADR-0020.
- Les schémas sont ajoutés dans `contracts/mission-findings-issued-v1.schema.json` et `contracts/mission-findings-withdrawn-v1.schema.json`.

### Lecture interne de l’instantané

`GET /internal/missions/{id}/issues/{cycle}` dans audit-service renvoie l’instantané émis :

- titre de la mission, sujet du propriétaire, du validateur et des auditeurs affectés.
- constats Validé : identifiant, version, référence, titre, gravité, condition observée, cause et recommandation.

La lecture exige un jeton client credentials du client Keycloak `remediation-service`, porteur du rôle de service `REMEDIATION_SYNC`. La gateway ne route pas `/internal/**`, le chemin reste accessible uniquement depuis le réseau interne. Elle répond 409 si le cycle demandé n’est plus le cycle courant ou si la mission n’est plus approuvée ou clôturée. L’instantané est stable car les constats, les affectations et la désignation sont figés après approbation.

### Consommation dans remediation-service

1. Ignorer un eventId déjà traité, enregistré dans `processed_events`.
2. Ignorer un événement dont le cycle est inférieur au cycle connu de la mission.
3. Pour `findings-issued`, lire l’instantané. Une erreur réseau ou 5xx laisse le message non acquitté, avec reprise et délai croissant. Une réponse 409 acquitte le message, un événement plus récent suivra.
4. Dans une seule transaction : mettre à jour le périmètre de la mission, créer ou mettre à jour chaque suivi présent, réactiver les suivis suspendus présents et passer Retiré les suivis absents. Enregistrer l’eventId.
5. Pour `findings-withdrawn`, passer les suivis actifs à Suspendu avec REOPENED, ou à Retiré avec DELETED.

Entre l’approbation et la synchronisation, les suivis ne sont pas encore visibles. L’interface affiche alors un message de synchronisation en cours pour une mission approuvée.

## Données, base remediation, migration V1

`mission_scopes` : mission_id, title, owner_subject, validator_subject, issue_cycle, state parmi ACTIVE, SUSPENDED et WITHDRAWN, synced_at. `mission_scope_auditors` : mission_id, subject.

`finding_follow_ups` :

- id égal à l’identifiant du constat dans audit-service, mission_id, reference entière, finding_version.
- title, severity, condition_text, cause, recommendation : copie de l’instantané.
- status parmi TO_PLAN, ACTION_PLAN, VERIFICATION, CLOSED, SUSPENDED et WITHDRAWN, status_before_suspension.
- version, closure_requested_by, closure_requested_at, closed_by, closed_at, return_comment, created_at, updated_at.

`corrective_actions` :

- id, follow_up_id, mission_id, reference entière unique par suivi, attribuée par `action_sequences` sans réutilisation, comme pour les constats.
- description, owner_subject, due_date de type date, status parmi TODO, IN_PROGRESS, DONE, VERIFIED et CANCELLED.
- version, created_by, created_at, updated_by, updated_at, completion_note, completed_at, verified_by, verified_at, verification_comment, cancel_reason, due_date_changes.
- Contraintes CHECK sur les longueurs, les statuts, la note de réalisation hors TODO et IN_PROGRESS, le vérificateur différent du responsable et le motif d’annulation.

`action_progress_notes` : id, action_id, author_subject, note, created_at, en ajout seul. `action_attachments` : id, action_id, mission_id, object_key, file_name, content_type, size_bytes, sha256, uploaded_by, uploaded_at.

`remediation_change_log` : id, mission_id, follow_up_id, action_id, actor_subject, action, entity_version, reason, override, previous_data, resulting_data, occurred_at. `processed_events` : event_id, processed_at. `remediation_outbox` sur le modèle de l’outbox d’audit-service.

`scripts/harden-databases.py` crée la base et le rôle `remediation_runtime`. Il accorde uniquement SELECT et INSERT sur `remediation_change_log` et `action_progress_notes`, ainsi que SELECT, INSERT, UPDATE et DELETE sur les autres tables. Aucune clé étrangère ne relie cette base à celle d’audit-service.

## API remediation-service

| Méthode et chemin | Usage |
| --- | --- |
| GET /remediation/missions/{missionId}/follow-ups | Suivis de la mission avec leurs actions, pour l’équipe d’audit |
| GET /remediation/follow-ups/{id}/history | Historique du suivi et de ses actions |
| PUT /remediation/follow-ups/{id}/transition | REQUEST_CLOSURE, CLOSE ou RETURN, avec version et commentaire |
| POST /remediation/follow-ups/{id}/actions | Création d’une action par le responsable de mission |
| PUT /remediation/actions/{id} | Description, responsable et échéance, motif obligatoire pour un report |
| PUT /remediation/actions/{id}/transition | COMPLETE, VERIFY, RETURN, CANCEL ou REOPEN, avec version et commentaire |
| POST /remediation/actions/{id}/progress | Avancement par le responsable de l’action |
| POST /remediation/actions/{id}/attachments | Dépôt multipart d’un justificatif |
| GET /remediation/actions/{id}/attachments/{attachmentId} | Téléchargement |
| DELETE /remediation/actions/{id}/attachments/{attachmentId}?version= | Retrait par son auteur avant réalisation |
| GET /remediation/me/actions | Actions de l’audité connecté, filtres statut et retard |
| GET /remediation/directory/auditees?q= | Recherche des comptes AUDITEE actifs pour le responsable de mission |

Chaque écriture suit le même ordre :

1. Charger le suivi avec un verrou de ligne. Refuser par 404 un suivi inconnu ou hors périmètre de l’utilisateur.
2. Vérifier le rôle, le sujet par rapport au périmètre de la mission ou au responsable de l’action, puis l’état ACTIVE de la mission.
3. Comparer la version de l’entité, appliquer la transition autorisée.
4. Écrire l’entité, le journal et l’éventuel événement d’outbox dans la même transaction.

Le verrou du suivi sérialise les actions d’un même constat, notamment la demande de clôture face à une réouverture d’action. Le dépôt d’un justificatif verrouille aussi la ligne `mission_scopes` pour contrôler le quota de la mission, comme les preuves de contrôle. L’objet déjà transmis au stockage est supprimé si la transaction est refusée.

Les erreurs suivent les conventions d’audit-service : 400 pour un champ invalide, 403 pour un rôle insuffisant ou une séparation des fonctions non respectée, 404 hors périmètre, 409 pour un statut ou une version. `server.error.include-message=never` s’applique.

Le retard est calculé à la lecture avec `REMEDIATION_TIME_ZONE`, Europe/Paris par défaut, et une horloge injectable pour les tests. Aucun traitement planifié ne modifie les statuts.

## Identité et annuaire

- Nouveau rôle de royaume `AUDITEE`. Compte de démonstration `auditee` dans le royaume de développement uniquement, le royaume de production reste sans compte démo selon C1.
- L’administration des comptes, ADR-0016, permet d’attribuer AUDITEE.
- Adaptateur d’annuaire dans remediation-service avec un client technique `remediation-directory`, limité à view-users, sur le modèle d’ADR-0005. Il valide le rôle AUDITEE et l’activité du compte à la création et à la réaffectation.
- Filtre de révocation repris de notification-service et de la gateway, pour retirer l’accès sans attendre l’expiration du jeton.

## Notifications

remediation-service écrit `audit.action.assigned.v1` dans son outbox à la création et à la réaffectation d’une action : eventId, actionId, followUpId, missionId, recipientSubject, aggregateVersion, occurredAt. notification-service ajoute un consommateur idempotent par event_id, comme pour `audit.mission.assigned.v1`. La notification renvoie vers `/actions/{id}`. Le schéma est ajouté dans `contracts/action-assigned-v1.schema.json`.

## BFF et interface

- Gateway : route `/remediation/**`, corps limité à 32 Kio sauf le dépôt de justificatif limité à 11 Mio.
- Routes Next.js sous `apps/web/src/app/api/remediation`, servies par `src/lib/remediation-api.ts`, avec session, origine, CSRF, limitation de débit et liste blanche des champs. Règles pures dans `src/lib/remediation-rules.ts`.
- Onglet Suivi des actions dans la fiche de mission, visible pour une mission approuvée ou clôturée. Cartes par constat avec état du suivi, actions, retards, historique et décisions de clôture.
- Page Mes actions sous `/actions` pour le rôle AUDITEE, seule entrée de l’audité avec les notifications. Fiche d’action avec copie du constat, avancement, justificatifs et déclaration de réalisation.
- Contrat `contracts/remediation-api.yaml`.

## Compose et CI

- Compose : `remediation-service`, base `remediation-db` publiée sur 127.0.0.1:5547 en développement uniquement, bucket Garage et clé dédiés préparés par `scripts/setup-garage.py`.
- Module Maven ajouté au parent, donc compilé et testé par `./mvnw -B verify` en CI.
- `scripts/test-remediation.py --faults` sur le modèle des scripts de notifications : redélivrance, audit-service arrêté pendant la lecture d’instantané, reprise sans doublon.
- `scripts/cleanup-test-data.py` étendu à la base remediation, journal conservé.

## Lots et vérifications prévues

| Lot | Contenu | Vérification attendue |
| --- | --- | --- |
| 1 | Squelette remediation-service, migration V1, droits SQL, route gateway, Compose, rôle AUDITEE et clients Keycloak | Migration sur base vide avec Testcontainers, test des droits, santé du service, jeton invalide refusé |
| 2 | audit-service : V18, outbox d’émission et de retrait, lecture interne | Test Java : un événement par approbation et dérogation APPROVE, aucun après annulation de transaction, retrait sur REOPEN et DELETE, lecture interne refusée sans rôle de service, 409 sur cycle périmé |
| 3 | Consommateur, lecture d’instantané et réconciliation | Test d’intégration : redélivrance sans doublon, cycle ancien ignoré, audit-service indisponible sans acquittement, suspension puis réactivation, constat absent passé Retiré |
| 4 | API des suivis et actions, annuaire, journal | Test Java : matrice des permissions, séparation des fonctions y compris par dérogation, versions, retard avec horloge fixée, journal sans UPDATE ni DELETE |
| 5 | Justificatifs dans Garage | Test : type, taille, nombre, quota concurrent, nettoyage de l’objet après refus, retrait interdit après réalisation |
| 6 | Notification d’affectation | Test notification-service : consommation idempotente, destinataire seul, reprise après panne du broker |
| 7 | BFF, contrat et interface | Tests unitaires Node, TypeScript, build, Playwright `remediation.spec.ts` : approbation, action, avancement et justificatif par l’audité, renvoi, vérification, demande de clôture, clôture, audité extérieur et utilisateur extérieur refusés |
| 8 | Documentation et publication | Ce guide passé à implémenté puis vérifié, backlog, changelog, migrations appliquées localement après sauvegarde, CI distante |

## Limites connues de la proposition

- Le texte des constats est copié dans une seconde base. Sauvegarde, conservation et chiffrement des volumes, C7, doivent couvrir aussi la base remediation et son bucket.
- La lecture interne crée un couplage au moment de la consommation. Une indisponibilité d’audit-service retarde la synchronisation sans perte, le message reste en attente.
- La protection de `/internal/**` repose sur le jeton de service et le réseau interne. mTLS entre services reste prévu par ADR-0020, non déployé.
- Les relances et escalades de retard ne sont pas incluses. Le retard reste un affichage jusqu’à US-009 et Temporal.
- Un suivi Clôturé ne peut pas être rouvert dans cette tranche.
