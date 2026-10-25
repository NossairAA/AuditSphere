# 09 : Backlog initial

**Statut :** priorités métier révisées le 2026-10-19.

## Revue de sécurité du 22 octobre 2026

Les constats C1 à C18 sont suivis dans le [compte rendu des correctifs](../technique/25-correctifs-securite.md). Les protections applicatives sont implémentées localement. Les vérifications des 23 et 24 octobre comptent 27 tests Playwright réussis, avec un scénario de preuve à 10 Mio bloqué par Garage distant injoignable. C7 reste un chantier de déploiement distinct décrit par ADR-0020, proposé et non déployé.

| Constats | Évolution | État |
| --- | --- | --- |
| C1, C17 | Royaume de production isolé sans comptes démo, OTP obligatoire et grants directs désactivés | Tests ciblés et Keycloak jetable vérifiés, production non déployée |
| C2 | Action email sans mot de passe dans l'API, adresse existante immuable | Tests API et navigateur réussis, SMTP de production à vérifier |
| C6, C13 | Séparation validateur et auditeur, dérogation APPROVE limitée, équipe figée après exécution | Tests API, SQL concurrents et navigateur réussis, migration locale V16 appliquée |
| C3, C4, C16 | Dépôts et flux bornés, limites de session et proxy | Implémenté, tests ciblés réussis |
| C5 | Messages invalides en DLT sans perte silencieuse | Scénario Kafka avec DLT et trois pannes réelles réussi |
| C8, C9, C10, C11, C14 | Empreinte, droits du journal, révocation, quota et annuaire | Tests d’intégration réussis, droits du journal appliqués et vérifiés localement |
| C12, C15 | CSP à nonce et sessions chiffrées | Implémenté, tests ciblés réussis |
| C18 | Actions et images épinglées | Vérifié par les tests de configuration |
| C7 | TLS et identités internes, persistance Temporal, chiffrement des volumes | ADR proposé, reste à déployer et vérifier |

## Correctifs de sécurité du 23 octobre 2026

