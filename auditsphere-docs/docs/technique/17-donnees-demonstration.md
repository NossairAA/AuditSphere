# Jeu de démonstration métier

**Date :** 2026-10-19, révisé le 2026-10-29
**Statut :** jeu v2 implémenté et vérifié en local le 2026-10-28. Déployé dans k3s et peuplé dans le cluster le 2026-10-29

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

## Jeu v2 du 28 octobre 2026 : contenu réaliste et justificatifs

À la demande de l’utilisateur, le jeu de 40 missions est réécrit pour représenter une direction de l’audit interne en activité. Il remplace le jeu `auditsphere-demo-expansion-v1`, dont les textes étaient génériques et sans constat ni preuve.

### Contexte fictif

- Entreprise : Groupe Alvéane, ETI fictive de négoce et de fabrication de matériel électrique, avec un siège à Lyon et trois filiales : distribution, industrie et centre de services partagés.
- Outils cités : SAP S/4HANA, SIRH, Active Directory, ServiceNow, Kyriba, GED et Microsoft 365. Les références de commande, de contrat ou d’incident sont inventées.
- Équipe : les comptes de démonstration existants. Les autres personnes citées dans les documents sont inventées, leurs adresses sont en `example.invalid`.
- Aucune donnée réelle : ni IBAN, ni numéro de sécurité sociale, ni SIREN. Chaque description de mission se termine par la mention `Données fictives de démonstration (auditsphere-demo-v2)`. Chaque justificatif porte cette mention en pied de page.

### Contenu

| Élément | Nombre |
| --- | --- |
| Missions | 40, mêmes titres et mêmes scénarios de cycle que le jeu v1 |
| Risques | 122, avec événement, cause, effet et exposition chiffrée |
| Contrôles | 176, avec population, source, taille d’échantillon et méthode |
| Résultats enregistrés | 121 : 46 conformes, 63 non conformes et 12 non applicables, 55 contrôles restent à réaliser |
| Justificatifs | 137 : 117 PDF et 20 captures PNG, 659 ko au total |
| Constats | 63 : 43 validés, 3 rejetés, 6 soumis et 11 brouillons, dont 6 critiques |

Le journal des constats montre 18 renvois au rédacteur, 3 rejets et 3 réouvertures après rejet de la mission par le validateur. Les missions en cours ont deux contrôles conclus et des justificatifs déjà réunis sur un troisième. Une mission soumise ne contient aucun constat brouillon ou soumis, comme l’API l’exige.

Le taux de non-conformité des contrôles testés atteint 52 %. Il est plus élevé que dans un audit courant, afin que chaque mission avancée illustre au moins un constat.

### Justificatifs

Trois types de pièces sont générés à chaque peuplement :

- Tableau PDF : échantillon testé, extraction ou rapprochement, avec les lignes en exception surlignées et les notes de l’auditeur.
- Document PDF : procès-verbal, note, procédure, courriel ou chronologie d’incident.
- Capture PNG : écran d’application avec les enregistrements en anomalie surlignés.

Chaque pièce porte l’en-tête du groupe, la mission, la référence de feuille de travail `AUD-<année>-<numéro> / C<contrôle>`, l’auteur et la date. Le dépôt passe par l’API habituelle : type détecté, empreinte SHA-256, quota par mission et auteur réel. Le dépôt est fait par l’auditeur affecté qui a conclu le contrôle. Une mission confiée à deux auditeurs répartit les contrôles entre eux.

### Dates relatives

Les textes utilisent des jetons tels que `{date:-30}`, `{month:-1}`, `{quarter:-1}` ou `{year:0}`. Le peuplement les remplace à partir de la date d’ancrage du manifeste. Après chaque réinitialisation nocturne, les périodes auditées, les dates des pièces et les trimestres cités restent donc cohérents avec la date du jour.

### Fichiers

