# Navigation et administration des comptes

## Missions

Le répertoire recherche toutes les missions accessibles, titre et périmètre, avant pagination. Les filtres statut, responsable et retard se combinent. Le tri porte sur création décroissante, titre ou fin prévue croissante. La pagination reste de 20 missions dans le BFF. Les paramètres sont conservés dans l’URL et dans le lien de retour depuis une mission et un contrôle.

Une mission est en retard si sa fin prévue est antérieure à la date UTC courante et qu’elle n’est pas clôturée. Les missions supprimées sont exclues. Le superadmin dispose d’indicateurs globaux cliquables, les autres utilisateurs restent limités à leur périmètre.

La fiche propose Vue d’ensemble, Risques, Contrôles, Équipe et Suivi. Les dates détaillées, le cycle de vie et les dérogations restent dans Vue d’ensemble. Les anciens liens de fiche restent valides.

## Tableau de bord superadmin

Administration puis Tableau de bord ouvre la page /missions/admin, réservée au superadmin. Missions d’audit reste l’unique répertoire. Les quatre compteurs ouvrent ce répertoire avec le filtre approprié. La répartition par statut propose également des liens filtrés et des effectifs lisibles sans dépendre de la couleur.

À traiter en priorité affiche au maximum les cinq missions en retard dont la fin prévue est la plus ancienne. Les missions clôturées ou supprimées sont exclues. Ouvrir une priorité conserve un lien de retour au tableau de bord. Voir tous les retards ouvre le répertoire trié par fin prévue. Le tableau de bord se charge à l’ouverture et propose Réessayer en cas d’erreur, sans afficher de faux compteurs à zéro.

GET /admin/summary conserve les quatre champs existants et ajoute byStatus avec les cinq statuts, avec un effectif zéro pour les statuts sans mission. Les lectures des compteurs partagent une transaction cohérente. Les priorités utilisent GET /missions avec overdue=true, sort=end et size=5. Le BFF accepte cette taille de cinq pour le tableau de bord, le répertoire conserve sa pagination de vingt. Les deux réponses peuvent refléter un changement métier survenu entre leurs lectures.

## Programme d’audit

Les risques sont présentés dans une liste compacte, avec description dépliable, probabilité, impact et nombre de contrôles. Recherche, filtres de probabilité et impact et sélection Sans contrôle facilitent la préparation. Ajouter un contrôle depuis une ligne présélectionne ce risque.

Les contrôles disposent d’une liste filtrable par risque et résultat. Ouvrir le contrôle donne accès à sa procédure, ses risques, son résultat et ses preuves privées. Les listes restent bornées aux limites métier existantes de 100 risques et 100 contrôles. Aucun nouveau score de criticité n’est introduit.

Les formulaires de programme s’ouvrent dans un panneau latéral avec dialogue natif, focus et fermeture au clavier. Les modifications non enregistrées déclenchent une confirmation lors de la fermeture ou d’un changement de lien et une protection lors de la fermeture de l’onglet. Les erreurs conservent la saisie. Les résultats restent modifiables uniquement En cours et le programme reste verrouillé pendant la revue et après approbation.

## Utilisateurs

Administration puis Utilisateurs permet de rechercher les comptes, filtrer par rôle et activation, créer un utilisateur et modifier son profil. L’identifiant est immuable. Les comptes techniques sont exclus. Seuls Responsable, Auditeur, Validateur et Superadmin sont gérés, aucun rôle d’administration Keycloak n’est exposé.

La création et la réinitialisation génèrent un mot de passe temporaire côté serveur, affiché une fois et à changer lors de la prochaine connexion. Le mot de passe n’est ni journalisé ni envoyé dans une URL. Le superadmin doit le transmettre par un canal privé.

Toutes les mutations demandent un motif. L’interface demande confirmation et indique le nombre de missions actives liées au compte. Les affectations et historiques sont conservés après désactivation. La désactivation de son propre compte et le retrait de son propre rôle superadmin sont refusés côté serveur. Aucun effacement définitif n’est proposé.

