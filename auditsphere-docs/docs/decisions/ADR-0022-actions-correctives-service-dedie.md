# ADR-0022 : actions correctives dans un service dédié

**Statut :** Accepté le 2026-10-24
**Date :** 2026-10-24
**Auteur :** équipe AuditSphere

## Contexte

ADR-0021 a placé les constats dans audit-service et demandé de réexaminer la frontière à la tranche F-006. Les actions correctives survivent à la clôture de la mission. Elles ont leurs propres responsables, des audités qui ne font pas partie de l’équipe d’audit, des échéances et des justificatifs. Leur suivi dure souvent plusieurs mois. Les constats, eux, sont figés après l’approbation de la mission, sauf réouverture par dérogation du superadministrateur.

Kafka n’est pas encore chiffré ni authentifié, C7 restant à déployer selon ADR-0020. Le plan métier interdit de publier des contenus sensibles dans Kafka sans nécessité.

## Options évaluées

1. Actions dans audit-service, comme les constats. Avantages : cohérence transactionnelle, aucune synchronisation, livraison rapide. Inconvénients : audit-service porte aussi un cycle qui dépasse la mission, le rôle audité y entre, la frontière cible Findings & Actions s’éloigne.
2. remediation-service alimenté par un événement par constat contenant tout son texte. Avantages : asynchrone de bout en bout. Inconvénients : textes de constats en clair dans Kafka avant C7, messages volumineux, réconciliation d’un cycle complet délicate quand chaque constat arrive séparément.
3. remediation-service alimenté par un événement d’émission sans contenu, suivi d’une lecture interne de l’instantané figé auprès d’audit-service. Avantages : aucun texte dans Kafka, réconciliation du cycle en une lecture, instantané stable car la mission est figée. Inconvénients : couplage synchrone au moment de la consommation, point d’accès interne et jeton de service à protéger.
4. remediation-service interrogeant audit-service à chaque requête. Écartée pour les mêmes raisons que l’option 3 d’ADR-0021 : couplage fort et disponibilité dépendante des deux services.

## Décision

Retenir l’option 3. Créer `remediation-service` avec sa base, son journal en ajout seul et son bucket de justificatifs. Démarrer le suivi d’un constat à l’approbation de la mission, et non à la validation du constat, car un constat validé peut encore être rouvert tant que la mission est En cours.

audit-service publie `audit.mission.findings-issued.v1` et `audit.mission.findings-withdrawn.v1` via outbox, avec un cycle d’émission incrémenté à chaque approbation. Ces noms remplacent, pour ce besoin, `audit.finding.validated.v1` du catalogue initial, qui reste proposé pour le reporting. remediation-service lit l’instantané par `GET /internal/missions/{id}/issues/{cycle}` avec un jeton client credentials. Il publie `audit.action.assigned.v1` pour notification-service.

## Conséquences et risques

- Premier service métier hors audit-service, avec projection, idempotence et cohérence éventuelle, conformément à l’orientation du projet.
- Nouveau déploiement, nouvelle base, nouveau bucket et deux clients Keycloak à exploiter, sauvegarder et durcir.
- Les textes des constats sont copiés dans une seconde base. Les exigences de conservation et de chiffrement s’y appliquent.
- La synchronisation dépend de la disponibilité d’audit-service au moment de la consommation. Le message reste non acquitté et repris, sans perte.
- La lecture interne est la seule dépendance synchrone entre les deux services. Toute autre lecture croisée exigera un nouvel ADR.

## Acceptation et vérifications restantes

- Accepté par l’utilisateur le 2026-10-24 : service dédié et démarrage du suivi à l’approbation de la mission.
- Décision acceptée, non implémentée. Restent à vérifier par les tests des lots 2 et 3 du [guide technique](../technique/27-actions-correctives.md) : émission dans la transaction d’approbation, redélivrance sans doublon, reprise après indisponibilité d’audit-service, réconciliation après réouverture.

## Références

- [Plan métier, tranche actions correctives](../produit/04-plan-metier.md)
- [Implémentation proposée](../technique/27-actions-correctives.md)
- [ADR-0021](ADR-0021-constats-audit-service.md), [ADR-0007](ADR-0007-notifications-outbox.md), [ADR-0014](ADR-0014-superadmin.md), [ADR-0020](ADR-0020-deploiement-interne-securise.md)
