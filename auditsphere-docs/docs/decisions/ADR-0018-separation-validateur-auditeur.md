# ADR-0018 : séparation du validateur et des auditeurs affectés

Date : 2026-10-23
Statut : accepté, implémenté et vérifié en tests locaux

## Décision

Le validateur d'une mission ne peut être ni son propriétaire ni un auditeur affecté. La règle s'applique à la création, au remplacement des affectations, à la désignation du validateur, à la soumission et à la revue. Les nouveaux conflits reçoivent 400 et une mission déjà incohérente reçoit 409 lors de la soumission ou de la revue.

La migration V16 ajoute des déclencheurs sur missions et mission_assignments. Elle verrouille les deux tables pendant le contrôle des données existantes et l'installation des déclencheurs. Les deux chemins concurrents verrouillent ensuite la ligne de mission avant de contrôler l'affectation. Une mission déjà incohérente arrête la migration avec une erreur explicite. L'opérateur doit examiner et corriger ces lignes avant de relancer Flyway, sans suppression automatique de données.

La dérogation administrative APPROVE reste possible pour un superadministrateur distinct du propriétaire, du validateur et des auditeurs affectés. Elle exige une raison et conserve sa trace. Le superadministrateur qui participe à la mission reçoit 409, même s'il possède le rôle privilégié. CLOSE, REOPEN et DELETE gardent leurs règles actuelles.

## Conséquences et vérification

La vérification des nouveaux choix reste dans l'API pour rendre une erreur compréhensible. Les déclencheurs protègent également les écritures SQL directes et la concurrence entre deux transactions. Les données historiques incohérentes demandent une décision métier avant migration.

Le test MissionIntegrationTest vérifie les trois chemins API, les deux sens d'écriture SQL, le refus d'approbation personnelle et l'approbation par un autre administrateur. Il ne constitue pas un essai de déploiement sur une base de production existante.
