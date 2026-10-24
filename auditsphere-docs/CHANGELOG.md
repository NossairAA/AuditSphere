# Journal des changements

## 2026-10-24 : CI, actions Node 24 et runner explicite

- `actions/checkout` v7.0.1, `actions/setup-java` v6.1.0 et `actions/setup-node` v7.1.0, toujours épinglées par SHA complet. Elles tournent sous Node 24 et remplacent les v4 sous Node 20.
- Runner `ubuntu-26.04` à la place de `ubuntu-latest`, dont la cible change à partir du 19 octobre 2026.
- `test_image_pins.py` refuse désormais un runner en `-latest` et une action sans commentaire de version. Vingt tests Python réussis localement, dont un ignoré par conception.
- Détails dans le [guide DevOps](docs/technique/02-devops.md). Résultat de la CI distante consigné dans la PR.

## 2026-10-24 : constats, correctif CI

- Échec CI de `findings.spec.ts` expliqué : actions réactivées avant le rechargement de la liste, soumission envoyée avec une version périmée puis refusée en 409.
- La liste est rechargée avant la réactivation des actions. Le test retarde les lectures de liste et reproduisait l’échec avant correction. Réussi localement, nouvelle CI à observer.

## 2026-10-24 : constats, lot 4

- Onglet Constats dans la fiche de mission : liste filtrée, saisie en panneau latéral, soumission, retrait, décisions avec commentaire, réouverture et historique.
- Refus de soumission de mission pour constats en attente affiché avec un lien Voir les constats.
- Nettoyage des données de test étendu aux constats. Base locale sauvegardée puis V17 appliquée, 49 missions conservées, droits du journal vérifiés.
- Parcours `findings.spec.ts` réussi, captures bureau et mobile contrôlées. `lifecycle`, `withdrawal` et `validator` réussis seuls, deux échecs observés lors d’un lancement groupé, cause non confirmée. CI distante non exécutée. Aucun push.

## 2026-10-24 : constats, lot 3

- Routes BFF des constats : liste, création, modification, suppression, transitions et historique, avec session, CSRF, limitation de débit, liste blanche des champs et messages propres au BFF.
- Relais strict du refus FINDINGS_PENDING pour la soumission et les dérogations de mission, avec le nombre de constats en attente.
- Contrat audit-api 0.14.0 complété avec les chemins et schémas des constats.
- Six tests unitaires Node ajoutés, dix réussis au total. TypeScript et build Next.js réussis. Parcours réel non vérifié, prévu au lot 4. Aucun push.

## 2026-10-24 : constats, lot 2

- Soumission de mission refusée en 409 tant qu’un constat est Brouillon ou Soumis. Corps `{"code":"FINDINGS_PENDING","pending":n}` pour l’interface, messages internes toujours masqués.
- Comptage rejoué sous verrou de mission dans la transaction finale. Dérogations APPROVE et CLOSE du superadministrateur soumises à la même règle.
- Deux tests Java ajoutés, dont un scénario concurrent validé par mutation. 69 tests audit-service réussis.
- BFF, interface et migration locale restent à faire. Détails dans le [guide des constats](docs/technique/26-constats.md). Aucun push.

## 2026-10-24 : constats, lot 1

- ADR-0021 accepté : constats hébergés par audit-service.
- Migration V17 : constats, contrôles liés, séquence de références par mission et journal en ajout seul. Droits audit_runtime ajoutés dans `scripts/harden-databases.py`.
- `FindingController` : liste, historique, création, modification et suppression de brouillon, transitions de soumission, retrait, validation, renvoi, rejet et réouverture. Séparation entre auteur de la soumission et décideur, dérogations tracées.
- Deux tests d’intégration Java et un test des droits SQL ajoutés. 67 tests audit-service réussis. Le test a révélé la réattribution d’une référence supprimée, corrigée par la table de séquence.
- Migration non appliquée à la base locale. Blocage de la soumission de mission, BFF et interface à réaliser. Détails dans le [guide des constats](docs/technique/26-constats.md). Aucun push.

