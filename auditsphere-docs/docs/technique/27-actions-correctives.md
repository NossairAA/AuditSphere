# Actions correctives : implémentation proposée

**Statut :** découpage accepté par ADR-0022. Lot 1 implémenté, vérifié localement puis par la CI distante, publié dans `main` le 2026-10-24. Lot 2 vérifié par la CI distante et publié dans `main` le 2026-10-26. Lot 3 vérifié par la CI distante et publié dans `main` le 2026-10-29, non déployé dans k3s. Lot 4 vérifié par la CI distante et publié dans `main` le 2026-10-30. Lot 5 vérifié par la CI distante et publié dans `main` le 2026-10-30. Lot 6 implémenté et vérifié localement le 2026-10-30, publié dans `main` puis déployé dans k3s par HOST-013. Lot 7 implémenté et vérifié localement le 2026-11-01, publié dans `main` puis déployé dans k3s le même jour. Lot 8 proposé.
**Tranche :** F-006, US-006 et US-007. Règles métier dans le [plan métier](../produit/04-plan-metier.md). Découpage du service dans [ADR-0022](../decisions/ADR-0022-actions-correctives-service-dedie.md), accepté le 2026-10-24.

Ce document décrit le découpage technique. Chaque lot est marqué implémenté puis vérifié avec ses preuves de test. Les noms des lots 2 à 8 restent des propositions.

## Emplacement

Un nouveau service Spring Boot, `remediation-service`, porte les suivis de constats, les actions, les justificatifs et leur journal. Il possède sa base PostgreSQL `remediation`, sa migration Flyway, son compte applicatif `remediation_runtime` et son bucket Garage. La gateway lui transmet `/remediation/**`.

audit-service reste propriétaire des missions et des constats. Il publie l’émission des constats à l’approbation et expose une lecture interne de l’instantané émis. remediation-service publie l’affectation d’une action, consommée par notification-service. Les raisons de ce découpage sont dans ADR-0022.

## Synchronisation avec audit-service

### Événements produits par audit-service

| Topic | Moment | Contenu |
| --- | --- | --- |
| `audit.mission.findings-issued.v1` | Transaction qui fait entrer la mission dans APPROVED ou CLOSED depuis un autre statut : décision APPROVED du validateur, dérogation APPROVE, dérogation CLOSE depuis En cours ou En attente | eventId, eventType, eventVersion, occurredAt, missionId, issueCycle, aggregateVersion |
| `audit.mission.findings-withdrawn.v1` | Dérogation REOPEN ou DELETE d’une mission approuvée ou clôturée | eventId, eventType, eventVersion, occurredAt, missionId, issueCycle, reason parmi REOPENED et DELETED |

- Migration V18 d’audit-service : colonne `missions.issue_cycle`, valant 0 par défaut, incrémentée à chaque émission. Elle n’est pas mappée par JPA : seul `FindingsIssueOutbox` l’écrit, en SQL, dans la transaction de la mission, sans changer la version de la mission. Table `findings_issue_outbox` sur le modèle de `assignment_outbox`, publiée par `FindingsIssuePublisher` avec le même protocole de bail.
- La clôture ordinaire d’une mission approuvée ne crée pas de nouvelle émission. Une mission approuvée ou clôturée avant V18 garde le cycle 0 : elle n’est pas émise rétroactivement et sa réouverture ne publie aucun retrait.
- La clé Kafka est `missionId`. L’ordre des deux événements d’une même mission est donc conservé dans sa partition.
- Les messages ne contiennent aucun texte de constat, aucun titre de mission et aucun sujet utilisateur. Kafka n’est pas encore chiffré ni authentifié, voir C7 et ADR-0020.
- Les schémas sont dans `contracts/mission-findings-issued-v1.schema.json` et `contracts/mission-findings-withdrawn-v1.schema.json`. Les topics et leurs DLT sont créés par `kafka-init`.

### Lecture interne de l’instantané

`GET /internal/missions/{id}/issues/{cycle}` dans audit-service renvoie l’instantané émis :

- titre de la mission, sujet du propriétaire, du validateur et des auditeurs affectés.
- constats Validé : identifiant, version, référence, titre, gravité, condition observée, cause et recommandation.

La lecture exige un jeton client credentials du client Keycloak `remediation-service`, porteur du rôle de service `REMEDIATION_SYNC` et de l’audience `auditsphere-api`. `setup-web.py` crée ce client confidentiel, sans flux navigateur ni mot de passe, et n’attribue à son compte de service que ce rôle. Le secret `REMEDIATION_SYNC_CLIENT_SECRET` reste dans `.env`. Aucune personne ne doit recevoir ce rôle, l’administration des comptes ne le propose pas. La gateway ne route pas `/internal/**`, le chemin reste accessible uniquement depuis le réseau interne. Elle répond 409 si le cycle demandé n’est plus le cycle courant ou si la mission n’est plus approuvée ou clôturée. L’instantané est stable car les constats, les affectations et la désignation sont figés après approbation. La lecture verrouille la ligne de mission en partage : une réouverture ou une suppression concurrente attend la fin de la lecture. La transaction n’est pas déclarée en lecture seule, PostgreSQL refusant `FOR SHARE` dans ce mode.

### Consommation dans remediation-service

1. Ignorer un eventId déjà traité, enregistré dans `processed_events`.
2. Ignorer un événement dont le cycle est inférieur au cycle connu de la mission.
3. Pour `findings-issued`, lire l’instantané. Une erreur réseau ou 5xx laisse le message non acquitté, avec reprise et délai croissant. Une réponse 409 acquitte le message, un événement plus récent suivra.
4. Dans une seule transaction : mettre à jour le périmètre de la mission, créer ou mettre à jour chaque suivi présent, réactiver les suivis suspendus présents et passer Retiré les suivis absents. Enregistrer l’eventId.
5. Pour `findings-withdrawn`, passer les suivis actifs à Suspendu avec REOPENED, ou à Retiré avec DELETED.

