# Constats : implémentation

**Statut :** lot 1 implémenté et vérifié par les tests Java le 2026-10-24. Lots 2 à 5 proposés, non implémentés.
**Tranche :** F-005, US-005. Règles métier dans le [plan métier](../produit/04-plan-metier.md). Choix du service dans [ADR-0021](../decisions/ADR-0021-constats-audit-service.md).

Ce document décrit le découpage technique. Seul le lot 1 est livré sur la branche `feat/findings-lot1`, non publiée. Chaque lot est marqué implémenté puis vérifié avec ses preuves de test.

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

Les erreurs suivent les conventions actuelles : 400 pour un champ invalide, un contrôle inconnu ou un commentaire manquant, 403 pour un rôle insuffisant ou une décision sur sa propre soumission, 404 hors mission, 409 pour un statut, une version ou une soumission incomplète. Le service applique `server.error.include-message=never`, aucun code de raison n’est donc transmis. Le lot 2 devra choisir comment distinguer FINDINGS_PENDING des autres 409 sans exposer de message interne.

La décision par la personne qui a soumis est refusée en 403, y compris pour SUPER_ADMIN. Une décision du superadministrateur hors rôle de responsable propriétaire est enregistrée avec override à vrai, selon ADR-0014.

La mission ne change pas de version lors d’une écriture sur un constat. Deux auditeurs peuvent donc travailler sur deux constats différents sans conflit. Le verrou de mission sérialise les écritures avec les transitions de la mission.

## Soumission de mission

`MissionController.checkSubmission` compte les constats DRAFT ou SUBMITTED de la mission. La soumission est refusée en 409 avec FINDINGS_PENDING si ce nombre n’est pas nul. Le contrôle est rejoué dans la transaction finale de `submit`, après l’appel à l’annuaire, sous verrou de mission. Une écriture de constat concurrente ne peut donc pas passer entre le contrôle et le changement de statut.

## BFF et interface

- Routes Next.js sous `apps/web/src/app/api/missions/[id]/findings`, avec session, origine, CSRF, limitation de débit et corps borné à 32 Kio, comme les routes du programme.
- Composant `mission-findings.tsx` dans la fiche de mission : liste filtrable par statut et gravité, formulaire à la demande, sélection des contrôles de la mission, avertissement sur un contrôle incohérent.
- Dialogue de décision avec commentaire obligatoire pour renvoyer, rejeter et rouvrir. Historique en lecture sur la fiche du constat.
- Message explicite quand la soumission de mission est bloquée par des constats en attente, avec lien vers la section Constats.
- Contrat `contracts/audit-api.yaml` mis à jour avec les nouveaux chemins.

## Vérifications du lot 1

- `MissionIntegrationTest#findingsFollowReviewLifecycleAndKeepAppendOnlyTrace` : création, refus de soumission incomplète, conflit de version, soumission, renvoi avec commentaire obligatoire, retrait, validation, refus du rejet d’un constat validé, réouverture, refus de suppression après soumission, historique ordonné consulté par le validateur, référence non réattribuée après suppression d’un brouillon.
- `MissionIntegrationTest#findingsEnforcePermissionsSeparationAndMissionStatus` : refus hors En cours, utilisateur extérieur, responsable et validateur en écriture, gravité invalide, liste vide ou de 21 contrôles, contrôle d’une autre mission, soumission sans contrôle Non conforme, auto-décision refusée au superadministrateur, rejet avec motif, dérogations tracées par `override`, gel pendant la validation de mission et mission supprimée.
- Suite audit-service complète : 67 tests réussis avec JDK 25 et PostgreSQL Testcontainers. `scripts/test_database_grants.py` : deux tests réussis, un test local optionnel ignoré.
- Non vérifié : migration V17 appliquée sur la base locale, droits réels de audit_runtime, BFF, interface et CI distante.

## Lots et vérifications prévues

| Lot | Contenu | Vérification attendue |
| --- | --- | --- |
| 1, vérifié | Migration V17, contrôleur, journal, droits SQL | Test d’intégration Java : matrice des permissions, statuts, contrôle d’une autre mission, version périmée, refus de l’auto-décision, journal sans UPDATE ni DELETE |
| 2 | Blocage de la soumission de mission | Test Java : refus avec un brouillon et un soumis, acceptation après validation et rejet, scénario concurrent |
| 3 | BFF et contrat | Tests des primitives existantes sur les nouvelles routes, TypeScript |
| 4 | Interface | Playwright `findings.spec.ts` : création, soumission, renvoi, correction, validation, soumission de mission, utilisateur extérieur refusé. Nettoyage des données de test par identifiants exacts |
| 5 | Documentation | Ce guide passé à implémenté puis vérifié, backlog, changelog, migration appliquée localement après sauvegarde |

## Limites connues de la proposition

- Aucun événement Kafka dans cette tranche. Les événements `audit.finding.submitted.v1` et `audit.finding.decided.v1` via outbox seront ajoutés avec les notifications correspondantes.
- Le blocage de la soumission de mission lit les constats dans la même base. Une extraction vers un service dédié devra remplacer cette lecture par une projection ou un appel synchrone, voir ADR-0021.
- La modification d’un contrôle n’invalide pas un constat déjà validé. Le signalement reste visuel, la décision appartient au responsable.