## 2026-10-24 : cadrage des constats

- Tranche F-005 cadrée dans le [plan métier](docs/produit/04-plan-metier.md) : contenu, cycle Brouillon, Soumis, Validé ou Rejeté, permissions, interaction avec la mission, traçabilité et critères d’acceptation.
- Implémentation proposée dans le [guide des constats](docs/technique/26-constats.md) : migration V17, API audit-service, blocage de la soumission de mission, BFF, interface et lots de tests.
- [ADR-0021](docs/decisions/ADR-0021-constats-audit-service.md) proposé : constats dans audit-service, frontière réexaminée à F-006. Index des ADR complété de 0018 à 0021.
- Statut des preuves corrigé dans l’ordre de livraison du plan métier. Documentation seulement, aucun code ni test exécuté.

## 2026-10-24 : tout marquer comme lu

- Bouton Tout marquer comme lu sur la page Notifications. Le compteur et la liste sont rechargés après l’action.
- Endpoint PUT /notifications/read-all filtré par le sujet du JWT, dates de lecture existantes conservées. Route BFF protégée par session, origine et jeton CSRF.
- Onze tests Java du service notifications et quatre scénarios navigateur réussis. TypeScript vérifié. Build Next.js non relancé.
- Détails et limites dans le [guide des notifications](docs/technique/09-notifications-kafka.md). Aucun push ni déploiement.

## 2026-10-24 : CI, redémarrages d’audit-service

- Le filtre de révocation de la gateway utilise le cache DNS borné des routes. Il ne vise plus l’ancienne adresse Docker d’audit-service après un redémarrage, cause de l’échec Temporal du commit 0871cbe.
- Le nettoyage des données de test attend la santé d’audit-service avant de rendre la main, cause de l’échec du parcours `lifecycle` du commit 129393a.
- Tests ajoutés : `GatewayDnsTest` pour le réglage DNS du filtre et des routes, `test_service_health.py` pour l’attente de santé extraite dans `scripts/service_health.py`.
- Onze tests gateway et dix-neuf tests Python réussis. `GatewayDnsTest` échoue si le filtre reprend un client par défaut. Scénario Temporal avec pannes réussi localement avec changement d’adresse. Suites navigateur non rejouées localement, CI distante à observer. Détails dans la [reprise DNS de la gateway](docs/technique/16-reprise-dns-gateway.md).

## 2026-10-24 : vérifications finales des correctifs de sécurité

- Correctifs applicatifs C1 à C6 et C8 à C18 commités par constat. C7 reste un ADR proposé.
- Maven verify réussi avec 88 tests, puis test de concurrence SQL et trois tests gateway ciblés réussis. Quinze tests Python et quatre tests Node réussis.
- TypeScript et build web réussis. Vingt-sept tests Playwright réussis, dont déconnexion Keycloak, séparation du validateur et refus du dépôt anonyme avant lecture complète du corps.
- Le scénario de preuve à 10 Mio reste bloqué par une connexion expirée vers Garage sur Tailscale. Il n'est pas déclaré réussi, aucun endpoint ni secret n'a été modifié pour le contourner.
- Images locales gateway et notifications reconstruites et relancées. Smoke API rejoué avec succès. Résilience Kafka avec DLT et résilience Temporal vérifiées. Droits SQL C9 et migration V16 appliqués localement après sauvegarde, anciennes sessions en clair supprimées.
- Le test de répertoire attend la fin de la navigation des filtres avant de mémoriser son URL. Les données de test identifiées précisément sont nettoyées avec sauvegarde.
- Résultats et limites détaillés dans le [guide des correctifs](docs/technique/25-correctifs-securite.md). Aucun push ni déploiement de production.

## 2026-10-23 : C3, vérification navigateur

Un dépôt anonyme au corps volontairement inachevé reçoit 401 immédiatement, avant tamponnement. Six tests de primitives et quatre tests de lecture/admission réussis. Le parcours de preuve à 10 Mio franchit BFF et gateway mais échoue sur le stockage Garage distant injoignable. Il reste à rejouer quand ce service est disponible.