Entre l’approbation et la synchronisation, les suivis ne sont pas encore visibles. L’interface affiche alors un message de synchronisation en cours pour une mission approuvée.

## Données, base remediation, migration V1

`mission_scopes` : mission_id, title, owner_subject, validator_subject, issue_cycle, state parmi ACTIVE, SUSPENDED et WITHDRAWN, synced_at. `mission_scope_auditors` : mission_id, subject. La migration V2 rend `validator_subject` facultatif : une mission approuvée ou clôturée par dérogation n'a pas toujours de validateur désigné.

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
| GET /remediation/actions/{id} | Fiche d’action : action, copie du constat, avancement et historique de l’action, ajoutée au lot 4 pour l’audité |
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
- Adaptateur d’annuaire dans remediation-service avec un client technique `remediation-directory`, sur le modèle d’ADR-0005. Il est créé au lot 4 avec ses droits exacts, vérifiés contre Keycloak. Il valide le rôle AUDITEE et l’activité du compte à la création et à la réaffectation.
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

- Compose : `remediation-service`, base `remediation-db` publiée sur 127.0.0.1:5548 en développement uniquement, le port 5547 étant déjà pris par notification-db, bucket Garage et clé dédiés préparés par `scripts/setup-garage.py`.
- Module Maven ajouté au parent, donc compilé et testé par `./mvnw -B verify` en CI.
- `scripts/test-remediation.py --faults` sur le modèle des scripts de notifications : redélivrance, audit-service arrêté pendant la lecture d’instantané, reprise sans doublon.
- `scripts/cleanup-test-data.py` étendu à la base remediation, journal conservé.

## Lot 1 : socle du service

- Module Maven `services/remediation-service`, port 8083, sans Kafka à ce stade. `MIGRATION_ONLY` applique Flyway avec le compte migrateur, comme les autres services.
- Migration V1 : toutes les tables décrites plus haut. Les contraintes CHECK testent explicitement `IS NOT NULL` pour les valeurs obligatoires, car un CHECK évalué à NULL est accepté par PostgreSQL. Les numéros d’action sont uniques par suivi, une action ne peut pas viser le suivi d’une autre mission, le responsable d’une action ne peut pas en être le vérificateur et la personne qui demande la clôture ne peut pas clôturer.
- Sécurité : jeton JWT de l’audience `auditsphere-api`, rôles de royaume convertis en rôles Spring, filtre de révocation interrogeant `/account/security` d’audit-service. `/remediation/**` est réservé aux rôles humains, tout autre chemin est refusé.
- Lecture `GET /remediation/me/actions`, réservée à AUDITEE : actions de l’utilisateur connecté, hors suivis et missions retirés, triées par échéance, avec l’indicateur de retard calculé par `REMEDIATION_TIME_ZONE`.
- `scripts/prepare-remediation.py` crée les secrets locaux. `harden-databases.py` et `migrate-local.py` traitent la base remediation. Compose ajoute `remediation-db` et `remediation-service`, la gateway route `/remediation/**`.
- Rôle AUDITEE créé dans tous les environnements par `setup-web.py`, compte de démonstration `auditee` en local uniquement, refusé en production par `keycloak_production.py`. L’administration des comptes peut attribuer ce rôle, affiché Audité.
- Un audité peut se connecter à l’application web, mais n’a encore aucun écran dédié. Il ne voit aucune mission, audit-service lui répond 404 sur une mission dont il n’est pas participant.

## Vérifications du lot 1

- `RemediationIntegrationTest`, cinq tests avec PostgreSQL Testcontainers : migration V1 sur base vide et liste exacte des tables, rejet en base des lignes incohérentes, actions de l’audité limitées aux siennes avec retard calculé sur une horloge fixée, refus sans jeton, avec jeton invalide, pour AUDITOR et pour un compte de service, chemin hors `/remediation/**` refusé, compte révoqué refusé en 401 et registre injoignable en 503.
- Test de mutation : avec l’ancien CHECK sans `IS NOT NULL` sur le motif d’annulation, le test des contraintes échoue. Migration restaurée ensuite.
- `./mvnw -B clean verify` avec JDK 25 : 69 tests audit-service, 11 gateway, 17 notification-service et 5 remediation-service réussis.
- Tests Python des scripts : 22 tests réussis dont deux ignorés par conception, puis les deux tests de droits locaux réussis avec `AUDITSPHERE_TEST_LOCAL_GRANTS=1`. Ils couvrent l’ajout seul sur `remediation_change_log` et `action_progress_notes` et un droit explicite pour chaque table. `test_keycloak_production` vérifie aussi l’absence de port publié pour `remediation-db` en production.
- Pile locale relancée par `scripts/start-local.sh` : sauvegardes créées, V1 appliquée par le compte migrateur, `remediation_runtime` sans superutilisateur, 11 tables appartenant à `remediation_migrator`. Droits réels vérifiés : insertion sans modification ni suppression sur les deux tables en ajout seul.
- `scripts/smoke.py` avec de vrais jetons Keycloak par la gateway : l’audité obtient 200 et une liste sur `/remediation/me/actions`, 404 sur une mission et une liste de missions vide. AUDITOR reçoit 403, une requête sans jeton ou avec un jeton invalide reçoit 401.
- TypeScript du frontend réussi après l’ajout du libellé Audité.
- CI distante réussie sur `226c006`, exécutions `40120010724` et `40120015552`. PR #6 fusionnée dans `main` le 2026-10-24, commit `05f6191`.
- Non vérifié : parcours navigateur de l’administration des comptes avec le rôle AUDITEE.

