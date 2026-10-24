# Constats : implémentation

**Statut :** lots 1 à 5 implémentés et vérifiés le 2026-10-24 : tests Java, tests unitaires, TypeScript et parcours navigateur en local, puis CI distante réussie sur `92dd3ef`. Publié dans `main` par la PR #3, commit de fusion `65e4331`.
**Tranche :** F-005, US-005. Règles métier dans le [plan métier](../produit/04-plan-metier.md). Choix du service dans [ADR-0021](../decisions/ADR-0021-constats-audit-service.md).

Ce document décrit le découpage technique. Les lots 1 à 4 ont été livrés sur la branche `feat/findings-lot1`, fusionnée dans `main`. Chaque lot est marqué implémenté puis vérifié avec ses preuves de test.

## Emplacement

Les constats sont ajoutés à audit-service, à côté du programme, des résultats et des preuves. Ils réutilisent le verrou de mission, les permissions de mission, l’annuaire des participants et le schéma PostgreSQL existant. Les tables et le contrôleur restent distincts du code des missions pour permettre une extraction ultérieure.

## Données, migration V17

`mission_findings` :

- id, mission_id avec suppression en cascade, reference entière unique par mission.
- title, condition_text, criterion, cause, consequence, recommendation. Les quatre derniers champs sont nullables en brouillon.
- severity parmi LOW, MODERATE, HIGH, CRITICAL.
- status parmi DRAFT, SUBMITTED, VALIDATED, REJECTED.
- version pour le verrou optimiste du constat.
- created_by, created_at, updated_by, updated_at, submitted_by, submitted_at, decided_by, decided_at, decision_comment, ever_submitted.
- Contraintes CHECK sur les longueurs, la gravité, le statut, et sur cause et recommandation non vides hors brouillon.

`mission_finding_controls` : mission_id, finding_id, control_id. Clés étrangères composites vers `mission_findings(mission_id,id)` et `mission_controls(mission_id,id)`. La base refuse donc un contrôle d’une autre mission, comme pour les liens entre contrôles et risques.

`finding_change_log` : id, mission_id, finding_id, actor_subject, action, finding_version, reason, override, previous_data, resulting_data, occurred_at. Actions prévues : CREATED, UPDATED, SUBMITTED, WITHDRAWN, VALIDATED, RETURNED, REJECTED, REOPENED, DELETED. `mission_finding_sequences` : dernière référence attribuée par mission. Le serveur l’incrémente sous verrou de mission par une insertion avec reprise sur conflit. Un numéro libéré par la suppression d’un brouillon n’est donc jamais réattribué. Un premier essai fondé sur la plus grande référence existante réattribuait ce numéro, le test l’a détecté.

`scripts/harden-databases.py` accorde uniquement SELECT et INSERT sur ce journal à audit_runtime, et SELECT, INSERT, UPDATE, DELETE sur les trois autres tables. Des contraintes CHECK garantissent aussi en base la cause et la recommandation hors brouillon, l’auteur de la soumission, l’auteur d’une décision et le motif d’un rejet ou d’un renvoi.

L’interface affichera la référence sous la forme C-01 à partir de l’entier stocké.

## API audit-service

Le contrôleur est `FindingController`. Chaque constat renvoie ses contrôles liés avec leur résultat courant, les risques déduits et `inconsistentControls`, vrai si un contrôle lié n’est pas Non conforme.

| Méthode et chemin | Usage |
| --- | --- |
| GET /missions/{id}/findings | Liste avec contrôles liés, risques déduits et indicateur de contrôle incohérent |
| GET /missions/{id}/findings/{findingId}/history | Historique des changements et décisions |
| POST /missions/{id}/findings | Création d’un brouillon |
| PUT /missions/{id}/findings/{findingId} | Modification d’un brouillon avec sa version |
| DELETE /missions/{id}/findings/{findingId}?version= | Suppression d’un brouillon jamais soumis |
| PUT /missions/{id}/findings/{findingId}/transition | Action SUBMIT, WITHDRAW, VALIDATE, RETURN, REJECT ou REOPEN, avec version et commentaire |

Chaque écriture suit le même ordre :

