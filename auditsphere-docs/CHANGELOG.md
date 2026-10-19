# Journal des changements

## Notifications en temps réel : 2026-10-19

- Flux SSE authentifié via BFF, signal après commit et mise à jour de la liste et du compteur.
- Lecture synchronisée entre onglets, reconnexion et relecture de secours.
- Connexions et file de diffusion bornées, périmètre local à une instance documenté dans ADR-0012.
- Dix tests Java et trois scénarios navigateur réussis, compilation frontend validée.

## Notifications compactes : 2026-10-19

- Action Ouvrir avec flèche déplacée dans une colonne à droite du nom de mission.
- Présentation allégée sans bordure permanente, libellé accessible conservé.

## Action de notification plus visible : 2026-10-19

- Ligne cliquable, action Ouvrir la mission avec flèche et mise en évidence au survol ou au focus.
- Compilation et activation souris et clavier vérifiées sans modification des données métier.

## Notifications : titre et lecture au clic : 2026-10-19

- Ordre décroissant par réception conservé avant pagination, date et heure affichées.
- Colonne Mission avec titre autorisé, suppression de la colonne Lecture.
- Lecture enregistrée au clic et compteur actualisé avant consultation.
- Titres chargés par le BFF avec droits courants et concurrence bornée.

## Champ de commentaire de validation : 2026-10-19

- Champ pleine largeur, libellé au-dessus, aide de saisie et hauteur adaptée.
- Mise en page des boutons compatible mobile, compilation et contrôle navigateur à 1280 et 375 pixels réussis.

## Jeu métier réaliste : 2026-10-19

- Anciennes données métier locales et historiques Kafka et Temporal supprimés après sauvegarde privée.
- Six missions fictives créées par l’API, couvrant les différents états et une demande de corrections.
- Noms des quatre comptes personnalisés, identifiants, mots de passe et rôles conservés.
- Vérification des accès, de douze notifications et de quatre workflows. Contrôle TypeScript réussi.

## Priorité métier et audit continu : 2026-10-19

- Plan métier ajouté avec première tranche Risques et contrôles, permissions et critères d’acceptation.
- Roadmap et backlog réordonnés vers les preuves, constats, actions correctives puis audit continu.
- Vision, exigences et parcours alignés, relances Temporal reportées au backlog.
- Architecture événementielle existante conservée, future expérimentation de règle versionnée décrite sans la présenter comme livrée.
- Intervention documentaire uniquement, aucun code applicatif ajouté.

## Reprise DNS de la gateway : 2026-10-19

- Correction de l’échec CI Temporal après changement d’adresse Docker.
- Cache DNS du client HTTP limité à cinq secondes et connexion bornée à trois secondes.
- CI distante réussie sur 7e4c707, run 39233384313. Notifications de validation encore locales.

## Notifications de validation : 2026-10-19

- Notification interne au validateur désigné à chaque soumission.
- Événement Kafka enregistré dans la même transaction que la mission et le message Temporal.
- Type de notification persisté, anciennes affectations conservées et rejeu sans réinitialiser la lecture.
- Validation locale : 41 tests Java ciblés, 12 scénarios navigateur, compilation frontend et test réel de pannes réussis. Contrôle des secrets réussi.

## Temporal et revue durable : 2026-10-18

- Workflow MissionReviewV1 démarré par cycle de soumission, terminé par le signal de décision.
- Outbox transactionnelle, envoi ordonné, reprise sur bail et traitement des doublons.
- Worker séparé sans secrets SQL ou Keycloak, serveur Temporal local persistant et interface sur localhost:8233.
- Migration V7 reprenant les revues en attente, test de pannes ajouté à la CI et documentation mise à jour.
- PostgreSQL reste la source de vérité, échéances et notifications de revue restent hors périmètre.
- Validation avant publication : 42 tests Java sur tous les services, 12 scénarios navigateur et reprise réelle après pannes réussis.


## Approbation obligatoire : 2026-10-18

- Clôture directe refusée, soumission au validateur désigné et commentaire de décision obligatoire.
- Refus vers En cours, approbation préalable à la clôture et contrôles de version.
- Dates, affectations et désignation figées pendant la revue et après approbation.
- Migration V6 conservant les anciennes clôtures, règle SQL et interface actualisée.
- Temporal et historique complet restent à implémenter.
- Validation avant publication : 39 tests Java sur tous les services, 12 scénarios navigateur et compilation frontend réussis. Contrôle des secrets réussi, CI distante à vérifier.


## Désignation du validateur : 2026-10-18

- Choix, remplacement et retrait d’un validateur actif, réservé au responsable propriétaire avant clôture.
- Auto-désignation interdite, version contrôlée et validation Keycloak hors transaction.
- Accès de lecture limité aux missions désignées, migration V5, fiche et annuaire paginé.
- Contrat API, guide et ADR-0009 ajoutés. Temporal reste hors périmètre.
- Validation : 28 tests Java ciblés, 12 scénarios navigateur et compilation frontend réussis.


## Préparation du rôle validateur : 2026-10-18

- Rôle Keycloak AUDIT_VALIDATOR et compte validator réservés à la préparation du workflow.
- Compte de démonstration créé uniquement en configuration locale, sans réinitialisation des mots de passe existants.
- Permissions existantes conservées et testées, aucun droit global ajouté.
- Validation : 25 tests Java ciblés, jetons Keycloak réels via la gateway et initialisation neuve vérifiés.
- Temporal, désignation du validateur et approbation restent à implémenter.

## [0.8.0] : 2026-10-17

