# Durcissement du socle local

**Date :** 2026-10-16
**Statut :** implémenté et vérifié localement, aucune publication de production

## Résultat par risque

| Risque initial | Changement | Limite restante |
| --- | --- | --- |
| Comptes applicatifs superutilisateurs | audit_runtime et web_runtime non superutilisateurs, droits DML ciblés | Comptes historiques réservés à la maintenance |
| Connexions Keycloak sans protection | Protection contre les tentatives répétées sur master et auditsphere, rotation des refresh tokens | HTTPS local volontairement inchangé, profil HTTPS à déployer avec certificats |
| Absence de quotas et lecture intégrale du corps | Limiteurs gateway et BFF, lecture bornée à 32 Ko, timeout de lecture | Quotas par instance, limitation distribuée à prévoir |
| Annuaire sous transaction SQL | Validation réseau avant une transaction courte, propriété et version revérifiées sous verrou | État IAM et état métier ne constituent pas une transaction distribuée |
| Annuaire intégral et noms séquentiels | Pages de 50, validation ciblée, jeton réutilisé, noms en cache et parallélisme borné | Fraîcheur des noms limitée à 60 secondes |
| Session réécrite à chaque requête | Lecture sans verrou hors refresh, prolongation inactive espacée | Deux requêtes SQL sur le chemin rapide, purge périodique encore à planifier |
| Écrasement depuis un ancien formulaire | Version obligatoire, réponse 409 et rechargement explicite | L’utilisateur doit refaire sa sélection après conflit |

## Comptes PostgreSQL et conservation des données

Utiliser sh scripts/start-local.sh pour une nouvelle copie comme pour les volumes existants. Le script prépare les bases, appelle harden-databases.py, construit les services, lance la migration dédiée puis applique les droits runtime avant de démarrer les applications.

- audit_runtime : SELECT, INSERT, UPDATE et DELETE sur missions et mission_assignments.
- web_runtime : mêmes droits sur web_sessions uniquement.
- audit_migrator : propriétaire du schéma métier, non superutilisateur, utilisé dans un processus ponctuel avec MIGRATION_ONLY=true.
- web_owner : propriétaire non connecté du schéma de sessions.
- audit_admin et web_admin : administration locale, jamais transmis aux applications runtime.

Les comptes historiques audit et web restent les comptes administratifs d’initialisation des clusters PostgreSQL. Ils ne sont plus utilisés par Spring et Next.js. Le bootstrap PostgreSQL possède des objets système, sa suppression ou une réaffectation globale de ses objets n’est pas une migration sûre. Le correctif porte sur le retrait de leurs identifiants des applications, avec création de rôles runtime dédiés.

Chaque préparation crée des sauvegardes privées au format pg_dump dans .local/backups. Elles couvrent les bases, pas une sauvegarde complète des rôles du cluster. Les mots de passe existants ne sont pas écrasés. Les nouveaux secrets sont générés dans .env et la chaîne de connexion du BFF est mise à jour dans apps/web/.env.local. Redémarrer Next.js après la préparation.

Flyway est désactivé dans le service runtime Docker. Le processus de migration utilise audit_migrator, puis le runtime reçoit ses seuls droits DML. Les tables existantes sont conservées. La migration V2 ajoute version, assignment_revision et un index sur subject, mission_id.

## Connexion et profil HTTPS

La configuration locale applique sur master et auditsphere : protection active, cinq échecs avant temporisation, attente croissante jusqu’à quinze minutes, événements conservés sept jours. Les détails administratifs susceptibles de contenir des secrets ne sont pas enregistrés. Le realm auditsphere active la rotation des refresh tokens sans réutilisation autorisée. Les quatre refresh concurrents de la même session continuent de réussir grâce à la coordination en base.

Le fichier compose.production.yaml prépare Keycloak avec start, TLS natif et HTTP désactivé. Il impose PROD_OIDC_ORIGIN et KEYCLOAK_TLS_DIR. Fournir tls.crt et tls.key, avec une clé privée lisible par UID 1000 sans la rendre publique. PROD_APP_ORIGIN et PROD_OIDC_ORIGIN doivent être deux origines HTTPS valides, sans chemin final. Leur résolution doit fonctionner depuis l’hôte et les conteneurs.

Sur un environnement dédié, le profil utilise le projet Compose `auditsphere-production` et importe `deploy/keycloak/production-realm.json`, sans utilisateur ni client de démonstration. Ce nom isole les volumes de ceux du projet local `auditsphere`. L'import ne remplace pas un royaume déjà présent en base. `scripts/setup-web.py --production` refuse alors tout compte nommé `manager`, `auditor`, `outsider`, `validator` ou `superadmin`. Examiner et retirer ces comptes manuellement avant de reprendre. Le script configure les redirections HTTPS, interdit l’exception locale du BFF, impose SSL dans les realms et désactive le client de démonstration s'il existe. L’option `--production --configure-only` prépare seulement les valeurs privées, sans contacter Keycloak. La validation des certificats n’est jamais désactivée.