## Lot 2 : émission depuis audit-service

- `FindingsIssueOutbox.afterTransition` est appelé après l’écriture de la mission dans la transition de statut, la décision du validateur et les dérogations. Il émet quand la mission entre dans APPROVED ou CLOSED, et publie un retrait quand elle en sort par REOPEN ou DELETE.
- `IssueSnapshotController` sert `GET /internal/missions/{id}/issues/{cycle}`. `SecurityConfig` réserve `/internal/**` au rôle REMEDIATION_SYNC, avant toute autre règle.
- `audit_runtime` reçoit SELECT, INSERT, UPDATE et DELETE sur `findings_issue_outbox`. `cleanup-test-data.py` supprime aussi ces lignes pour les missions de test.
- Aucun consommateur n’existe encore : les messages restent dans les topics jusqu’au lot 3.

## Vérifications du lot 2

- Six tests Java dans `MissionIntegrationTest` : émission à l’approbation avec instantané complet, aucune émission à la soumission ni à la clôture ordinaire, retrait REOPENED puis nouvelle émission au cycle 2 puis retrait DELETED, 409 sur un cycle périmé, 404 sur une mission inconnue, aucune émission après un refus, émission par la dérogation CLOSE, mission approuvée avant V18 rouverte sans retrait, accès interne refusé sans jeton et pour chacun des cinq rôles humains, annulation complète de l’approbation si l’outbox échoue, messages limités aux identifiants.
- Test de mutation : sans l’appel dans la décision du validateur, le test d’émission échoue. Code restauré ensuite.
- `./mvnw -B clean verify` avec JDK 25 : 75 tests audit-service, 11 gateway, 17 notification-service et 5 remediation-service réussis. Tests Python des scripts réussis.
- Pile locale relancée par `scripts/start-local.sh` : sauvegardes créées, V18 appliquée, 51 missions conservées. Les 15 missions déjà approuvées ou clôturées gardent le cycle 0. Topics et DLT présents dans Kafka.
- Droits réels de `audit_runtime`, dans une transaction annulée : `FOR SHARE` sur `missions`, mise à jour de `issue_cycle` et insertion dans `findings_issue_outbox` acceptées.
- `scripts/smoke.py` : jeton client credentials de `remediation-service` obtenu auprès de Keycloak, avec REMEDIATION_SYNC, l’audience `auditsphere-api` et aucun rôle humain. La gateway répond 404 sur `/internal/**`, même avec ce jeton, et le compte de service ne voit aucune mission.
- Parcours réel par la gateway avec les comptes de démonstration : création, programme, résultat Non conforme, constat validé, soumission et approbation. Le message arrive dans `audit.mission.findings-issued.v1` avec la clé `missionId`, le cycle 1 et uniquement des identifiants. La ligne d’outbox est marquée publiée après une tentative.
- Non couvert par un test automatisé : la réouverture concurrente d’une lecture d’instantané en cours. Non vérifié : appel réel de la lecture interne depuis le réseau Docker, prévu au lot 3 avec le consommateur. CI distante non exécutée.

## Lot 3 : consommateur et réconciliation

- `FindingsIssueConsumer` écoute les deux topics dans le groupe `remediation-service-v1`, avec un acquittement par message après validation de la transaction. Un message de plus de 32 Kio, un JSON illisible ou un événement hors contrat part dans le topic `.DLT` de même partition.
- Les deux topics ne garantissent aucun ordre entre eux. Chaque message prend donc un verrou consultatif PostgreSQL par mission, `pg_advisory_xact_lock`, gardé jusqu'à la validation. La lecture d'instantané se fait sous ce verrou : si un retrait a été appliqué avant, audit-service a déjà rouvert la mission et répond 409.
- Ordre des contrôles : eventId déjà présent dans `processed_events`, puis cycle connu de la mission. Une émission d'un cycle inférieur, ou du même cycle quand la mission n'est plus active, est ignorée sans lecture. Un retrait d'un cycle inférieur, ou d'une mission inconnue, est ignoré. L'eventId est enregistré dans tous ces cas.
- `IssueSnapshotClient` obtient un jeton client credentials du client `remediation-service` auprès de Keycloak, `KEYCLOAK_URL`, et le garde jusqu'à 15 secondes avant son expiration. Un 401 d'audit-service efface le jeton. Une réponse 404 ou 409 acquitte le message sans changement.
- Une erreur réseau, un 5xx, un 401 ou un 403 lève `SnapshotUnavailable` : la transaction est annulée et le message réessayé avec un délai doublé de 1 seconde à 1 minute, sans limite de tentatives.
- L'instantané est validé avant écriture : mission et cycle identiques à l'événement, textes non vides dans les longueurs de la base, gravité connue, identifiants et références uniques. Un instantané invalide lève `IllegalArgumentException` et part dans le `.DLT`.
- Émission : le périmètre est créé ou remplacé, état ACTIVE, et la liste des auditeurs réécrite. Un nouveau constat crée un suivi À planifier. Un suivi Suspendu reprend son statut d'avant suspension. Un suivi Retiré qui revient reprend Plan d'action s'il garde une action non annulée, sinon À planifier. Les textes sont recopiés quand la version du constat change. Un suivi absent de l'instantané passe Retiré.
- Retrait REOPENED : le périmètre passe SUSPENDED et chaque suivi actif passe Suspendu en gardant son statut. Retrait DELETED : le périmètre et tous les suivis passent WITHDRAWN. Une mission déjà retirée le reste.
- Chaque changement de suivi incrémente sa version et écrit une ligne `remediation_change_log` : ISSUED, SUSPENDED ou WITHDRAWN, acteur `audit-service`, motif REOPENED ou DELETED pour un retrait, statut, version du constat et cycle dans `previous_data` et `resulting_data`. Une émission sans changement n'écrit rien.
- Compose transmet `KAFKA_BOOTSTRAP_SERVERS`, `KEYCLOAK_URL` et `REMEDIATION_SYNC_CLIENT_SECRET` à remediation-service, qui attend `kafka-init`.