1. Charger la mission avec `findForUpdate`, refuser une mission supprimée ou hors périmètre par 404.
2. Vérifier rôle, affectation ou propriété, puis le statut IN_PROGRESS.
3. Charger le constat de cette mission, comparer sa version, appliquer la transition autorisée.
4. Écrire le constat, ses liens et le journal dans la même transaction.

Les erreurs suivent les conventions actuelles : 400 pour un champ invalide, un contrôle inconnu ou un commentaire manquant, 403 pour un rôle insuffisant ou une décision sur sa propre soumission, 404 hors mission, 409 pour un statut, une version ou une soumission incomplète. Le service applique `server.error.include-message=never`, aucun code de raison n’est donc transmis. Seul le refus de soumission de mission porte un code, décrit ci-dessous.

La décision par la personne qui a soumis est refusée en 403, y compris pour SUPER_ADMIN. Une décision du superadministrateur hors rôle de responsable propriétaire est enregistrée avec override à vrai, selon ADR-0014.

La mission ne change pas de version lors d’une écriture sur un constat. Deux auditeurs peuvent donc travailler sur deux constats différents sans conflit. Le verrou de mission sérialise les écritures avec les transitions de la mission.

## Soumission de mission

Le composant `PendingFindings` compte les constats DRAFT ou SUBMITTED de la mission. C’est la seule lecture des constats depuis le cycle de mission prévue par ADR-0021. `MissionController.checkSubmission` l’appelle dans les deux transactions de `submit` : la première échoue tôt, avant l’appel à l’annuaire, la transaction finale rejoue le comptage sous verrou de mission. Une écriture de constat concurrente prend le même verrou et ne peut donc pas passer entre le contrôle et le changement de statut.

Le refus retourne 409 avec le corps `{"code":"FINDINGS_PENDING","pending":n}`, produit par un gestionnaire dédié. Ce code stable et le nombre de constats en attente sont destinés à l’interface. Ils ne révèlent aucun message interne, la règle `server.error.include-message=never` reste inchangée pour les autres erreurs.

Les dérogations APPROVE et CLOSE du superadministrateur appliquent le même comptage. Sans cette règle, une mission approuvée ou clôturée directement depuis En cours figerait des constats encore en brouillon ou soumis, sans décision possible. REOPEN et DELETE ne sont pas concernés.

## BFF et interface

- Routes Next.js sous `apps/web/src/app/api/missions/[id]/findings` : liste et création, modification et suppression, `transition` et `history`. Elles passent par `src/lib/findings-api.ts`, avec session, origine, CSRF, limitation de débit et corps borné à 32 Kio, comme les routes du programme. La gateway route déjà `/missions/**` et n’a pas changé.
- Les règles pures sont isolées dans `src/lib/findings-rules.ts` : identifiants au format UUID, liste blanche des champs transmis, rôle attendu, chemin amont et messages d’erreur. Un champ forgé comme `createdBy` ou `status` n’atteint pas l’API.
- Le BFF exige AUDITOR pour écrire, soumettre et retirer, AUDIT_MANAGER pour valider, renvoyer, rejeter et rouvrir, ou SUPER_ADMIN. audit-service reste l’autorité, notamment pour l’affectation et l’auto-décision.
- La suppression reçoit la version dans un corps JSON, comme les autres suppressions de l’application. Le BFF la valide comme entier positif et la transmet en paramètre `version`.
- Les messages d’erreur sont rédigés par le BFF, aucun message amont n’est relayé. Pour la soumission et les dérogations de mission, `src/lib/api.ts` relaie seulement un corps 409 conforme à `{"code":"FINDINGS_PENDING","pending":n}`, avec un message en français. Tout autre corps 409 garde le message générique.
- Onglet Constats de la fiche de mission, entre Contrôles et Équipe, servi par `mission-findings.tsx` : liste filtrable par statut et gravité, cartes avec référence C-01, gravité, statut, champs, contrôles liés avec leur résultat, auteurs et date de mise à jour.
- Panneau latéral de saisie, réutilisant celui du programme, avec sélection des contrôles de la mission et protection contre la perte d’une saisie non enregistrée. Les champs cause et recommandation peuvent rester vides en brouillon.
- Actions affichées selon le statut et le rôle : modifier, soumettre et supprimer un brouillon, retirer une soumission, valider, renvoyer et rejeter, rouvrir. Dialogue de décision avec commentaire obligatoire sauf pour valider. La personne qui a soumis voit un message à la place des décisions.
- Avertissement quand un contrôle lié n’est pas Non conforme, rappel du nombre de constats en attente, message de lecture seule hors En cours. Historique déplié à la demande, avec les dérogations signalées.
- Les noms viennent de `/participants`. Un superadministrateur hors équipe apparaît comme Compte hors équipe pour les autres utilisateurs.
- Le refus FINDINGS_PENDING affiché par `mission-lifecycle.tsx` propose un lien Voir les constats. Les dérogations du superadministrateur affichent le message sans lien.
- Contrat `contracts/audit-api.yaml` en version 0.14.0 : quatre chemins de constats, schémas `Finding`, `FindingInput`, `FindingControl`, `FindingHistoryEntry` et `FindingsPending`, réponses 409 de la soumission et de la dérogation complétées.