## 2026-10-23 : C6, vérification navigateur

Parcours superadmin complet réussi : refus API409 de sa propre approbation, option HTML désactivée et dérogations autorisées sur une mission distincte. L’assertion cible la propriété native de l’option.

## 2026-10-23 : C12, vérification complémentaire

Proxy CSP réservé aux pages, corps de pages bornés à 32768 octets et HSTS conservé sur toutes les routes au build HTTPS. Déconnexion autorisée vers la seule origine OIDC configurée. Cinq tests de primitives et parcours réel de déconnexion Keycloak réussis, build et TypeScript vérifiés.

## 2026-10-23 : C10, vérification complémentaire

Le filtre de limites précède la consultation du registre de révocation. Test composé avec 245 requêtes valides puis révoquées : au-delà de 240 requêtes, aucun appel supplémentaire au registre. Trois tests gateway réussis.

## 2026-10-23 : C7

ADR proposé pour Kafka SASL_SSL et ACL, Temporal avec mTLS, TLS Garage et chiffrement des preuves. Aucun déploiement de production ni modification de secrets. Validations locales détaillées dans le guide des correctifs.

## 2026-10-23 : C18

Images externes épinglées par digest SHA-256 et actions GitHub par SHA. Références vérifiées auprès des registres et dépôts, test de non-régression réussi. Images PostgreSQL Testcontainers exécutées avec succès.

## 2026-10-23 : C17

Politique de mot de passe de 14 caractères et OTP requis pour tous les utilisateurs de production, grants mot de passe désactivés pour tous les clients. Tests unitaires et configuration idempotente sur Keycloak jetable réussis. Parcours privilégié réel de production restant à valider.

## 2026-10-23 : C16

Trois flux SSE par utilisateur, plafond global configurable de 200 par instance. Tests de saturation et libération des places réussis.

## 2026-10-23 : C15

Jetons chiffrés par AES-256-GCM avec hachage de session authentifié. Anciennes sessions locales supprimées après sauvegarde. Tests de retour, altération et échange de ligne réussis. Clé dédiée obligatoire en production, dérivation locale transitoire documentée.

## 2026-10-23 : C14

Nom des auteurs de résultats et preuves résolu depuis le sujet authentifié dans l’annuaire. Test avec nom trompeur dans le jeton réussi.

## 2026-10-23 : C13

Affectations autorisées uniquement en brouillon et en cours. Test de refus sur mission clôturée réussi.

## 2026-10-23 : C12

CSP avec nonce par requête, interdiction des cadres et objets, rendu dynamique Next.js. HSTS réservé à HTTPS. Test des directives, unicité du nonce et HSTS réussi, build web validé.

## 2026-10-23 : C11

Quota cumulé de preuves par mission configurable, 100 Mio par défaut, contrôlé sous verrou de mission. Dépassement refusé et objet orphelin supprimé. Test de dépassement et nettoyage réussi.

## 2026-10-23 : C10

Passerelle et notifications consultent le registre central avant chaque requête authentifiée. Révocation refusée et indisponibilité traitée en refus fermé. Tests dédiés réussis, délai limité à la vérification pour conserver les flux SSE.

## 2026-10-23 : C9

UPDATE du journal limité à outcome, completed_at, target_subject et requested_changes. Privilèges appliqués et vérifiés localement après sauvegarde, acteur non modifiable et résultat modifiable.

## 2026-10-23 : C8

Calcul SHA-256 et contrôle de taille pendant le téléchargement, journalisation et interruption en cas de divergence. Test de contenu altéré réussi. Des octets peuvent avoir été transmis avant détection, une clé S3 de lecture distincte reste à étudier.

## 2026-10-23 : C5

Messages invalides transférés en DLT avec acquittement, reprise infinie conservée pour les pannes de base. Tests unitaires et scénario Kafka avec message invalide suivi du valide sur la même clé réussis, y compris les pannes Kafka, base et service.

## 2026-10-23 : C4