## Vérifications du lot 3

- `IssueSyncIntegrationTest`, six tests avec PostgreSQL Testcontainers, audit-service et Keycloak simulés par un serveur HTTP local :
  - émission créant le périmètre, les auditeurs et deux suivis À planifier, avec le jeton Bearer et le formulaire client credentials attendus. Redélivrance du même eventId sans lecture ni écriture, second événement du même cycle sans nouvelle version ni journal.
  - réouverture suspendant les suivis avec leur statut, émission tardive du cycle 1 ignorée sans lecture, cycle 2 restaurant Plan d'action avec les textes révisés, constat absent passé Retiré, nouveau constat créé, retrait ancien ignoré. Cycle 3 : un suivi retiré qui revient avec une action ouverte reprend Plan d'action.
  - audit-service en 503 puis en 401 : exception, aucune écriture, nouveau jeton demandé après le 401, puis synchronisation réussie.
  - retrait reçu avant toute émission, 409 et 404 acquittés sans périmètre.
  - mission sans validateur acceptée grâce à V2, puis retrait DELETED avec motif journalisé.
  - événements et instantanés invalides rejetés sans écriture : charge nulle, JSON illisible, cycle 0, motif inconnu, gravité inconnue, mission différente.
- `KafkaErrorHandlerTest` : un `IllegalArgumentException` part dans le `.DLT` de même partition, un `SnapshotUnavailable` reste en reprise sans envoi.
- Test de mutation : sans le contrôle du même cycle déjà retiré, le test de réouverture échoue. Code restauré ensuite.
- `./mvnw -B -pl services/remediation-service -am verify` avec JDK 25 : 12 tests remediation-service réussis. Les autres modules n'ont pas été relancés, ils ne changent pas. Tests Python des scripts réussis, 25 dont deux ignorés par conception, après ajout du secret de synchronisation au test de configuration production.
- Pile locale relancée par `scripts/start-local.sh` : V2 appliquée, `validator_subject` facultatif. Le consommateur a lu les 21 événements restés dans les topics depuis le lot 2, par le réseau Docker avec un vrai jeton Keycloak. Résultat comparé à la base audit : 14 missions approuvées ou clôturées avec un cycle émis, mêmes cycles et même nombre de constats validés, 25 suivis et 25 lignes ISSUED.
- Panne réelle : audit-service arrêté, événement injecté dans `audit.mission.findings-issued.v1`. Message non acquitté, trois tentatives journalisées en WARN avec `SnapshotUnavailable`, puis traité au redémarrage sans doublon, toujours 25 suivis et 25 lignes de journal.
- Non vérifié : CI distante, déploiement k3s, `scripts/test-remediation.py --faults` non écrit, panne vérifiée à la main.
- Non déployé dans k3s : il manque l'utilisateur Kafka `remediation-service` et ses ACL dans `k3s-kafka-security.sh`, le secret SASL, les variables Kafka, `KEYCLOAK_URL` et `REMEDIATION_SYNC_CLIENT_SECRET` dans `infra/helm/values/remediation-service.yaml`, et les sorties réseau vers Kafka et Keycloak.

## Lot 4 : API des suivis et des actions