- C3, vérification navigateur : Un dépôt anonyme au corps volontairement inachevé reçoit 401 immédiatement, avant tamponnement. Six tests de primitives et quatre tests de lecture/admission réussis. Le parcours de preuve à 10 Mio franchit BFF et gateway mais échoue sur le stockage Garage distant injoignable. Il reste à rejouer quand ce service est disponible.
- C6, vérification navigateur : Parcours superadmin complet réussi : refus API409 de sa propre approbation, option HTML désactivée et dérogations autorisées sur une mission distincte. L’assertion cible la propriété native de l’option.
- C12, vérification complémentaire : Proxy CSP réservé aux pages, corps de pages bornés à 32768 octets et HSTS conservé sur toutes les routes au build HTTPS. Déconnexion autorisée vers la seule origine OIDC configurée. Cinq tests de primitives et parcours réel de déconnexion Keycloak réussis, build et TypeScript vérifiés.
- C10, vérification complémentaire : Le filtre de limites précède la consultation du registre de révocation. Test composé avec 245 requêtes valides puis révoquées : au-delà de 240 requêtes, aucun appel supplémentaire au registre. Trois tests gateway réussis.
- C7 : ADR proposé pour Kafka SASL_SSL et ACL, Temporal avec mTLS, TLS Garage et chiffrement des preuves. Aucun déploiement de production ni modification de secrets. Validations locales détaillées dans le guide des correctifs.
- C18 : Images externes épinglées par digest SHA-256 et actions GitHub par SHA. Références vérifiées auprès des registres et dépôts, test de non-régression réussi. Images PostgreSQL Testcontainers exécutées avec succès.
- C17 : Politique de mot de passe de 14 caractères et OTP requis pour tous les utilisateurs de production, grants mot de passe désactivés pour tous les clients. Tests unitaires et configuration idempotente sur Keycloak jetable réussis. Parcours privilégié réel de production restant à valider.
- C16 : Trois flux SSE par utilisateur, plafond global configurable de 200 par instance. Tests de saturation et libération des places réussis.
- C15 : Jetons chiffrés par AES-256-GCM avec hachage de session authentifié. Anciennes sessions locales supprimées après sauvegarde. Tests de retour, altération et échange de ligne réussis. Clé dédiée obligatoire en production, dérivation locale transitoire documentée.
- C14 : Nom des auteurs de résultats et preuves résolu depuis le sujet authentifié dans l’annuaire. Test avec nom trompeur dans le jeton réussi.
- C13 : Affectations autorisées uniquement en brouillon et en cours. Test de refus sur mission clôturée réussi.
- C12 : CSP avec nonce par requête, interdiction des cadres et objets, rendu dynamique Next.js. HSTS réservé à HTTPS. Test des directives, unicité du nonce et HSTS réussi, build web validé.
- C11 : Quota cumulé de preuves par mission configurable, 100 Mio par défaut, contrôlé sous verrou de mission. Dépassement refusé et objet orphelin supprimé. Test de dépassement et nettoyage réussi.
- C10 : Passerelle et notifications consultent le registre central avant chaque requête authentifiée. Révocation refusée et indisponibilité traitée en refus fermé. Tests dédiés réussis, délai limité à la vérification pour conserver les flux SSE.
- C9 : UPDATE du journal limité à outcome, completed_at, target_subject et requested_changes. Privilèges appliqués et vérifiés localement après sauvegarde, acteur non modifiable et résultat modifiable.
- C8 : Calcul SHA-256 et contrôle de taille pendant le téléchargement, journalisation et interruption en cas de divergence. Test de contenu altéré réussi. Des octets peuvent avoir été transmis avant détection, une clé S3 de lecture distincte reste à étudier.
- C5 : Messages invalides transférés en DLT avec acquittement, reprise infinie conservée pour les pannes de base. Tests unitaires et scénario Kafka avec message invalide suivi du valide sur la même clé réussis, y compris les pannes Kafka, base et service.
- C4 : Limites de connexion par IP de proxy explicitement fiable, refus HTTPS si cette configuration manque. Session réelle résolue avant son compteur. Plafond IP de la passerelle configurable. Tests de séparation des IP réussis. Le mode HTTP local conserve une limite anonyme partagée car Next.js ne fournit pas la socket.
- C3 : Dépôts bornés à deux par utilisateur et huit par instance au BFF et à la passerelle, lecture BFF limitée à trente secondes et onze Mio. Tests de concurrence, annulation et lecture lente réussis.
- C6 : Séparation validateur et auditeur en API et en base, transactions concurrentes vérifiées. Dérogation APPROVE refusée au propriétaire, au validateur et aux affectés. Migration V16 appliquée localement après sauvegarde.
- C2 : Création et réinitialisation par action email Keycloak, aucun mot de passe retourné. Adresse email existante immuable pour empêcher le détournement de la réinitialisation. Tests API et client Keycloak réussis, SMTP de production non vérifié.
- C1 : Royaume sans utilisateurs de démonstration, projet Compose et volumes distincts, refus du provisionnement si un compte démo subsiste. Tests de configuration et Keycloak jetable vérifiés, aucun déploiement de production.
## Priorité active

Correctif CI de quotas partagés entre tests navigateur vérifié localement : 16 tests réussis avec le lanceur isolé. Validation distante nécessaire après publication.

Le [superadministrateur](../technique/20-superadmin.md) et les dérogations explicites sont vérifiés localement, avant la reprise du programme métier.

La sélection facultative du validateur dès la création d’une mission est implémentée et vérifiée localement le 2026-10-22. Test Java ciblé, compilation frontend et scénario navigateur réussis. Voir le [guide de désignation](../technique/12-designation-validateur.md).

La [fiche de mission](../technique/19-fiche-mission.md) est réorganisée et vérifiée localement avant la reprise des fonctionnalités métier. La navigation entre sections, les formulaires à la demande et le suivi séparé sont également validés sur ordinateur et mobile.

Le retrait des soumissions et la suppression des brouillons sont implémentés et vérifiés en local le 2026-10-20. Voir le [guide](../technique/18-retrait-suppression.md).