- Dates prévues à la création et modification de planification avant clôture.
- Cycle manuel Brouillon, En cours, Clôturée avec contrôle du rôle, de la propriété et de la version.
- Migration préservant les missions existantes, contraintes SQL et dates réelles des transitions.
- Fiche et liste actualisées, contrat API, ADR-0008 et guide du cycle de vie ajoutés.
- Validation avant publication : 34 tests Java et 11 scénarios navigateur réussis, TypeScript et build frontend validés.

## [0.7.1] : 2026-10-17

- Notifications lues et non lues, date persistée et marquage idempotent réservé au destinataire.
- Compteur global de non-lues dans l’en-tête, accessible sur mobile.
- Migration conservant les données, droits SQL limités à read_at et protection CSRF dans le BFF.
- Validation complète avant publication : 31 tests Java et 10 scénarios navigateur réussis, TypeScript et compilation Next.js validés.

## [0.7.0] : 2026-10-17

- Accès Notifications déplacé de la barre latérale vers l’en-tête, visible aussi sur mobile.

- Service de notifications Spring Boot et base PostgreSQL dédiée avec comptes runtime et migration distincts.
- Kafka KRaft local, événements d’affectation sans contenu métier et outbox transactionnelle.
- Reprises avec bail et consommateur idempotent, validation des pannes Kafka, service et base.
- Interface Notifications, accès par sujet JWT, pagination et actualisation.
- 29 tests Java et 10 scénarios navigateur validés localement, compilation réussie.
- Contrats, ADR-0007 et guide de test ajoutés. CI enrichie, exécution distante de cette tranche en attente.

## [0.6.0] : 2026-10-16

- Retrait du suivi Git des consignes locales pour assistants IA, documentation du projet conservée.

- Préparation de la publication des correctifs, vérification CI distante en attente et accès de maintenance pgAdmin documenté.

- Accès pgAdmin aux trois bases sur la boucle locale, ports retirés du profil production et connexions authentifiées vérifiées.

- Comptes runtime PostgreSQL limités, sauvegardes privées et migrations séparées.
- Protection Keycloak des connexions, rotation des refresh tokens et profil HTTPS distinct.
- Quotas BFF et gateway, corps bornés pendant la lecture.
- Annuaire paginé, validations ciblées et appels externes hors transaction métier.
- Sessions sans réécriture systématique et conflits explicites sur les affectations.
- ADR-0006, tests et guide de durcissement ajoutés.

## Audit sécurité et scalabilité : 2026-10-16

- Revue du code et vérification des droits PostgreSQL et paramètres Keycloak actifs.
- Quinze tests backend relancés, audit npm et scan des secrets sans détection.
- Sonde de 90 lectures BFF sans erreur, limites de portée documentées.
- Risques priorisés et plan de remédiation ajoutés, aucun correctif applicatif.

## [0.5.0] : 2026-10-16

- Sélection des auditeurs, modification des affectations et affichage des participants.
- Annuaire Keycloak en lecture seule côté Spring et validation des comptes actifs.
- Contrôles de propriété, retrait immédiat des accès et tests sur deux sessions.
- Préparation privée de l’import Keycloak compatible avec des UID Linux différents.
- Échec du smoke CI précédent constaté, nouvelle validation distante en attente.
- ADR-0005 et guide des affectations ajoutés.

## [0.4.0] : 2026-10-16

- Interface Next.js, React, TypeScript et Tailwind pour les missions.
- Client Keycloak confidentiel, session BFF PostgreSQL, PKCE, CSRF, refresh et déconnexion.
- Endpoint de liste filtrée et paginée, tests backend et navigateur.
- Configuration locale reproductible et extension du workflow CI.
- ADR-0004 et documentation de démarrage et sécurité enrichies.


## Harmonisation documentaire : 2026-10-16

- Remplacement des tirets cadratins et des points-virgules dans tous les fichiers Markdown.
- Ajout de la règle de rédaction dans AGENTS.md pour les prochaines interventions.


## Publication initiale : 2026-10-16

- Contrôle des secrets avant premier commit, exclusions Git/Docker renforcées.
- Ajout du scan Gitleaks de l’historique dans la CI et documentation du faux positif JDBC.
- Préparation de la publication vers `NossairAA/AuditSphere`.


## [0.3.0] : 2026-10-16

- Initialisation du dépôt Git et du monorepo Maven, création audit-service et api-gateway.
- PostgreSQL/Flyway, Keycloak, permissions par mission et contrat OpenAPI.
- Génération privée de la configuration locale, Compose, Dockerfile et script de démonstration.
- Sept tests d’intégration réussis et parcours réel Keycloak/gateway vérifié localement.
- Workflow CI écrit, non exécuté sur GitHub, frontend non implémenté.
- ADR-0003, guide de démarrage, backlog et roadmap actualisés.

## [0.2.0] : 2026-10-16

- Orientation Spring Boot, microservices, Kafka, workflows distribués et Kubernetes confirmée par l’utilisateur.
- Ajout de la stack de référence et ADR-0002, technologies complémentaires explicitement proposées.
- Alignement architecture, DevOps, roadmap et backlog sur cette orientation.
- Ajout de `AGENTS.md` à la racine pour enrichir la documentation à chaque intervention.
- Aucun code applicatif, test d’intégration ou déploiement effectué.

## [0.1.0] : 2026-10-16

- Création du socle documentaire AuditSphere.
- Définition de l'hypothèse métier initiale et du MVP.
- Première architecture cible, flux, exigences et roadmap.
- Ajout de modèles pour les itérations futures.

## À venir

- Validation des cas d'usage métier avec les futurs utilisateurs.
- Choix du périmètre fonctionnel V1 et des premières métriques SLO.

## Dépannage du frontend local : 2026-10-16

- Aucun processus n’écoutait sur le port 3000 alors que les services Docker fonctionnaient.
- Frontend compilé relancé en arrière-plan, réponse HTTP 200 vérifiée.
- Instructions de dépannage ajoutées au README.