Keycloak reste la source des identités. Le client auditsphere-user-admin est distinct de l’annuaire en lecture seule. Son secret privé USER_ADMIN_CLIENT_SECRET reste dans .env. Le client possède les permissions techniques de gestion des utilisateurs, les API applicatives imposent une liste fermée de rôles et le rôle SUPER_ADMIN.

## Sessions et traçabilité

La migration V14 ajoute user_admin_log et account_revocations. La migration V15 conserve les modifications de profil et de rôles demandées, sans secrets. Chaque mutation écrit une intention PENDING avant les appels Keycloak, puis SUCCESS ou UNCERTAIN. Une opération incertaine doit être vérifiée dans Keycloak et dans le journal avant répétition. Une intention restée PENDING après un arrêt bloque une autre mutation de la même cible et demande une réconciliation opérateur. Aucun verrou de transaction métier n’est conservé pendant les appels réseau.

Une modification ou une réinitialisation invalide les jetons d’audit antérieurs au point de révocation et ferme les sessions Keycloak. Le BFF vérifie ce point avant de réutiliser une session et supprime sa session devenue invalide. Un utilisateur dont les droits changent doit se reconnecter. Les appels déjà en cours peuvent terminer. Un JWT déjà émis peut rester valable jusqu’à son expiration sur le service de notifications appelé directement, qui ne partage pas cette table de révocation. La durée configurée du JWT a été vérifiée dans le realm local : 300 secondes, auxquelles peut s’ajouter la tolérance d’horloge du validateur. Les appels web passent par la vérification BFF.

Le journal présente les 50 dernières opérations du compte, leur auteur technique, le motif et le résultat. Le mot de passe et les jetons n’y figurent jamais. Les requêtes d’administration sont protégées côté Spring et BFF, avec contrôle CSRF sur les mutations et réponses sans cache.

## Limites et exploitation

Cible de cette tranche : moins de 100 missions. Le répertoire utilisateurs lit un annuaire borné à 1 000 entrées et refuse de présenter une liste tronquée. Les groupes et rôles composites Keycloak ne sont pas administrés par cette interface. Les rôles hérités incompatibles nécessitent une intervention Keycloak et produisent un résultat non confirmé.

Exécuter setup-web.py pour préparer le client dédié, puis les migrations avec le compte migrateur et harden-databases.py pour les droits runtime. Les bases et les comptes existants sont conservés. Les tests de comptes utilisent uniquement des identités ux-test enregistrées dans un manifeste privé, supprimées après vérification. Aucun déploiement distant ou push automatique.

## Vérifications locales du 21 octobre 2026

Les 55 tests du service d’audit, les 4 tests gateway et les 4 tests Python passent. La compilation frontend réussit. Les parcours navigateur exécutés par suites isolées comptent 17 tests réussis sur les 18 tests actuels, dont le changement de rôle avec invalidation de session et reconnexion avec les nouveaux droits.

Le parcours programme avec dépôt de preuve avait réussi lors d’une exécution précédente. Sa dernière exécution reste bloquée par l’indisponibilité réseau de Garage sur le homeserver. Il faut le relancer après rétablissement du stockage avant de déclarer la totalité des parcours valide. Les sept missions existantes ont été conservées. Aucun push ni validation CI distante pour cette refonte.

Le point de révocation est comparé à la date d’émission du JWT à la seconde près. Une reconnexion dans la même seconde que la révocation peut être refusée, une nouvelle tentative à la seconde suivante résout ce cas.

## Validation du tableau de bord, 22 octobre 2026

Compilation frontend réussie. Deux tests Java ciblés vérifient les compteurs, les exclusions, le tri des retards et la limite de cinq. Quatre tests navigateur passent, couvrant les liens et le retour au tableau de bord, les erreurs et leur nouvelle tentative, les états vides, le mobile et le répertoire filtré avec refus de l’API admin à un responsable. Les captures ordinateur et mobile ont été vérifiées. Les tests de présentation utilisent des réponses simulées, le test du répertoire utilise une mission synthétique nettoyée après exécution. Aucun push.