Avant de brancher le proxy HTTPS, contrôler hors ligne la configuration fusionnée avec `docker compose -f compose.yaml -f compose.production.yaml config --no-interpolate`. Cette option évite d'afficher les valeurs des variables privées. Vérifier le nom du projet, la source de l'import et l'absence de réutilisation des volumes locaux. Démarrer Keycloak sur son seul port de boucle locale, exécuter `setup-web.py --production`, puis confirmer l'absence des cinq comptes de démonstration. Pour une installation déjà exposée ou déjà peuplée, couper d'abord l'accès du proxy, sauvegarder le volume d'identité et examiner le royaume. Ne rétablir l'accès qu'après assainissement manuel des comptes, application du profil et vérification des connexions privilégiées.

Le même script applique une politique de mot de passe de 14 caractères avec quatre catégories et cinq mots de passe précédents exclus. Il rend l'OTP obligatoire pour chaque utilisateur du royaume en production, y compris `SUPER_ADMIN` et `AUDIT_VALIDATOR`. Il désactive le grant mot de passe de tous les clients, y compris `admin-cli`, qui existe automatiquement dans un nouveau royaume Keycloak. L'enrôlement se fait lors de la prochaine authentification. Les connexions fédérées exigent une revue séparée. Après le provisionnement, vérifier dans Keycloak que le flux navigateur actif est `auditsphere-browser-mfa`, que l'exécution OTP est `REQUIRED`, que chaque client a le grant mot de passe désactivé et que la politique de mot de passe est active. Tester ensuite un compte privilégié de test sans OTP et confirmer qu'il doit l'enrôler avant d'accéder à l'application.

Pour créer le premier superadministrateur applicatif, ouvrir la console Keycloak avec l'administrateur bootstrap sur le réseau d'administration. Créer un utilisateur nominatif avec une adresse vérifiée, attribuer le rôle de royaume `SUPER_ADMIN`, puis envoyer les actions `UPDATE_PASSWORD` et `CONFIGURE_TOTP`. Le destinataire choisit son mot de passe et configure son OTP. Ne placer aucun mot de passe de ce compte dans `.env`. Réduire ensuite l'accès à l'administrateur bootstrap selon la procédure d'exploitation de Keycloak. Cette procédure n'a pas été exécutée sur une installation de production.

Ce fichier constitue un profil de configuration, pas un déploiement public livré. Il reste nécessaire de provisionner les certificats et le reverse proxy HTTPS du frontend, de conserver la gateway privée, de dimensionner l’infrastructure et de tester le parcours HTTPS réel. La syntaxe Compose a été validée avec des origines fictives. Aucun certificat réel ni domaine public n’a été configuré ici. Le lanceur local ne charge jamais le profil production.

Les images externes de Compose, du stockage Garage, du Dockerfile et du scanner CI sont épinglées par digest SHA-256. Les actions GitHub sont épinglées par SHA de commit. Ces références ont été vérifiées auprès des registres et dépôts amont le 23/10/2026. Le test `scripts/test_image_pins.py` refuse le retour à une référence mutable. Une mise à jour requiert de vérifier le nouvel artefact et son digest avant de modifier la référence.

## Limites de requêtes

Le BFF applique des fenêtres d’une minute : 6000 requêtes avant authentification par adresse client, 120 par session réellement résolue, 60 requêtes anonymes, 30 connexions OAuth et 60 callbacks par adresse. Un cookie inventé ne crée pas de nouveau compteur de session. Les adresses déclarées ne sont utilisées que si TRUSTED_PROXY_IP_HEADER désigne un en-tête écrasé par le proxy et si l’accès direct au BFF est fermé. En HTTPS, une configuration absente ou une adresse invalide produit 503. Le mode HTTP local utilise une clé partagée car Next.js ne fournit pas la socket aux routes.

La gateway applique 1200 requêtes globales et 240 par identité sur une minute. Le plafond réseau vaut 600 par défaut, relevé à 1200 dans Compose par GATEWAY_RATE_IP_LIMIT pour le trafic du BFF. Les compteurs sont en mémoire, avec nettoyage et capacité bornée. Un dépassement renvoie 429 et Retry-After. Les compteurs redémarrent avec le processus. Derrière un proxy, plusieurs clients peuvent partager le quota réseau.

Ces limites protègent le déploiement local à une instance. Pour plusieurs réplicas ou un hébergement serverless, utiliser une limite partagée Redis ou un équipement d’entrée de confiance. Une fenêtre fixe autorise un pic à la frontière de deux fenêtres. Il ne faut pas annoncer un quota global de cluster avec cette implémentation.

Le BFF rejette les corps de plus de 32768 octets pendant leur lecture, y compris sans Content-Length. Les limites de corps sont appliquées directement dans les Route Handlers et leur utilitaire commun. La déconnexion est limitée à 4096 octets. La gateway borne aussi l’agrégation des corps POST et PUT à 32 Ko. Les lectures des corps JSON sont limitées à cinq secondes. Les preuves ont une limite de onze Mio de corps, deux dépôts simultanés par utilisateur et huit par instance. Leur lecture BFF est bornée à trente secondes, celle de la gateway à cinq secondes. Les chemins directs vers audit-service restent privés dans Compose.

## Annuaire et transactions