## Vérifications du lot 1

- `MissionIntegrationTest#findingsFollowReviewLifecycleAndKeepAppendOnlyTrace` : création, refus de soumission incomplète, conflit de version, soumission, renvoi avec commentaire obligatoire, retrait, validation, refus du rejet d’un constat validé, réouverture, refus de suppression après soumission, historique ordonné consulté par le validateur, référence non réattribuée après suppression d’un brouillon.
- `MissionIntegrationTest#findingsEnforcePermissionsSeparationAndMissionStatus` : refus hors En cours, utilisateur extérieur, responsable et validateur en écriture, gravité invalide, liste vide ou de 21 contrôles, contrôle d’une autre mission, soumission sans contrôle Non conforme, auto-décision refusée au superadministrateur, rejet avec motif, dérogations tracées par `override`, gel pendant la validation de mission et mission supprimée.
- Suite audit-service complète : 67 tests réussis avec JDK 25 et PostgreSQL Testcontainers. `scripts/test_database_grants.py` : deux tests réussis, un test local optionnel ignoré.
- Non vérifié : migration V17 appliquée sur la base locale, droits réels de audit_runtime, BFF, interface et CI distante.

## Vérifications du lot 2

- `MissionIntegrationTest#missionSubmissionWaitsForEveryFindingDecision` : soumission refusée avec deux brouillons puis avec un constat soumis, code et nombre vérifiés, dérogations APPROVE et CLOSE refusées, soumission acceptée quand les constats sont validés ou rejetés.
- `MissionIntegrationTest#missionSubmissionCountsFindingsUnderMissionLock` : un brouillon inséré dans une transaction qui détient le verrou de mission bloque la soumission lancée pendant ce temps. Test de mutation effectué : avec le comptage limité à la première transaction, le test échoue avec 200 au lieu de 409. Code restauré ensuite.
- Suite audit-service complète : 69 tests réussis avec JDK 25 et PostgreSQL Testcontainers.
- Non vérifié : transmission du corps par le BFF, message dans l’interface, CI distante.

## Vérifications du lot 3

- `apps/web/tests/unit/findings-rules.test.mjs` : six tests sur les identifiants, la liste blanche des champs, les rôles, les chemins, les messages et le relais strict de FINDINGS_PENDING. Dix tests unitaires Node réussis au total avec Node 24.
- TypeScript et build Next.js réussis, les quatre routes de constats apparaissent dans le build.
- Contrat analysé comme YAML valide, toutes les références de schémas résolues.
- Non vérifié : appel réel à travers BFF, gateway et audit-service, car la pile locale ne contient pas l’image audit-service avec V17. Le lot 4 le couvrira avec Playwright. La CI n’exécute pas encore les tests unitaires Node, seulement TypeScript et build.

## Vérifications du lot 4