Limites de connexion par IP de proxy explicitement fiable, refus HTTPS si cette configuration manque. Session réelle résolue avant son compteur. Plafond IP de la passerelle configurable. Tests de séparation des IP réussis. Le mode HTTP local conserve une limite anonyme partagée car Next.js ne fournit pas la socket.

## 2026-10-23 : C3

Dépôts bornés à deux par utilisateur et huit par instance au BFF et à la passerelle, lecture BFF limitée à trente secondes et onze Mio. Tests de concurrence, annulation et lecture lente réussis.

## 2026-10-23 : C6

Séparation validateur et auditeur en API et en base, transactions concurrentes vérifiées. Dérogation APPROVE refusée au propriétaire, au validateur et aux affectés. Migration V16 appliquée localement après sauvegarde.

## 2026-10-23 : C2

Création et réinitialisation par action email Keycloak, aucun mot de passe retourné. Adresse email existante immuable pour empêcher le détournement de la réinitialisation. Tests API et client Keycloak réussis, SMTP de production non vérifié.

## 2026-10-23 : C1

Royaume sans utilisateurs de démonstration, projet Compose et volumes distincts, refus du provisionnement si un compte démo subsiste. Tests de configuration et Keycloak jetable vérifiés, aucun déploiement de production.

## 2026-10-22 : validateur choisi à la création

- Sélection facultative d’un validateur dans le formulaire de nouvelle mission.
- Éligibilité contrôlée avant la transaction et désignation enregistrée avec la mission.
- Contrat API et tests ciblés actualisés. Test Java ciblé, typecheck, build frontend et parcours navigateur réussis localement.

## 2026-10-22 : extension du jeu de démonstration

- Générateur local de 40 missions fictives, 120 risques et 160 contrôles, sans suppression de données.
- Scénarios de retard, échéance du jour, dates futures, revue, corrections, retrait, approbation et clôture.
- Résultats conformes, non conformes, non applicables et contrôles restant à réaliser.
- Reprise par manifeste privé et conservation des missions déjà générées. Aucun push.

## 2026-10-22 : tableau de bord superadmin

- Répertoire unique dans Missions d’audit, ancienne supervision remplacée par Tableau de bord.
- Compteurs et répartition par statut cliquables, cinq retards prioritaires avec retour à la vue globale.
- États de chargement, erreur et absence de retard, mise en page mobile.
- API summary enrichie sans migration ni suppression de données. Aucun push.
- Compilation réussie, deux tests Java ciblés et quatre tests navigateur réussis. Affichages ordinateur et mobile vérifiés.

## 2026-10-21 : navigation et comptes

- Recherche des missions côté serveur, filtres combinés, tri et retour à la liste conservé.
- Fiche par onglets, listes compactes de risques et contrôles, éditeurs latéraux et détail du contrôle.
- Supervision globale et gestion des utilisateurs par le superadmin, mots de passe temporaires et journal des opérations.
- Révocation des jetons d’audit et des sessions web après modification de compte.
- Client Keycloak administratif séparé de l’annuaire en lecture seule. Migrations V14 et V15 sans suppression de données.
- Validation locale détaillée dans le guide de navigation. Aucun push de cette tranche.


## Preuves privées des contrôles : 2026-10-21

- Résultats et correctif CI publiés dans 3e0dbcb, CI verte.
- Garage privé installé sur le homeserver et raccordé par Tailscale.
- Dépôt PDF et images, métadonnées et téléchargement soumis aux permissions de mission.
- 53 tests audit-service, 4 tests gateway et un parcours navigateur réussis, compilation validée.
- Nettoyage ciblé des objets synthétiques et documentation de sauvegarde. Tranche non publiée.

## Isolation des quotas dans les tests navigateur : 2026-10-21