- `FollowUpController` : liste des suivis d’une mission avec leurs actions, historique, transitions REQUEST_CLOSURE, CLOSE et RETURN, création d’action. `ActionController` : fiche, modification, transitions COMPLETE, VERIFY, RETURN, CANCEL et REOPEN, avancement. `DirectoryController` : recherche des audités.
- Lecture réservée au propriétaire AUDIT_MANAGER, aux auditeurs AUDITOR affectés, au validateur AUDIT_VALIDATOR désigné et à SUPER_ADMIN. L’audité AUDITEE ne voit que ses actions, par `GET /remediation/actions/{id}` et `GET /remediation/me/actions`. Tout autre utilisateur reçoit 404. Une mission supprimée, état WITHDRAWN, devient introuvable. Une mission suspendue reste lisible.
- Le superadministrateur agit sur les mêmes routes, comme pour les constats d’audit-service : la ligne du journal porte `override` à vrai quand il n’est pas le titulaire ordinaire du droit. Il ne déclare jamais une action réalisée et ne renseigne pas l’avancement, réservés au responsable de l’action.
- Séparation des fonctions, vérifiée même par dérogation : le responsable d’une action ne la vérifie pas, la personne qui demande la clôture ne clôture pas. Les contraintes CHECK de la base bloquent aussi ces deux cas. Sans validateur désigné, seul un superadministrateur clôture.
- Ordre des contrôles : 404 hors périmètre, 403 rôle, 400 champ invalide, puis 409 si la mission n’est pas active, si le suivi est Suspendu, Retiré ou Clôturé, si le statut ne permet pas la transition ou si la version est périmée. Les actions sont figées tant que le suivi est en Vérification.
- Création : description de 1 à 2000 caractères, échéance entre la date du jour et cinq ans, 20 actions au plus par suivi, références attribuées par `action_sequences` sans réutilisation. Le responsable n’est ni propriétaire, ni auditeur, ni validateur de la mission, et `AuditeeDirectory` vérifie dans Keycloak un compte actif portant AUDITEE. Le premier suivi passe en Plan d’action.
- Modification : description, responsable et échéance tant que l’action est À faire ou En cours. Un report exige un motif et incrémente `due_date_changes`. Une réaffectation retire l’accès au précédent responsable. Chaque aspect modifié écrit sa ligne UPDATED, REASSIGNED ou DUE_DATE_CHANGED.
- Avancement : note de 2000 caractères par le responsable de l’action, en ajout seul. La première note passe l’action En cours. Réalisation : commentaire obligatoire et au moins un justificatif, sinon 409. Les justificatifs arrivent au lot 5.
- Annulation de la dernière action non annulée : le suivi revient À planifier. Réouverture d’une action Vérifiée avec motif : elle revient En cours, vérificateur effacé.
- Demande de clôture : au moins une action Vérifiée et aucune action ouverte. Le renvoi par le validateur exige un commentaire et rouvre le plan d’action.
- Création et réaffectation écrivent `audit.action.assigned.v1` dans `remediation_outbox`, avec les seuls identifiants. La publication reste au lot 6.
- Verrous pris dans l’ordre du consommateur de synchronisation : `mission_scopes` en partage, puis le suivi, puis l’action. Les écritures et la synchronisation d’une même mission ne peuvent donc pas s’interbloquer.
- Annuaire : client Keycloak `remediation-directory`, distinct du compte de synchronisation, avec `view-users` et `view-realm` seulement. Secret `REMEDIATION_DIRECTORY_CLIENT_SECRET` créé par `setup-web.py` et transmis par Compose. La recherche filtre au plus 500 membres du rôle AUDITEE et renvoie 20 comptes actifs. Les noms affichés sont gardés 60 secondes en cache. Si Keycloak est indisponible, la lecture répond sans les noms et l’affectation répond 503.
- `GET /remediation/me/actions` accepte les filtres `status` et `overdue=true`.

## Vérifications du lot 4

- `RemediationApiIntegrationTest`, six tests avec PostgreSQL Testcontainers, horloge fixée au 2026-10-30 et annuaire simulé :
  - parcours nominal : création, avancement, réalisation refusée sans justificatif puis acceptée, renvoi sans commentaire refusé, renvoi, nouvelle réalisation, vérification refusée à l’audité et sur version périmée, vérification, demande de clôture refusée à l’auditeur, action figée en Vérification, renvoi du suivi, clôture refusée sur version périmée, clôture, historique complet dans l’ordre.
  - périmètre : 404 pour un utilisateur extérieur portant tous les rôles d’audit, sur la liste, l’historique, la fiche, la création et les transitions. L’audité ne voit pas la mission, voit sa fiche avec la copie du constat, pas celle d’un autre audité. Le même sujet sans rôle AUDITEE reçoit 404. 403 pour l’auditeur et le validateur qui créent ou annulent, pour l’audité qui modifie et pour l’auditeur qui renseigne l’avancement.
  - affectation : propriétaire, validateur et auditeur refusés comme responsables, compte sans AUDITEE refusé, échéance passée ou au-delà de cinq ans refusée, description vide refusée, 21e action refusée en 409, références 1 à 20.
  - report sans motif refusé puis compté, réaffectation qui retire l’accès au précédent responsable et ajoute une ligne d’outbox, annulation de la dernière action qui ramène le suivi À planifier, référence suivante non réutilisée, réouverture d’une action Vérifiée.
  - dérogations : création journalisée avec `override`, réalisation refusée au superadministrateur, vérification refusée au responsable de l’action même superadministrateur, clôture refusée à celui qui l’a demandée, clôture sans validateur désigné réservée au superadministrateur.
  - mission suspendue lisible sans écriture possible, mission retirée introuvable.
- `ErrorDispatchTest` avec un vrai Tomcat sur port aléatoire : 404, 400 et 200 conservés derrière la chaîne de sécurité, chemin hors API refusé en 403. Ce test échouait avant le correctif de `SecurityConfig` : MockMvc ne passe pas par `/error`, le défaut n’apparaissait que dans le service réel.
- Tests de mutation : sans le refus de la vérification par le responsable de l’action, puis sans le refus de la clôture par le demandeur, `RemediationApiIntegrationTest` échoue. Code restauré ensuite.
- `./mvnw -B -pl services/remediation-service -am verify` avec JDK 25 : 19 tests remediation-service réussis. Tests Python des scripts : 25 réussis dont deux ignorés par conception.
- Pile locale relancée par `scripts/start-local.sh` : client `remediation-directory` créé avec `view-users` et `view-realm`. Parcours réel par la gateway avec les jetons des comptes de démonstration, sur une mission synthétique insérée dans la base remediation puis retirée : lecture 200 pour le propriétaire, l’auditeur et le validateur, 404 pour l’audité et l’utilisateur extérieur, recherche réelle dans Keycloak, auditeur et compte sans AUDITEE refusés en 400, action créée pour `auditee`, avancement, Mes actions, fiche avec la copie du constat, nom réel du responsable, réalisation sans justificatif en 409, journal CREATED puis PROGRESS et une ligne d’outbox. `scripts/smoke.py` réussi.
- Non vérifié : CI distante, interface, notification réelle à l’audité, lots 5 et 6.
- Non déployé dans k3s : en plus du câblage du lot 3, le client `remediation-directory` et son secret manquent dans `k3s-setup-keycloak.py` et dans les valeurs Helm.

## Lot 5 : justificatifs