- Base locale sauvegardée dans `.local/backups/pre-v17-20261024-182232`, puis image audit-service reconstruite, V17 appliquée par `scripts/migrate-local.py` et droits rejoués. Les 49 missions existantes sont conservées. Droits vérifiés : audit_runtime peut insérer dans `finding_change_log` mais pas le modifier, et peut modifier les constats et la séquence.
- `apps/web/tests/findings.spec.ts` réussi contre le serveur de développement : création par l’auditeur, refus de soumission incomplète, correction, soumission, refus de soumission de mission avec lien vers les constats, renvoi avec commentaire, nouvelle soumission, validation, historique de six entrées, accès refusé à un utilisateur extérieur, soumission de mission acceptée, constats en lecture seule ensuite.
- Captures `.local/design/findings-desktop.png` et `findings-mobile.png` contrôlées, sans défilement horizontal à 390 pixels.
- Nettoyage vérifié : `scripts/cleanup-test-data.py` supprime liens, constats et séquence des missions de test avant les missions, et conserve le journal comme pour le programme.
- Non-régression : `lifecycle`, `withdrawal` et `validator` réussis. Lancés ensemble sur le serveur de développement, `withdrawal` et un test `validator` ont échoué sur des éléments absents, puis ont réussi seuls. Cause non confirmée, les quotas en mémoire partagés justifient déjà le lanceur isolé de la CI.
- Non couvert par le navigateur : suppression d’un brouillon, rejet et réouverture, vérifiés uniquement par les tests Java.

## Correctif après la première CI

La CI de la PR #3 a échoué sur `findings.spec.ts` : après l’enregistrement d’un brouillon, la soumission ne passait pas au statut Soumis. Le message Constat enregistré s’affichait avant le rechargement de la liste, les boutons restaient actifs avec l’ancienne version du constat et la soumission recevait 409. Le serveur local, plus rapide, masquait cette course.

Le test retarde désormais de 1,5 seconde les lectures de la liste côté auditeur. Il reproduit l’échec de la CI avec l’ancien composant. Le composant recharge maintenant la liste avant de réactiver les actions et d’afficher le message. Le test retardé réussit localement, TypeScript aussi.

## Publication et CI distante

- Exécution GitHub Actions `40113987183` de la PR #3, sur `92dd3ef`, réussie le 2026-10-24 : 69 tests audit-service, tests des scripts Python, TypeScript, build Next.js et parcours navigateur, dont `findings.spec.ts`.
- Le lanceur isolé de la CI exécute `lifecycle`, `withdrawal` et `validator` sans échec. Les échecs observés lors d’un lancement groupé local ne se sont pas reproduits, leur cause reste non confirmée.
- PR #3 fusionnée dans `main` le 2026-10-24, commit `65e4331`. La CI de `main` sur ce commit est réussie.
- Aucun déploiement hors poste local. La migration V17 n’est appliquée que sur la base locale.

## Lots et vérifications prévues

| Lot | Contenu | Vérification attendue |
| --- | --- | --- |
| 1, vérifié | Migration V17, contrôleur, journal, droits SQL | Test d’intégration Java : matrice des permissions, statuts, contrôle d’une autre mission, version périmée, refus de l’auto-décision, journal sans UPDATE ni DELETE |
| 2, vérifié | Blocage de la soumission de mission et des dérogations APPROVE et CLOSE | Test Java : refus avec un brouillon et un soumis, acceptation après validation et rejet, scénario concurrent |
| 3, vérifié hors parcours réel | BFF et contrat | Tests des primitives existantes sur les nouvelles routes, TypeScript |
| 4, vérifié | Interface | Playwright `findings.spec.ts` : création, soumission, renvoi, correction, validation, soumission de mission, utilisateur extérieur refusé. Nettoyage des données de test par identifiants exacts |
| 5, vérifié | Documentation et publication | Ce guide passé à implémenté puis vérifié, backlog, changelog, migration appliquée localement après sauvegarde, CI distante réussie et PR fusionnée |

## Limites connues

- Aucun événement Kafka dans cette tranche. Les événements `audit.finding.submitted.v1` et `audit.finding.decided.v1` via outbox seront ajoutés avec les notifications correspondantes.
- Le blocage de la soumission de mission lit les constats dans la même base. Une extraction vers un service dédié devra remplacer cette lecture par une projection ou un appel synchrone, voir ADR-0021.
- La modification d’un contrôle n’invalide pas un constat déjà validé. Le signalement reste visuel, la décision appartient au responsable.