- Diagnostic CI a1c7391 : le test de désignation reçoit 429 au lieu du conflit 409 attendu, puis une connexion échoue.
- Nouveau lanceur isolant les processus BFF et les quotas gateway entre fichiers de tests.
- Limites applicatives et test dédié aux réponses 429 conservés.
- Test de synchronisation des notifications corrigé pour attendre un badge absent lorsque le compteur revient à zéro.
- Disponibilité des services vérifiée explicitement avant chaque groupe, arrêt du frontend même en cas d’échec.
- Lanceur complet vérifié localement : 16 tests réussis, données de test nettoyées. Validation distante à faire après publication.

## Résultats des contrôles : 2026-10-20

- Saisie et correction par les auditeurs affectés, conclusion et justification obligatoires.
- Auteur et date fournis par le serveur, journal transactionnel des changements.
- Saisie uniquement En cours, version contrôlée et résultat à refaire après modification du contrôle.
- 52 tests du service d’audit et 16 tests navigateur réussis, compilation frontend validée et migration locale appliquée.
- Nettoyage des données de test effectué, sept missions conservées.
- CI du commit précédent a1c7391 en échec à l’étape navigateur, quotas partagés entre les tests identifiés. Nouvelle tranche non publiée.
- Pièces justificatives hors périmètre de cette tranche.

## Programme d’audit : 2026-10-20

- Publication des changements précédents dans 6ca0b6c, CI GitHub réussie.
- Risques et contrôles rattachés aux missions, création et modification depuis la fiche.
- Permissions par mission, verrouillage selon le statut, conflits de version et journal transactionnel.
- Échelles initiales de 1 à 5, plafonds de 100 risques et 100 contrôles par mission.
- 50 tests du service d’audit et un parcours navigateur réussis, compilation frontend validée.
- Nouvelle tranche vérifiée en local, validation CI distante à suivre après publication.

## Fiche de mission allégée : 2026-10-20

- Navigation directe entre les sections, action principale mise en avant et formulaires ouverts à la demande.
- Dates et validateur consultables avant modification, section distincte pour les décisions et les repères de suivi.
- Compilation et quatre parcours navigateur réussis, vérification visuelle sur ordinateur et mobile sans débordement horizontal.
- Documentation actualisée, changements disponibles en local et non poussés.

## Nettoyage Kafka : 2026-10-20

- Le dernier push `d74df3e` a passé son scénario Temporal mais échoué pendant le nettoyage CI.
- Gestion des partitions Kafka encore sans offset et réellement vides, tout retard positif reste bloquant.
- Quatre tests Python réussis, ajoutés à la CI. La validation distante du correctif reste à faire.

## Superadministrateur : 2026-10-20

- Rôle SUPER_ADMIN et compte local superadmin, sans privilège d’infrastructure.
- Accès global aux missions, dérogations motivées avec confirmation, journal et suppression logique.
- Approbation personnelle explicitement autorisée par dérogation, coordination Temporal conservée.
- 59 tests Java et 11 parcours navigateur réussis, compilation et vérification réelle des décisions Temporal.
- Documentation et contrat API actualisés, aucune donnée de test conservée dans la liste des missions.

## Fiche de mission : 2026-10-20

- Correctifs de retrait et suppression publiés dans `d74df3e`.
- Fiche restructurée autour du statut, des informations clés, de l’équipe et des actions contextuelles.
- Panneau de prochaine étape, adaptation mobile et séparation des actions secondaires.
- Compilation et trois parcours navigateur réussis, données de test nettoyées.

## Retrait et suppression : 2026-10-20

- Publication du socle notifications dans le commit `4cd8d43`, après 47 tests Java et 13 tests navigateur réussis.
- Suppression des brouillons et retrait des soumissions par le propriétaire, avec confirmation, motif et journal conservé.
- Décision Temporal WITHDRAWN, nouveau cycle à la soumission suivante et conflits de version contrôlés.
- Nouvelle tranche vérifiée par 53 tests Java, 14 tests navigateur, compilation frontend et reprise réelle des trois cycles Temporal avec pannes.
- Nettoyage des missions synthétiques et ajout d’un manifeste par identifiant pour le nettoyage automatique des tests navigateur et Temporal. Les missions de démonstration et la mission utilisateur sont conservées.

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