- `AttachmentController` sous `/remediation/actions/{id}/attachments` : dépôt multipart avec `version` et `file`, téléchargement, retrait avec `version`. La fiche d’action liste les justificatifs avec nom, type, taille, empreinte, auteur et date.
- Dépôt et retrait réservés au responsable de l’action, rôle AUDITEE, tant que l’action est À faire ou En cours et que le suivi est en Plan d’action sur une mission active. Le retrait est réservé à l’auteur du fichier. Après la déclaration Réalisée, un justificatif n’est plus ni ajouté ni retiré. Un renvoi En cours rouvre l’ajout.
- Téléchargement pour l’équipe d’audit et le responsable de l’action, 404 pour tout autre utilisateur. Réponse en pièce jointe avec `nosniff` et `no-store`. Taille et SHA-256 recalculés pendant l’envoi : un objet modifié dans le bucket interrompt la réponse et laisse une erreur dans les logs.
- Contrôles du modèle des preuves de contrôle : 1 octet à 10 Mio, type PDF, JPEG ou PNG détecté par la signature du contenu et identique au type déclaré, nom sans chemin ni caractère de contrôle, 10 fichiers par action, 100 Mio par mission, `REMEDIATION_MISSION_QUOTA_BYTES`.
- Ordre du dépôt : contrôles d’accès sans verrou, lecture du fichier, écriture de l’objet `attachments/{id}` dans Garage, puis transaction qui reprend tous les contrôles sous verrou. Elle verrouille `mission_scopes` en exclusif dès le départ, ce qui sérialise le quota de la mission sans risque d’interblocage. Si la transaction refuse, l’objet est supprimé. Un retrait supprime l’objet seulement après validation de la transaction.
- Chaque dépôt et retrait incrémente la version de l’action et écrit ATTACHMENT_ADDED ou ATTACHMENT_REMOVED avec l’identifiant, l’empreinte et la taille, jamais le contenu.
- Bucket Garage `auditsphere-remediation` et clé dédiée, distincts du bucket des preuves de contrôle. Chaque stockage a son propre endpoint : `EVIDENCE_S3_ENDPOINT` et `REMEDIATION_S3_ENDPOINT`. `scripts/setup-garage.py` provisionne dans le Garage local chaque stockage sans endpoint externe, avec une clé limitée à son bucket, et ignore les autres. Compose transmet `REMEDIATION_S3_*` à remediation-service, endpoint `http://garage:3900` par défaut.
- Gateway : `/remediation/actions/{id}/attachments` en POST reçoit la limite de 11 Mio et les créneaux de dépôt simultanés des preuves. Toute autre écriture sous `/remediation/**` reste limitée à 32 Kio.

## Vérifications du lot 5

- `AttachmentIntegrationTest`, quatre tests avec PostgreSQL Testcontainers et un stockage en mémoire à la place de Garage :
  - dépôt par l’audité, empreinte, téléchargement identique pour l’auditeur et l’audité, 404 hors périmètre, liste dans la fiche, retrait sur version périmée en 409, retrait par le responsable de mission en 403, retrait par l’auteur avec objet supprimé, déclaration Réalisée, puis ajout et retrait refusés en 409, journal dans l’ordre.
  - faux PDF, PNG déclaré PDF, nom avec chemin refusés en 400, fichier vide en 413, dépôt par le responsable de mission en 403, par un autre audité en 404, version périmée en 409, onzième fichier refusé en 409 après écriture de l’objet, objet supprimé ensuite.
  - quota concurrent : deux dépôts sur deux suivis d’une même mission proche du quota, tenus en vol ensemble par un verrou de table externe. Un seul passe, l’autre reçoit 413, un seul objet reste.
  - objet modifié dans le stockage : le téléchargement échoue au lieu de servir un contenu différent.
- Tests de mutation : avec un verrou partagé au lieu d’exclusif sur `mission_scopes`, le test de quota reçoit deux 201. Code restauré ensuite. Une première version du test, sans verrou de table, ne détectait pas cette mutation : les deux dépôts ne se croisaient pas.
- `RequestLimitsTest` de la gateway : limite de 11 Mio sur le dépôt de justificatif, 32 Kio sur les autres écritures de remediation.
- `./mvnw -B -pl services/remediation-service,services/api-gateway -am verify` avec JDK 25 : 23 tests remediation-service et 16 tests gateway réussis. Tests Python des scripts : 25 réussis dont deux ignorés par conception.
- Pile locale relancée : bucket `auditsphere-remediation` et sa clé créés dans le Garage local, le stockage des preuves restant sur son endpoint externe. Parcours réel par la gateway avec les jetons de démonstration, mission synthétique retirée ensuite : dépôt refusé au responsable de mission, dépôt par l’audité avec objet présent dans Garage et empreinte identique, téléchargement identique par l’auditeur, 404 hors périmètre, faux PDF en 400, 12 Mio refusé en 413 par la gateway, aucun objet en trop, retrait avec objet supprimé. `scripts/smoke.py` réussi.
- Incident pendant la vérification : deux essais interrompus par le script de test ont laissé deux objets sans ligne en base. Ils ont été listés puis supprimés du bucket local. Ce cas reste possible si le processus s’arrête entre l’écriture de l’objet et la transaction : aucun nettoyage automatique des objets orphelins n’existe.
- Non vérifié : CI distante, interface, Garage du serveur privé où le bucket des justificatifs n’est pas créé, k3s.

## Lot 6 : notification d’affectation