US-003 et F-003 sont implémentés et vérifiés localement dans le [programme d’audit](../technique/21-programme-audit.md). La saisie des [résultats des contrôles](../technique/22-resultats-controles.md) est implémentée, avec migration, tests du service, parcours navigateur et compilation frontend vérifiés en local. Les [preuves privées](../technique/23-preuves-controles.md) sont également implémentées et vérifiées localement. La prochaine tranche métier porte sur les constats. Son cadrage métier est rédigé dans le plan métier, son API est implémentée et vérifiée par tests Java selon le [guide des constats](../technique/26-constats.md) et ADR-0021 accepté. Le blocage de la soumission de mission est aussi vérifié par tests Java. Les routes BFF et le contrat sont vérifiés par tests unitaires, TypeScript et build. L’onglet Constats est vérifié par un parcours navigateur, migration V17 appliquée localement après sauvegarde. La CI distante est réussie sur `92dd3ef` et la PR #3 est fusionnée dans `main`. La tranche suivante, F-006 actions correctives, est cadrée dans le plan métier et le [guide des actions correctives](../technique/27-actions-correctives.md), avec ADR-0022 accepté. Le périmètre livré concerne les risques et contrôles rattachés à une mission. Les critères, permissions, limites et questions ouvertes figurent dans la [fiche métier](../produit/04-plan-metier.md).

| Ordre | Périmètre | Critère de sortie | État |
| --- | --- | --- | --- |
| 1 | F-003, US-003 : programme risques et contrôles | Programme préparé par le responsable, consultable par les auditeurs, références et accès isolés par mission | Vérifié localement |
| 2 | Exécution des contrôles, F-004 et US-004 | Résultats et preuves versionnées, consultables uniquement par les personnes autorisées | Résultats et ajout de preuves vérifiés localement |
| 3 | F-005, US-005 : constats | Anomalie documentée avec gravité, cause, recommandation et revue | Vérifié localement et par la CI distante le 2026-10-24, publié dans `main` |
| 4 | F-006, US-006 et US-007 : actions correctives | Responsable, échéance, justificatif et vérification avant clôture | Cadré le 2026-10-24, ADR-0022 accepté, lot 1 du socle vérifié et publié dans `main`, lot 2 d’émission vérifié localement, lots 3 à 8 à réaliser |
| 5 | US-011 : première règle d’audit continu | Exécution reproductible et anomalies sans doublon, revue avant constat officiel | À réaliser après le parcours manuel |

Les notifications de soumission sont publiées dans `4cd8d43`. VALID-005 reste proposé, hors de la prochaine tranche. Les sections historiques suivantes décrivent les jalons passés, les fonctionnalités métier nouvelles ci-dessus ne sont pas encore livrées.

## Catalogue des user stories

| ID | Epic / user story | Priorité | État |
| --- | --- | --- | --- |
| US-001 | Comme auditeur, je peux me connecter via SSO. | P0 | Proposé |
| US-002 | Comme responsable, je peux créer et affecter une mission. | P0 | Proposé |
| US-003 | Comme auditeur, je peux lier un contrôle à une mission. | P0 | Proposé |
| US-004 | Comme audité, je dépose une preuve autorisée. | P0 | Proposé |
| US-005 | Comme auditeur, je crée et soumets un constat. | P0 | Proposé |
| US-006 | Comme responsable d'action, je renseigne l'avancement. | P0 | Proposé |
| US-007 | Comme validateur, je clôture un constat après revue. | P0 | Proposé |
| US-008 | Comme responsable, j'exporte un rapport de mission. | P0 | Proposé |
| US-009 | Comme auditeur, je suis notifié d'un retard. | P1 | Proposé |
| US-010 | Comme responsable, je consulte les indicateurs consolidés. | P1 | Proposé |
| US-011 | Comme auditeur, je configure un contrôle récurrent. | P1 | Planifié après les actions correctives |
| US-012 | Comme analyste, je compare deux versions de règles sur l'historique. | P2 | Proposé |

Pour chaque user story ajoutée, utiliser [le modèle de fonctionnalité](../templates/fiche-fonctionnalite.md) et renseigner les critères d'acceptation.

## Fondations techniques : ordre de réalisation