L’interface parcourt des pages de 50 membres du rôle AUDITOR, puis masque les comptes désactivés. Une page peut donc contenir moins de 50 choix sans être la dernière. Les sélections hors page restent conservées et peuvent être retirées.

La validation d’une affectation consulte uniquement les identifiants choisis et leurs rôles effectifs. Elle ne réutilise pas le cache des noms pour décider des droits. Les noms sont mis en cache pendant une minute, avec un plafond de 5000 entrées. Le jeton du compte technique est réutilisé jusqu’à quinze secondes de son expiration.

Les opérations ciblées utilisent huit workers au maximum, une file limitée à 256 tâches et un budget global de cinq secondes. Les timeouts réseau sont de une seconde en connexion et deux secondes en lecture. Une surcharge ou une panne renvoie 503, sans appliquer le changement métier. Les requêtes déjà en vol peuvent continuer jusqu’à leur timeout réseau après annulation.

La propriété est vérifiée avant d’appeler l’annuaire. Après validation, une transaction de cinq secondes maximum relit et verrouille la mission, revérifie la propriété et la version, puis applique la nouvelle équipe. Les noms sont résolus après fermeture de la transaction qui vérifie le droit de lecture.

## Sessions et concurrence

Une session dont le jeton reste valide est lue sans SELECT FOR UPDATE. La prolongation de son expiration inactive est conditionnelle et espacée d’environ une minute. Le JSONB des jetons n’est pas réécrit sur ce chemin. Le renouvellement conserve le verrou SQL avec revérification après acquisition, afin de ne pas utiliser deux fois le même refresh token. Les attentes de verrou et les requêtes SQL sont bornées.

Le PUT des affectations attend version et assigneeSubjects. La version est fournie dans la mission retournée par le serveur. Si elle est périmée, le serveur renvoie 409 et ne change pas l’équipe. Le bouton Recharger la fiche récupère la nouvelle version et ferme le formulaire périmé. Chaque remplacement accepté incrémente la version, même si les membres restent identiques.

## Vérifications

- 21 tests Java réussis, dont absence de transaction pendant les appels annuaire, cache des noms distinct de la validation, conflit de version, quotas gateway et corps trop volumineux.
- 8 scénarios Playwright sur le serveur compilé, dont sessions non réécrites, corps envoyé en flux, réponse 429, refresh concurrent et conflit 409.
- Smoke API avec vrais JWT réussi après migration des comptes runtime.
- Rôles runtime et migrateur vérifiés sans privilèges administrateur.
- Création de table et de rôle interdite au runtime, lecture de l’historique Flyway refusée.
- Préparation et migration répétées sur les volumes existants et sur des volumes neufs isolés. Les volumes de répétition ont été supprimés après le test, les volumes locaux conservés.
- Build Next.js et contrôle TypeScript réussis. Profil Compose HTTPS validé syntaxiquement.
- Scan Gitleaks et comparaison avec les secrets privés sans détection dans les fichiers destinés à Git.

Une montée en charge durable, une validation multi-instance et le parcours HTTPS réel restent à réaliser. Le chiffrement applicatif des jetons, la CSP et le journal métier de l’audit précédent restent hors de cette tranche.

## Acces local avec pgAdmin

Les trois instances PostgreSQL sont accessibles depuis le Mac sur 127.0.0.1 uniquement. Dans pgAdmin, créer trois connexions avec Register > Server, puis renseigner l’onglet Connection.

| Nom conseillé | Host | Port | Maintenance database | Username | Variable du mot de passe dans .env |
| --- | --- | --- | --- | --- | --- |
| AuditSphere sessions | 127.0.0.1 | 5544 | web | web_runtime | WEB_RUNTIME_PASSWORD |
| AuditSphere métier | 127.0.0.1 | 5545 | audit | audit_runtime | AUDIT_RUNTIME_PASSWORD |
| AuditSphere identité | 127.0.0.1 | 5546 | keycloak | keycloak | KEYCLOAK_DB_PASSWORD |

Les secrets restent dans le fichier privé .env à la racine du projet. Le mot de passe PostgreSQL de Keycloak est distinct de celui de sa console admin. Les comptes runtime ont des droits sur les tables applicatives, pas sur l’administration du cluster. Le compte keycloak possède des droits étendus, gérer les utilisateurs via la console Keycloak pour préserver la cohérence des données.

Pour consulter les tables : Databases > nom de la base > Schemas > public > Tables, puis clic droit sur une table > View/Edit Data > First 100 Rows. PostgreSQL ne sert pas de page HTTP, ces ports ne s’ouvrent pas dans Safari.

Les connexions TCP authentifiées aux trois bases ont été vérifiées. Les conteneurs métier et identité ont été recréés avec leurs volumes conservés. Le profil compose.production.yaml retire les trois publications de ports, ce retrait a été vérifié sur la configuration fusionnée.

Pour consulter flyway_schema_history, utiliser une connexion de maintenance avec audit_admin et AUDIT_ADMIN_PASSWORD. Le refus de cette table avec audit_runtime est attendu. Les identifiants de maintenance ne sont jamais utilisés par le service applicatif.