- `AssignmentPublisher` publie les lignes de `remediation_outbox` dans `audit.action.assigned.v1`, avec le protocole de bail des outbox d’audit-service : une ligne à la fois, bail de 30 secondes, nouvelle tentative différée de 2 secondes par tentative, 60 secondes au plus. Clé Kafka `actionId`, pour garder l’ordre des réaffectations d’une même action. Activé par `OUTBOX_ENABLED`, comme audit-service, et activé dans Compose.
- Le message contient seulement eventId, eventType, eventVersion, actionId, followUpId, missionId, recipientSubject, aggregateVersion et occurredAt. Schéma dans `contracts/action-assigned-v1.schema.json`. Topic et DLT créés par `kafka-init`.
- notification-service consomme ce topic dans son groupe existant, idempotent par eventId, voir le [guide des notifications](09-notifications-kafka.md). Un événement d’action sans `actionId`, ou de mission avec `actionId`, part dans le DLT.

## Vérifications du lot 6

- `AssignmentPublisherTest`, deux tests avec PostgreSQL Testcontainers et un KafkaTemplate simulé : publication sur le bon topic avec la clé de l’action, champs exactement égaux à ceux du schéma publié, aucun texte de constat ni d’action, ligne marquée publiée puis plus rien à publier. Courtier en échec : bail libéré, tentative comptée, ligne différée et non publiée.
- `NotificationIntegrationTest`, deux tests ajoutés : notification d’action enregistrée une fois malgré la redélivrance, visible par son destinataire avec `actionId`, invisible pour un autre utilisateur, notification de mission avec `actionId` nul. Événement d’action sans action et événement de mission avec action refusés sans écriture, contrainte V4 vérifiée en base.
- `./mvnw -B -pl services/remediation-service,services/notification-service -am verify` avec JDK 25 : 25 tests remediation-service et 20 tests notification-service réussis. TypeScript du frontend réussi.
- Pile locale relancée par `scripts/start-local.sh` : migration V4 de notification-service appliquée, topic `audit.action.assigned.v1` et son DLT créés. Parcours réel par la gateway avec les jetons de démonstration, sur une mission synthétique retirée ensuite : action créée pour `auditee`, ligne d’outbox publiée, notification reçue par l’audité avec `actionId` et le bon type, absente pour le responsable de mission, une seule ligne en base. `scripts/smoke.py` et tests Python réussis.
- Non vérifié : CI distante, affichage navigateur de la notification, k3s, où il manque le topic, son DLT, les ACL d’écriture de remediation-service et de lecture de notification-service.

## Lot 7 : BFF, contrat et interface

- Une route Next.js unique, `apps/web/src/app/api/remediation/[...path]/route.ts`, servie par `src/lib/remediation-api.ts`. Session, origine, CSRF et limitation de débit comme les autres routes du BFF. Le jeton reste côté serveur.
- `src/lib/remediation-rules.ts`, règles pures testées en unitaire : liste fermée des treize routes relayées, identifiants au format UUID, paramètres de recherche filtrés par route, liste blanche des champs de chaque corps, messages d'erreur en français sans détail du service. Une route absente de la liste répond 404 sans appel amont, `/remediation/internal/**` compris.
- Le BFF ne vérifie aucun rôle métier : responsable de mission, auditeur affecté, validateur désigné, responsable de l'action et dérogation du superadministrateur restent décidés par remediation-service. Les boutons affichés suivent les mêmes règles, mais un refus du service reste la seule garantie.
- Dépôt de justificatif : multipart relu dans une limite de 11 Mio, type PDF, JPEG ou PNG et taille de 10 Mio au plus contrôlés avant l'envoi, deux dépôts simultanés par utilisateur, comme les preuves de contrôle. Le téléchargement est relayé en flux avec `Content-Disposition` et `nosniff`.
- Onglet Actions correctives dans la fiche de mission, visible pour une mission approuvée ou clôturée, composant `mission-remediation.tsx`. Une carte par suivi avec gravité, statut, recommandation, actions, retards et historique. Le responsable ajoute une action en cherchant l'audité dans l'annuaire, puis demande la clôture quand toutes les actions sont vérifiées ou annulées. Le validateur désigné clôture ou renvoie, sauf s'il a lui-même demandé la clôture.
- Page Mes actions sous `/actions`, avec filtres de statut et de retard, et fiche `/actions/{id}`, composant `action-detail.tsx`. L'audité y voit la copie du constat, saisit son avancement, dépose et retire ses justificatifs puis déclare la réalisation, refusée sans justificatif. L'équipe d'audit ouvre la même fiche depuis l'onglet de la mission pour vérifier ou renvoyer, le responsable de mission pour modifier, annuler ou rouvrir. Un changement d'échéance exige un motif.
- Un compte qui n'a que le rôle AUDITEE est redirigé de `/missions` vers `/actions`, ne voit plus le lien Missions d'audit et garde ses notifications. Une notification d'action ouvre désormais la fiche `/actions/{actionId}`.
- Contrat `contracts/remediation-api.yaml`, aligné sur les contrôleurs de remediation-service.
- `scripts/cleanup-test-data.py` couvre désormais la base remediation, comme prévu dans la section Compose et CI : sauvegarde de la base, arrêt de remediation-service avec les autres producteurs, retrait des objets de justificatifs dans Garage, suppression des suivis, actions et périmètres des missions de test. `remediation_change_log` et `processed_events` sont conservés. Dans k3s, le nettoyage s'arrête sans rien supprimer si des justificatifs existent, comme pour les preuves.

## Vérifications du lot 7