| ID | Livrable | Critère d’acceptation | État |
| --- | --- | --- | --- |
| TECH-001 | Stack et convention documentaire | Stack Spring décrite et ADR présents | Vérifié (documentation uniquement) |
| TECH-002 | Monorepo et build Maven | Build local réussi, workflow CI écrit, exécution distante en attente | En cours |
| TECH-003 | Audit service, Flyway et PostgreSQL | Migration sur base vide et test Testcontainers réussi | Vérifié localement |
| TECH-004 | Keycloak, gateway et permissions | Jeton invalide refusé, mission hors périmètre inaccessible | Vérifié localement (API) |
| TECH-005 | Kafka et outbox | Reprise après panne, aucun effet métier doublé après redélivrance | Vérifié localement, jalon notifications |
| TECH-006 | Workflow Temporal | Validation durable et reprise du worker, expiration à ajouter | Implémenté localement, échéances proposées |
| TECH-007 | Observabilité | Trace corrélée HTTP/Kafka et métriques consultables | Proposé |
| TECH-008 | CI/CD et Kubernetes | Image analysée, déploiement Helm/GitOps et rollback testés | En cours : images analysées en CI par HOST-001, cible k3s par ADR-0023 |
| TECH-009 | Migration vers Spring Boot 4 | Spring Framework 7, dépendances à jour, analyse Trivy bloquante sans vulnérabilité haute ou critique corrigeable | Implémenté et vérifié localement par [ADR-0024](../decisions/ADR-0024-migration-spring-boot-4.md) : Spring Boot 4.0.9, Spring Cloud 2025.1.4, Trivy sans vulnérabilité corrigeable sur les quatre images Java, parcours Playwright réussis. CI distante réussie sur `main` le 2026-10-25 (`2743acb`), analyse bloquante comprise, cinq images publiées |

## Hébergement privé avec k3s

