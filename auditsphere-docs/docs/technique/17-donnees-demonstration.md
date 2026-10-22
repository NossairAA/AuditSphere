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

Les missions ont été créées par l’API et ont suivi les transitions autorisées. À cette date, aucun risque, contrôle, constat ou document n’avait été ajouté. Le complément du 22 octobre ci-dessous enrichit le jeu de démonstration.

## Essayer le parcours

Se connecter avec validator et ouvrir Fiabilité des stocks et des inventaires. Le commentaire de décision et les actions de validation sont disponibles. Avec manager, la mission de paie peut être clôturée et celle des notes de frais peut être soumise à nouveau après correction.

## Vérifications

Six missions et douze notifications, aucune publication outbox en attente. Quatre workflows Temporal correspondent aux quatre soumissions de ce jeu, sans historique des anciennes missions. Les jetons Keycloak confirment les quatre noms affichés. L’API retourne six missions pour le responsable et le validateur, quatre pour chaque auditeur. Le contrôle TypeScript passe.

Les scripts de génération des nouveaux comptes et les attentes de noms dans les tests navigateur sont alignés. Le lancement habituel ne réinitialise pas les données et ne crée pas automatiquement ces six missions. Les suites de tests créent leurs propres missions synthétiques, elles n’ont pas été relancées après nettoyage afin de conserver ce jeu propre.

## Extension du 22 octobre 2026

Le script scripts/seed-demo-missions.py ajoute un jeu indépendant de 40 missions entièrement fictives, avec 120 risques et 160 contrôles. Les missions et comptes existants sont conservés, y compris Alae. Une sauvegarde privée des bases audit et notification précède l’exécution.

Les domaines couverts incluent achats, finance, ventes, ressources humaines, cybersécurité, conformité, logistique et continuité. Chaque mission porte une description explicite de simulation. Les approbations, résultats et commentaires ne constituent pas un audit réel.

| Scénario ajouté | Nombre |
| --- | --- |
| Brouillon | 8 |
| En cours sans décision | 7 |
| En cours après corrections demandées | 3 |
| En cours après retrait de soumission | 2 |
| En attente de validation | 7 |
| Approuvée | 7 |
| Clôturée | 6 |

Les échéances sont relatives à la première exécution et figées dans un manifeste local privé. Le jeu comprend un brouillon sans dates, des échéances futures, trois échéances au jour de création, des retards et des missions clôturées à échéance passée qui ne doivent pas apparaître dans les retards. Les dates techniques restent celles des opérations réellement effectuées, sans antidatage.

Chaque mission contient trois risques, quatre contrôles et des liens entre eux. Les probabilités et impacts couvrent les valeurs de 1 à 5. Les brouillons ont des contrôles à réaliser. Les missions en cours simples ont des résultats partiels. Les autres scénarios comportent des résultats fictifs Conforme, Non conforme et Non applicable. Aucun fichier de preuve ni constat ou action corrective fictivement présenté comme une fonctionnalité implémentée n’est ajouté.

Les missions sont créées par manager. Les affectations alternent entre auditor et outsider. Les soumissions sont adressées à validator, qui porte les décisions fictives. Kafka et Temporal suivent les chemins applicatifs habituels. Les workflows des missions en attente de validation restent normalement actifs, les décisions et retraits terminent les cycles correspondants.

### Exécution et reprise

Sans argument, python3 scripts/seed-demo-missions.py affiche le plan sans écriture. L’option --apply réalise ou reprend les créations. Les secrets sont lus dans .env et ne sont jamais affichés. Le manifeste .local/demo-missions-manifest.json conserve les identifiants et la date d’ancrage. Une mission marquée terminée est laissée intacte à la relance, même si elle a ensuite été modifiée dans l’interface. Ne pas supprimer ce manifeste pour réinitialiser le jeu. Les conflits avec des titres étrangers au jeu provoquent un arrêt, sans suppression.

Le script limite le volume à 100 missions visibles par manager, respecte les quotas et ne réessaie pas automatiquement une mutation à résultat incertain. Une relance réconcilie le programme avec les données présentes. Une interruption après une décision permet de reprendre son statut sans créer une seconde mission. Le jeu ne s’exécute pas au démarrage et n’est pas utilisé par le nettoyage des tests.

### Vérification de l’extension

Exécution terminée le 22 octobre 2026 : 40 nouvelles missions, 120 risques et 160 contrôles. Résultats : 38 conformes, 37 non conformes, 39 non applicables et 46 à réaliser. Le jeu ajouté compte 18 missions en retard et trois échéances au jour d’ancrage. Ces compteurs évoluent naturellement avec la date.

Les sept enregistrements de mission préexistants, dont un déjà supprimé, sont inchangés. Le répertoire comporte désormais 46 missions non supprimées. La seconde exécution conserve les 40 missions sans doublon. Les 40 notifications d’affectation et les 25 notifications de soumission sont présentes. Aucune publication Kafka ou Temporal du jeu ne reste en attente dans les outbox au moment du contrôle.