- Tests unitaires Node `tests/unit/remediation-rules.test.mjs`, cinq tests réussis : routes admises et refusées, chemins détournés, filtres de recherche, champs retirés des corps, versions, messages d'erreur.
- Playwright `tests/remediation.spec.ts` contre la pile locale, réussi : mission approuvée avec un constat validé, suivi reçu par Kafka, action créée par le responsable pour Nadia Rousseau, audité redirigé vers ses actions puis fiche ouverte depuis sa notification, avancement, dépôt et téléchargement d'un PDF, réalisation refusée sans justificatif puis déclarée, renvoi par l'auditeur, nouvelle réalisation, vérification, demande de clôture par le responsable sans bouton de clôture pour lui, clôture par le validateur. 404 pour un utilisateur extérieur sur la fiche, refus de la liste des suivis pour l'audité, 404 sur une route interne. Aucun débordement horizontal à 390 px.
- Nettoyage de fin de suite constaté en base : aucun périmètre de la mission de test et aucun justificatif orphelin dans remediation.
- `npm run typecheck` et `npm run build` réussis.
- Suite Playwright complète, le 2026-11-01 : `remediation.spec.ts` réussi au sein de la suite. Des échecs intermittents touchent d'autres tests, avec des réponses 503 du BFF ou une page de connexion qui ne s'affiche pas. Ils existent aussi sur `main` sans le lot 7 : quatre échecs sur une exécution de référence, sur `missions.spec.ts`, `program.spec.ts` et `withdrawal.spec.ts`. Les tests touchés changent d'une exécution à l'autre. Cause établie ensuite par TECH-011 : plafond de la gateway de 240 requêtes par identité, atteint par la vérification de session du BFF. Corrigé le même jour, voir le [durcissement](08-durcissement.md).
- CI distante réussie sur la PR et sur `main` (`995eff4`). Déployé dans k3s le 2026-11-01, voir l'[hébergement k3s](28-hebergement-k3s.md).
- Non vérifié : lecteur d'écran.

## Déploiement dans k3s

Les manques notés aux lots 3 à 6 sont levés le 2026-10-30 par HOST-013 : compte Kafka et ACL, règle réseau, `KEYCLOAK_URL` et secrets clients, client `remediation-directory`, bucket des justificatifs du cluster et outbox active. Parcours vérifié dans k3s par `scripts/test-remediation.py`. Détails dans l'[hébergement k3s](28-hebergement-k3s.md).

## Lots et vérifications prévues

| Lot | Contenu | Vérification attendue |
| --- | --- | --- |
| 1, vérifié et publié | Squelette remediation-service, migration V1, droits SQL, route gateway, Compose, rôle AUDITEE et lecture `GET /remediation/me/actions` | Migration sur base vide avec Testcontainers, contraintes, test des droits, santé du service, jeton invalide refusé, parcours réel par la gateway |
| 2, vérifié et publié | audit-service : V18, outbox d’émission et de retrait, lecture interne, client `remediation-service` et rôle `REMEDIATION_SYNC` | Test Java : un événement par approbation et dérogation APPROVE, aucun après annulation de transaction, retrait sur REOPEN et DELETE, lecture interne refusée sans rôle de service, 409 sur cycle périmé |
| 3, vérifié et publié | Consommateur, lecture d’instantané et réconciliation | Test d’intégration : redélivrance sans doublon, cycle ancien ignoré, audit-service indisponible sans acquittement, suspension puis réactivation, constat absent passé Retiré |
| 4, vérifié et publié | API des suivis et actions, annuaire et client `remediation-directory`, journal | Test Java : matrice des permissions, séparation des fonctions y compris par dérogation, versions, retard avec horloge fixée, journal sans UPDATE ni DELETE |
| 5, vérifié et publié | Justificatifs dans Garage | Test : type, taille, nombre, quota concurrent, nettoyage de l’objet après refus, retrait interdit après réalisation |
| 6, vérifié localement | Notification d’affectation | Test notification-service : consommation idempotente, destinataire seul, reprise après panne du broker |
| 7, vérifié localement | BFF, contrat et interface | Tests unitaires Node, TypeScript, build, Playwright `remediation.spec.ts` : approbation, action, avancement et justificatif par l’audité, renvoi, vérification, demande de clôture, clôture, audité extérieur et utilisateur extérieur refusés |
| 8 | Documentation et publication | Ce guide passé à implémenté puis vérifié, backlog, changelog, migrations appliquées localement après sauvegarde, CI distante |

## Limites connues

- Le texte des constats est copié dans une seconde base. Sauvegarde, conservation et chiffrement des volumes, C7, doivent couvrir aussi la base remediation et son bucket.
- La lecture interne crée un couplage au moment de la consommation. Une indisponibilité d’audit-service retarde la synchronisation sans perte, le message reste en attente.
- La protection de `/internal/**` repose sur le jeton de service et le réseau interne. mTLS entre services reste prévu par ADR-0020, non déployé.
- Aucun nettoyage automatique des objets orphelins du bucket des justificatifs, comme pour les preuves de contrôle.
- Les relances et escalades de retard ne sont pas incluses. Le retard reste un affichage jusqu’à US-009 et Temporal.
- Un suivi Clôturé ne peut pas être rouvert dans cette tranche.
- Une dérogation APPROVE peut viser une mission sans validateur désigné. L’instantané porte alors `validatorSubject` nul. La migration V2 du lot 3 rend `mission_scopes.validator_subject` facultatif. Le lot 4 doit alors réserver la clôture au superadministrateur.
- Le consommateur garde une transaction et une connexion SQL ouvertes pendant la lecture d’instantané, au plus 15 secondes. Avec un pool de 5 connexions et un seul fil d’écoute, une lecture lente occupe une connexion sans bloquer l’API.
- Un instantané invalide part dans le topic `.DLT` sans être réessayé. Sa reprise demande une correction puis une nouvelle émission par réouverture et approbation.