Décision prise le 2026-10-24 par [ADR-0023](../decisions/ADR-0023-hebergement-prive-k3s.md). Détail et critères de sortie dans le [guide d'hébergement](../technique/28-hebergement-k3s.md).

| ID | Livrable | État |
| --- | --- | --- |
| HOST-000 | Préparation de l'hôte : mémoire vidéo, ports d'administration, réseau, nom neutre | En cours : port d'administration refermé et vérifié, nom neutre appliqué et vérifié le 2026-10-25, mémoire vidéo non réglable dans le BIOS. Liaison réseau à faire |
| HOST-001 | Images légères, Dockerfile web, réglages JVM, GHCR, Trivy, mémoire mesurée | Implémenté et mesuré localement, analyse bloquante depuis TECH-009, CI distante et publication GHCR vérifiées sur `main` le 2026-10-25 |
| HOST-002 | k3s à un nœud, API et entrée par Tailscale uniquement | Implémenté et vérifié le 2026-10-25 : k3s `v1.36.5+k3s1`, API joignable par Tailscale seulement, redémarrage réussi, opérateur Tailscale avec entrée HTTPS testée |
| HOST-003 | PostgreSQL unique, Kafka, Temporal, Garage hors cluster | Implémenté et vérifié le 2026-10-25 : isolation SQL, redémarrage complet, Garage joignable depuis les pods. Droits sur les tables en phase 4, NetworkPolicy en phase 8 |
| HOST-004 | Charts Helm, Keycloak, frontend, Traefik derrière l'entrée Tailscale | En cours : lot A, services Spring avec migrations et droits SQL, et lot B, Keycloak sur l'entrée `auth`, implémentés et vérifiés le 2026-10-25. Lots C à E à faire |
| HOST-005 | GitOps Argo CD minimal et Sealed Secrets | Proposé |
| HOST-006 | Observabilité par paliers et alertes | Proposé |
| HOST-007 | Sauvegardes hors machine et restauration vérifiée | Proposé |
| HOST-008 | Sécurité C7 sur le cluster selon ADR-0020 | Proposé |
| HOST-009 | Second nœud, essai AKS ou EKS. Accès public déplacé vers HOST-010 | Proposé |
| HOST-010 | Exposition publique par Cloudflare Tunnel selon ADR-0025, domaine délégué à Cloudflare | Proposé, après HOST-004, HOST-006, HOST-007 et HOST-008 |

## Avancement fonctionnel du jalon 1

US-001 et US-002 sont **En cours** : authentification API et création/consultation avec affectations démontrées. Connexion web, dates de planification, édition et validation d’existence des affectations restent à réaliser. Voir le [compte rendu technique](../technique/04-premier-jalon.md).

## Jalon 2 : interface locale

| ID | Livrable | État |
| --- | --- | --- |
| WEB-001 | Connexion Keycloak via BFF, sessions, refresh, CSRF et déconnexion | Vérifié localement |
| WEB-002 | Liste des missions filtrée côté backend et pagination | Vérifié localement |
| WEB-003 | Formulaire de création réservé au responsable et fiche mission | Vérifié localement |
| WEB-004 | États vide, refus, erreur et mobile | Vérifié localement |
| WEB-005 | Affectation d’auditeurs depuis l’interface avec annuaire | Vérifié localement |
| WEB-006 | Exécution CI GitHub du jalon frontend | Vérifié sur 69de451, nouvelle tranche Kafka à publier |

US-001 couvre maintenant la connexion web locale. US-002 reste partielle pour les dates métier et la modification des affectations.

## Jalon 3

Affectations web, annuaire, contrôles de propriété et retrait d’accès vérifiés localement. US-002 reste partielle pour les dates métier. La CI distante et les notifications Kafka restent ouvertes. Voir le [jalon affectations](../technique/06-affectations.md).

## Remédiations issues de l’audit

| ID | Action | État |
| --- | --- | --- |
| SEC-01 | Séparer comptes PostgreSQL runtime et migration | À réaliser, P1 |
| SEC-02 | Profil IAM de production, TLS et protection des connexions | À réaliser avant exposition, P1 |
| SEC-03 | Limites de débit, de corps et de concurrence | À réaliser, P1 |
| SEC-04 | Protection des jetons persistés et politique de renouvellement | À réaliser avant données sensibles, P1 |
| SCALE-01 | Transactions courtes sans attente annuaire | À réaliser, P1 |
| SCALE-02 | Validation ciblée et réduction des appels Keycloak | À réaliser, P1 |
| SCALE-03 | Réduire écritures et contention de sessions | À réaliser, P2 |
| SCALE-04 | Mesurer et optimiser les requêtes de liste | À mesurer, P2 |
| SEC-05 | Journal métier, événements IAM et CSP | À réaliser, P2 |
| DATA-01 | Conflit explicite sur formulaire périmé | À réaliser, P2 |
| OPS-01 | CI verte, scans Maven et images, restauration et charge | À réaliser, P2 |

[Preuves, limites et critères de remédiation](../technique/07-audit-securite-scalabilite.md).

## Remédiations livrées dans le jalon de durcissement

SEC-01 est corrigé pour les identifiants effectivement utilisés par les applications. SEC-02 est appliqué pour la protection des connexions, avec profil HTTPS préparé mais déploiement public non vérifié. SEC-03 est appliqué par instance, la limite distribuée reste ouverte. SCALE-01, SCALE-02, SCALE-03 et DATA-01 ont des correctifs vérifiés localement. La purge périodique, la charge durable et le dimensionnement restent ouverts.

SEC-04, SEC-05 et OPS-01 ne sont pas soldés par ce jalon. Voir les [preuves et limites](../technique/08-durcissement.md).

Le dépannage du frontend local est documenté dans le README. Le démarrage automatique après redémarrage du Mac reste hors périmètre.

## Accès local aux données

- DEV-DB-01 : accès pgAdmin aux bases web, audit et keycloak vérifié sur 127.0.0.1, ports 5544, 5545 et 5546. Aucune publication de port PostgreSQL dans le profil production.

Publication du jalon de durcissement terminée, CI verte sur 69de451.

- DEV-REPO-01 : consignes IA exclues des publications futures, documentation fonctionnelle et technique conservée.

## Jalon notifications Kafka

| ID | Livrable | État |
| --- | --- | --- |
| NOTIF-01 | Outbox dans la transaction d’affectation et publication avec bail | Vérifié localement |
| NOTIF-02 | Consommation idempotente et base propriétaire | Vérifié localement |
| NOTIF-03 | Notifications dans le BFF et l’interface, accès depuis l’en-tête et isolation par sujet | Vérifié localement |
| NOTIF-04 | Reprise après panne broker, service et base, rejeu sans doublon | Vérifié localement |
| NOTIF-05 | CI distante de la tranche Kafka | Vérifiée sur 7c15f78 |
| NOTIF-06 | Quarantaine, alertes, rétention et outil de rejeu supervisé | À réaliser |
| NOTIF-07 | TLS, ACL, réplication Kafka et mesures de charge | À réaliser avant production |

Les notifications de retard US-009 restent proposées. Le jalon livré porte uniquement sur les affectations aux missions.

- NOTIF-08 : compteur de non-lues et marquage individuel, implémentés avec isolation par sujet, persistance et protection CSRF. Vérifié localement par tests backend et navigateur ciblés.

## Cycle de vie des missions

- MISSION-01 : dates prévues, démarrage et clôture manuels réservés au propriétaire responsable, implémentés localement.
- MISSION-02 : transitions et planification protégées par version, contrats et tests ajoutés.
- MISSION-03 : validation humaine avec Temporal, annulation et réouverture proposées pour une tranche ultérieure.

## Préparation de la validation humaine

| ID | Livrable | État |
| --- | --- | --- |
| VALID-001 | Rôle AUDIT_VALIDATOR, compte local et absence de privilèges globaux | Vérifié localement, 25 tests Java ciblés et jetons réels |
| VALID-002 | Désignation sur une mission, séparation responsable et validateur, droits limités à la mission | Vérifié localement, 28 tests Java ciblés et 12 scénarios navigateur |
| VALID-003 | Soumission et décision persistées, clôture après approbation | Vérifié localement, 29 tests Java ciblés et 12 scénarios navigateur |
| VALID-004 | Temporal, livraison des décisions et reprise du worker | Vérifié localement, 32 tests Java ciblés, 12 scénarios navigateur et pannes réelles |
| VALID-005 | Échéances et relances de revue, notification de soumission suivie dans NOTIF-09 | Proposé, après priorité métier |

Voir le [guide du rôle validateur](../technique/11-role-validateur.md).

## Notifications de validation : 2026-10-19

- NOTIF-09 : notification interne au validateur à chaque soumission, outbox atomique avec la mission et le message Temporal. Vérifié localement par 41 tests Java ciblés, 12 scénarios navigateur et le test de pannes.
- Les relances, échéances, emails et notifications de décision restent hors de cette tranche.

- OPS-DNS-01 : reprise des routes après changement d’adresse Docker, cache DNS borné et scénario de panne Temporal comme vérification distante.
- OPS-DNS-02 : registre de révocation de la gateway sur le même cache DNS borné, nettoyage de test qui attend la santé d’audit-service. Vérifié localement par le scénario Temporal avec pannes, onze tests gateway dont `GatewayDnsTest` et quatre tests Python de l’attente de santé, CI distante à observer. Voir la [reprise DNS de la gateway](../technique/16-reprise-dns-gateway.md).

- DEMO-01 : nettoyage des anciennes données et six missions fictives réalistes, vérifié localement. Voir le [jeu de démonstration](../technique/17-donnees-demonstration.md).

- UX-REVIEW-01 : champ de commentaire de validation corrigé, pleine largeur et compatible mobile. Vérifié localement sans modifier les missions de démonstration.

- NOTIF-10 : titres de missions autorisés, ordre décroissant et lecture au clic avec compteur. Implémenté localement, colonne Lecture retirée.

- UX-NOTIF-01 : zone cliquable étendue à la ligne, action compacte dans une colonne distincte et focus clavier visible, vérifiés localement.

- NOTIF-11 : mise à jour SSE de la liste et du compteur, vérifiée localement avec une instance de notification-service. Diffusion partagée entre plusieurs instances à réaliser avant extension.

- NOTIF-12 : action Tout marquer comme lu, limitée au destinataire du JWT et protégée par CSRF. Vérifié localement par onze tests Java du service notifications et quatre scénarios navigateur. Voir le [guide des notifications](../technique/09-notifications-kafka.md).

## Refonte ergonomique du 21 octobre 2026

La navigation par onglets, les filtres de missions, le détail des contrôles et l’administration des comptes sont implémentés localement. Voir le [guide de navigation](../technique/24-navigation-administration.md). La prochaine tranche métier reste les constats.

- UX-DASH-01 : tableau de bord superadmin distinct du répertoire, compteurs, répartition par statut et cinq retards prioritaires. Vérifié localement par deux tests Java ciblés et quatre tests navigateur.

- DEMO-02 : extension du jeu fictif avec 40 missions, 120 risques et 160 contrôles pour exercer pagination, filtres, retards et parcours de revue. Voir le [jeu de démonstration](../technique/17-donnees-demonstration.md).
