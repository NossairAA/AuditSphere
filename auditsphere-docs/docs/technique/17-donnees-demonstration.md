# Jeu de démonstration métier

**Date :** 2026-10-19
**Statut :** créé et vérifié en local

## Nettoyage réalisé

À la demande de l’utilisateur, les anciennes missions, affectations, notifications, événements outbox et historiques Temporal ont été supprimés. Les données Kafka ont également été réinitialisées pour empêcher la réapparition des anciens événements. Les schémas et migrations PostgreSQL, comptes, rôles, mots de passe et configurations sont conservés.

Une sauvegarde privée des quatre bases, des volumes Kafka et Temporal et des profils a été créée sous .local/backups avant suppression. Elle reste ignorée par Git. Les sessions web et les sessions Keycloak des quatre comptes de démonstration ont été invalidées pour recharger les nouveaux noms. Se reconnecter après le nettoyage.

## Comptes fictifs

| Connexion | Nom affiché | Fonction |
| --- | --- | --- |
| manager | Claire Martin | Responsable des six missions |
| auditor | Julien Moreau | Auditeur affecté à quatre missions |
| outsider | Sarah Benali | Auditrice affectée à quatre missions |
| validator | Thomas Laurent | Validateur désigné des six missions |

Ces noms ne représentent pas des personnes réelles. Les mots de passe restent dans .env. Le nom de connexion outsider est conservé pour compatibilité, ce compte dispose maintenant d’affectations dans le jeu de démonstration.

## Missions disponibles

| Mission | Statut | Auditeurs |
| --- | --- | --- |
| Revue du processus achats et fournisseurs | Brouillon | Julien Moreau |
| Revue des habilitations et des comptes privilégiés | En cours | Sarah Benali |
| Fiabilité des stocks et des inventaires | En attente de validation | Julien Moreau et Sarah Benali |
| Conformité des notes de frais | En cours, corrections demandées | Julien Moreau |
| Sécurisation du cycle de paie | Approuvée | Sarah Benali |
| Préparation du plan de continuité d’activité | Clôturée | Julien Moreau et Sarah Benali |

Les descriptions et périmètres illustrent des pratiques d’audit interne, sans données réelles. Les dates prévues vont du 19 octobre au 20 novembre 2026 selon la mission. Les dates techniques de création, de démarrage et de décision correspondent à la simulation, elles n’ont pas été antidatées. Les décisions approuvées et la clôture sont fictives, aucune preuve réelle n’a été examinée.

Les missions ont été créées par l’API et ont suivi les transitions autorisées. Aucun risque, contrôle, constat ou document n’a été ajouté, ces fonctionnalités restent au plan métier.

## Essayer le parcours

Se connecter avec validator et ouvrir Fiabilité des stocks et des inventaires. Le commentaire de décision et les actions de validation sont disponibles. Avec manager, la mission de paie peut être clôturée et celle des notes de frais peut être soumise à nouveau après correction.

## Vérifications

Six missions et douze notifications, aucune publication outbox en attente. Quatre workflows Temporal correspondent aux quatre soumissions de ce jeu, sans historique des anciennes missions. Les jetons Keycloak confirment les quatre noms affichés. L’API retourne six missions pour le responsable et le validateur, quatre pour chaque auditeur. Le contrôle TypeScript passe.

Les scripts de génération des nouveaux comptes et les attentes de noms dans les tests navigateur sont alignés. Le lancement habituel ne réinitialise pas les données et ne crée pas automatiquement ces six missions. Les suites de tests créent leurs propres missions synthétiques, elles n’ont pas été relancées après nettoyage afin de conserver ce jeu propre.