- `scripts/demo_dataset.py` : les 40 missions, en données seules. Le script le lit par `ast.literal_eval`, sans l’exécuter.
- `scripts/demo_documents.py` : génération des PDF et des PNG avec la seule bibliothèque standard, car le CronJob tourne dans une image Python nue. Les PDF utilisent les polices standard Helvetica. Les PNG utilisent l’atlas de glyphes de `scripts/demo_font.py`.
- `scripts/demo_font.py` : glyphes pré-rendus de la police Inter, sous licence SIL Open Font License 1.1, et chasses d’Helvetica. Le fichier est régénéré par `scripts/build-demo-font.py`, outil de développement qui nécessite Pillow.
- `scripts/seed-demo-missions.py` : moteur de peuplement. `--render <dossier>` écrit tous les justificatifs sans appeler l’API, pour relecture.

Le jeu de données pèse 1,07 Mo en clair, au-delà de la limite de 1 Mio d’un ConfigMap. `scripts/k3s-deploy-services.sh` le transmet compressé par gzip et encodé en base64. Le chart le monte sous `demo_dataset.py.gz`. Le ConfigMap rendu pèse 356 ko.

`.gitattributes` marque `scripts/demo_dataset.py` et `scripts/demo_font.py` en `linguist-generated`. GitHub les exclut ainsi des statistiques de langages et replie leurs diffs dans les pull requests. Sans ce marquage, le jeu de données comptait pour l’essentiel des 61 % de Python affichés, alors qu’il ne contient aucun code. Les fichiers restent suivis par Git, car le déploiement du chart `demo-reset` les lit dans le dépôt.

### Reprise

La reprise reste idempotente. Les risques sont retrouvés par titre, les contrôles par objectif, les justificatifs par nom de fichier et les constats par titre. Un renvoi déjà joué est détecté dans l’historique du constat. Résultats, preuves et constats ne sont plus touchés après un rejet ou un retrait de la mission.

### Vérification locale du 2026-10-28

- Les 40 missions du jeu v1 ont été retirées par `scripts/cleanup-test-data.py`, après sa sauvegarde privée automatique. Le manifeste v1 est conservé sous `.local/demo-missions-manifest.v1.json`.
- Premier essai arrêté au neuvième dossier sur une erreur 500 : le `.env` local visait encore Garage en HTTP clair, refusé en 400 par Caddy depuis ADR-0028. L’URL locale est passée en HTTPS, celle déjà utilisée par k3s. La reprise a terminé les 32 missions restantes en 7 minutes 42 secondes.
- Contrôles en base : statuts attendus (8 brouillons, 12 en cours, 7 en attente, 7 approuvées, 6 clôturées) et compteurs du tableau ci-dessus.
- Le validateur a relu les cinq justificatifs d’une mission approuvée par l’API : signatures PDF et PNG valides, empreintes identiques à celles enregistrées.
- Une seconde exécution conserve les 40 missions sans doublon.
- audit-service n’a pas redémarré et n’a pas été arrêté par manque de mémoire. Sa mémoire anonyme atteignait 438 Mio sur sa limite locale de 448 Mio à la fin du peuplement.
- Non vérifié : exécution du CronJob dans k3s avec le chart modifié, et mémoire d’audit-service pendant ce peuplement plus lourd. À contrôler au premier déploiement.

### Vérification dans k3s du 2026-10-29

- Réinitialisation manuelle par `kubectl create job --from=cronjob/demo-reset demo-reset-manual-2`, à la demande de l’utilisateur, sans attendre l’exécution planifiée de 05:00.
- Job terminé en 9 minutes 19 secondes, contre environ 5 minutes avec le jeu v1. Le journal annonce 40 missions réconciliées.
- Contrôles dans la base `audit` : 40 missions, 122 risques, 176 contrôles, 63 constats et 137 justificatifs. Statuts : 8 brouillons, 12 en cours, 7 en attente, 7 approuvées, 6 clôturées. 37 missions citent le Groupe Alvéane dans leur description ou leur périmètre.
- audit-service sans redémarrage pendant le Job : compteur inchangé, dernier arrêt antérieur à la réinitialisation. Mémoire de travail à 565 Mio sur 768 juste après le peuplement.
- Nœud à 95 % de sa mémoire allouable selon `kubectl top node` après le Job.
- Non vérifié : affichage par le nom public, pic de mémoire d’audit-service pendant le peuplement, lecture des justificatifs depuis Garage dans le cluster.
