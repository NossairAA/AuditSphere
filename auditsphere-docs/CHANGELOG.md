# Journal des changements

## 2026-10-28 : mémoire d'audit-service relevée

- Réinitialisation planifiée de 04:00 UTC en échec : audit-service arrêté par OOM à sa limite de 512 Mio pendant le peuplement, après 33 missions. Il restait à 505 Mio avant le Job.
- Limite à 768 Mio, demande à 512 Mio, tas à 50 % de la limite pour ce seul service. Les autres services restent inchangés, le nœud n'ayant que 1,7 Gio disponibles.
- Déployé et vérifié : tas maximal de 384 Mio dans le pod, réinitialisation relancée à la main et réussie avec 40 missions, pic à 543 Mio, aucun redémarrage. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-28 : responsable dans l'entrée de démonstration

- Quatrième bouton : le responsable `manager`, seul rôle qui crée une mission et la soumet au validateur. Lien au fournisseur `demo-entry` par `scripts/k3s-setup-keycloak.py`. ADR-0031 et ADR-0035 révisés.
- Le responsable n'a aucun droit d'administration. Ses écritures dans les données partagées disparaissent à la réinitialisation nocturne.
- Image du BFF de `41c1c94` épinglée et déployée. Vérifié par le nom public : un clic ouvre la session du responsable, avec la création de mission.

## 2026-10-28 : entrée de démonstration déployée

- Image du BFF de `87d5848` épinglée par empreinte et déployée avec `demo.enabled`.
- Vérifié par le nom public : un clic par rôle ouvre la session de l'auditeur, du validateur et de l'audité. Session renouvelée par une nouvelle assertion au-delà des 5 minutes du jeton. Smoke de l'API et du BFF réussis. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-28 : profil de démonstration, phase 10 lot C

- ADR-0031 accepté. ADR-0035 : entrée en un clic par octroi par assertion JWT de Keycloak. Le BFF signe une assertion courte pour `auditor`, `validator` ou `auditee`. Ni mot de passe, ni session Keycloak dans le navigateur, ni verrouillage de force brute. `superadmin` garde l'OTP obligatoire.
- Page de connexion avec trois boutons d'entrée et bandeau permanent de démonstration, par la valeur `demo.enabled` du chart web. Variable `AUDITSPHERE_PROFILE` : `demo` par défaut, `production` retire tout le dispositif.
- Chart `demo-reset` : CronJob nocturne qui rétablit les comptes, arrête les workflows Temporal, vide les bases, retire les preuves et recrée 40 missions. Aucun accès à l'API Kubernetes.
- Vérifié dans le cluster : octroi des trois comptes, refus des autres et du rejeu, deux exécutions du CronJob dont une après modification de `auditor`, smoke de l'API et du BFF réussis. Image du BFF à déployer après la fusion. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-28 : console de compte de Keycloak fermée

- ADR-0034 : clients `account` et `account-console` désactivés par `scripts/k3s-setup-keycloak.py`. La console de compte et son API répondent 404 sur les deux noms.
- Le paramètre `kc_action` ouvrait les formulaires du mot de passe et de l'OTP sans ressaisie du mot de passe. `max_auth_age` réglé à 0 sur ces actions : Keycloak redemande le mot de passe.
- Sonde de refus étendue à `/realms/auditsphere/account/`.
- Console d'administration réparée par le tailnet : URL frontale du royaume `master` réglée sur `KEYCLOAK_ADMIN_ORIGIN`. ADR-0033 révisé.
- OTP de `superadmin` et de `kc-admin` configurés par l'utilisateur.
- Vérifié dans le cluster : smoke de l'API et du BFF réussis, sonde à 1, aucune alerte active. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-28 : application publique par le tunnel, phase 10 lot B

- Bascule vers les noms publics : émetteur des jetons public, console et API d'administration de Keycloak sur le tailnet. ADR-0025 et ADR-0033 implémentés.
- Image web de `04baf82` déployée, le BFF lit `cf-connecting-ip,x-real-ip`. Sondes du tunnel en service.
- Les scripts de vérification envoient l'agent `AuditSphere-checks` : Cloudflare refuse `Python-urllib` avec l'erreur 1010.
- Vérifié : smoke de l'API et du BFF par les noms publics, console refusée en 403, points internes en 404, sondes à 1, compteurs du BFF par visiteur et adresse forgée sans effet. Limite : la console de compte Keycloak reste joignable. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-28 : tunnel déployé et adresse des visiteurs par Cloudflare

- `cloudflared` déployé dans le cluster : quatre connexions au tunnel, 19 Mio de mémoire. Limitation de débit des formulaires de connexion créée par l'utilisateur dans Cloudflare.
- Cloudflare refuse de modifier `X-Real-Ip`. Le BFF accepte désormais une liste d'en-têtes de confiance et lit `Cf-Connecting-Ip` en premier. ADR-0033 révisé.
- Valeur `trustedProxyIpHeaders` du chart web, laissée à `x-real-ip` jusqu'au déploiement de la nouvelle image. Test ajouté sur l'ordre des en-têtes. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-28 : tunnel et noms publics préparés, phase 10 lot B

- ADR-0033 proposé : les noms publics deviennent l'origine unique du navigateur et de l'émetteur des jetons. La console de Keycloak reste sur le tailnet par `KC_HOSTNAME_ADMIN`.
- `deploy/k3s/cloudflared.yaml` : connecteur du tunnel épinglé par empreinte, namespace `restricted`, métriques ouvertes à Prometheus seul. NetworkPolicy de `edge` et de Keycloak ouvertes au connecteur.
- Scripts d'administration de Keycloak par `KEYCLOAK_ADMIN_ORIGIN`. Limite de débit de Traefik par `Cf-Connecting-Ip`. Sondes du tunnel et des chemins refusés sur le nom public.
- Fait par l'utilisateur : zone DNS active chez Cloudflare, tunnel créé, secret du jeton dans le cluster. Vérifié à blanc dans le cluster, rien n'est déployé. Ordre de bascule dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-28 : durcissement de Keycloak, phase 10 lot A

- ADR-0032 : le client `auditsphere-demo`, qui accepte le mot de passe direct, est désactivé au repos. `scripts/k3s-check.sh` l'active le temps d'une exécution et le désactive à la sortie.
- `scripts/k3s-demo-client.py` et compte de service `k3s-demo-switch`, limité aux clients du royaume `auditsphere`.
- OTP exigé pour `superadmin`. Sonde blackbox et alerte `DemoClientEnabled` si le client reste actif plus de 30 minutes.
- Vérifié dans le cluster : smoke réussi, refus quand le client est déjà actif, fermeture après Ctrl+C, sonde dans les deux états, mot de passe direct de `superadmin` refusé. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : profils de démonstration et de production proposés

- ADR-0031 proposé : deux profils de déploiement distincts. `demo` offre une entrée en un clic par rôle, sans OTP pour ces seuls comptes, sur des données synthétiques réinitialisées chaque nuit. `production` reprend le royaume isolé de C1 et C17.
- Passage d'un profil à l'autre par redéploiement sur une base vide. Un seul profil à la fois sur le serveur actuel, qui sert `demo`. De vraies données exigent un hébergement adapté et un nouvel ADR.
- Répond à la question d'ADR-0025 sur les visiteurs anonymes. Phase 10 du [guide d'hébergement](docs/technique/28-hebergement-k3s.md) et backlog HOST-011 mis à jour. Rien n'est implémenté.

## 2026-10-27 : images corrigées déployées

- api-gateway, audit-service, notification-service, remediation-service et workflow-worker passent aux images de `3066fbc`, avec `guava` 33.7.2-android et `snappy-java` 1.1.10.9.
- Vérifié : analyse Trivy bloquante réussie en CI, smoke de l'API réussi, 22 cibles actives, aucune alerte active après le redémarrage. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : session d'observation et requête tracée, phase 6 lot 6C-b

- `scripts/k3s-observability.sh up|down|grafana` : Tempo, Loki, Alloy et Grafana installés à la demande dans `monitoring`, traces activées dans les services pendant la session. Valeurs dans `deploy/k3s/observability`.
- Alloy lit les journaux des pods par l'API Kubernetes, avec des droits limités à deux namespaces. Grafana relie les journaux aux traces.
- Services passés aux images de `119bb1e`.
- Analyse Trivy bloquante sur des vulnérabilités publiées entre-temps : le parent Maven force `guava` 33.7.2-android et `snappy-java` 1.1.10.9.
- Vérifié : requête tracée de la gateway à audit-service, trace suivie par Kafka jusqu'à notification-service, journaux reliés aux traces, session fermée en 51 secondes, smoke réussi. Phase 6 terminée. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : traces OpenTelemetry à la demande, phase 6 lot 6C-a

- ADR-0030 : traces et journaux à la demande. L'export est coupé par défaut et activé pendant une session d'observation, choix de l'utilisateur.
- `spring-boot-starter-opentelemetry` dans les quatre services Spring, export coupé par défaut. Observation de Kafka dans audit-service et notification-service.
- Chart `spring-service` : option `tracing.enabled`, fausse par défaut, qui active l'export vers Tempo avec un échantillonnage complet.
- Vérifié par tests : span exportée par la gateway vers un collecteur OTLP local. Suivi de Kafka : mémoire stabilisée vers 285 Mio. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : mémoire de Kafka corrigée

- Cause des arrêts de Kafka par manque de mémoire : la table du nettoyeur des topics compactés réservait 128 Mio de tas en permanence, la moitié du tas. Tas saturé, collecte complète toutes les 13 minutes.
- `KAFKA_LOG_CLEANER_DEDUPE_BUFFER_SIZE` ramené à 16 Mio. Tas et limite inchangés.
- Vérifié : test avec pannes réussi, environ 60 Mio vivants dans le tas au lieu de 170, mémoire de travail de 240 Mio au lieu de 400, aucune collecte complète ni alerte. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : métriques des services activées dans k3s, phase 6 lot 6B-b

- api-gateway, audit-service, notification-service et remediation-service passent aux images de `788e3c8` avec le port de gestion 9464. Sonde de la gateway sur ce port.
- Scripts de vérification : santé d'Actuator lue sur le port de gestion dans k3s, par `AUDITSPHERE_HEALTH_BASE`.
- audit-service : limite mémoire portée à 512 Mio après un arrêt par manque de mémoire avec la nouvelle image.
- Vérifié : 22 cibles actives, métriques absentes du port routé, port de gestion refusé hors de la supervision, alertes DLT et OOM reçues, tests des notifications et de Temporal réussis. Kafka arrêté une fois par manque de mémoire pendant les pannes : dimensionnement à revoir. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : métriques Prometheus des services, phase 6 lot 6B-a

- api-gateway, audit-service, notification-service et remediation-service exposent `/actuator/prometheus`. Spring Security l'admet sans jeton.
- Chart `spring-service` : option `managementPort`, nulle par défaut, qui place Actuator sur un port réservé à la supervision, avec sondes, annotations de collecte et règle d'entrée. Rendu inchangé tant qu'elle reste nulle.
- audit-service : jauges du nombre et de l'âge des événements non publiés de `assignment_outbox` et `review_workflow_outbox`.
- Bordure Traefik et Keycloak : métriques activées, collectées par Prometheus seul. Neuf règles ajoutées : mémoire des JVM, erreurs 5xx, retard Kafka, DLT, outbox bloquée, refus 429, pic de trafic, échecs de connexion, refus de la gateway. Les compteurs comptent aussi une série qui vient d'apparaître.
- Vérifié par tests : métriques servies sur le port de gestion seul, sans jeton, jauges des outbox. Vérifié dans le cluster : échecs de connexion et refus 429 provoqués, alertes publiées sur le sujet ntfy. Activation des métriques Spring au lot 6B-b. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : socle de supervision et alertes ntfy, phase 6 lot 6A

- ADR-0029 : Prometheus sans opérateur, Alertmanager vers un sujet ntfy.sh choisi par l'utilisateur, phase 6 découpée en trois lots.
- `deploy/k3s/monitoring/monitoring.yaml` : Prometheus, Alertmanager, node-exporter, kube-state-metrics et sondes blackbox dans le namespace `monitoring`, avec refus par défaut des entrées. Métriques de cert-manager activées.
- 16 règles d'alerte : disque, mémoire, échange, charge, OOM, redémarrages, charges incomplètes, Job en échec, sauvegarde trop ancienne, certificats, sondes, cibles et envoi des alertes.
- `scripts/k3s-monitoring.sh` : sujet ntfy et secrets créés sans affichage, application du manifeste, alerte de test.
- Vérifié : toutes les cibles actives, alerte réelle, résolution et alerte de test publiées sur le sujet ntfy, aucun échec d'envoi. Alerte de test reçue sur le téléphone, confirmé par l'utilisateur. Supervision à 422 Mio. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : rotation réelle du certificat client Temporal, phase 8 lot E2b

- audit-service et workflow-worker passent à l'image `0e56ca9`, qui contient `ReloadingKeyManager`.
- Essai avec des certificats clients d'une heure renouvelés toutes les 20 minutes : le worker bascule seul sur le certificat renouvelé dix secondes après l'expiration de son certificat de démarrage, audit-service bascule à son premier appel après un renouvellement. Aucun pod redémarré pour la rotation. Test Temporal réussi. Durées de 90 jours rétablies.
- Critères de sortie d'ADR-0020 : quatre remplis sur cinq. Reste la supervision, phase 6.
- Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-27 : rechargement du certificat client Temporal, phase 8 lot E2a

- `ReloadingKeyManager` : le client Java de Temporal relit son certificat et sa clé à chaque négociation TLS et bascule quand leur contenu change. Plus de redémarrage d'audit-service ni de workflow-worker après un renouvellement par cert-manager.
- Fichiers renouvelés invalides : la paire courante reste en service. Fichiers invalides au démarrage : refus de démarrer, comme avant.
- Revue des critères de sortie d'ADR-0020 : trois remplis, rotation partielle jusqu'au lot E2b, supervision non remplie. Autoriseur Temporal absent, limite acceptée. Disque de l'hôte non chiffré, constaté, à traiter avant toute donnée réelle. Alertes de la phase 6 envoyées par ntfy.sh.
- Vérifié par tests unitaires, dont deux négociations TLS réelles avec rotation entre les deux. Déploiement et rotation dans le cluster à faire au lot E2b. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : Garage en HTTPS et rotation des certificats, phase 8 lot E1

- Garage servi en HTTPS par un proxy Caddy sur l'hôte, avec le certificat du nom du tailnet obtenu auprès de Tailscale, selon ADR-0028. Garage n'est plus publié directement. audit-service et la sauvegarde utilisent le point d'accès HTTPS.
- Kafka relit son magasin de clés renouvelé par un conteneur `reload` et une reconfiguration dynamique, sans redémarrage.
- Sonde du PostgreSQL de l'exercice de restauration par TCP, après un échec lié au serveur temporaire de l'initialisation.
- Lot E découpé : E2 pour le rechargement du certificat client Temporal en Java. Durcissement de Keycloak reporté à la phase 10.
- Vérifié : HTTPS depuis le poste et les pods, clair refusé, preuves relues, sauvegarde et restauration réussies, certificat Temporal expiré refusé, rotation des certificats serveur sans redémarrage. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : Kafka en SASL_SSL avec ACL dans k3s, phase 8 lot D

- Broker : seule l'écoute SASL_SSL, SCRAM-SHA-512 et certificat de l'autorité interne, est joignable par le réseau. Contrôleur et écoute des outils limités à la boucle locale du pod. Autorisation active, accès anonyme réseau refusé.
- `scripts/k3s-kafka-security.sh` remplace le Job `kafka-topics` : topics, comptes SCRAM, ACL et secrets clients, sans afficher de mot de passe. audit-service écrit ses quatre topics, notification-service lit ses deux topics et son groupe et écrit leurs DLT.
- audit-service et notification-service configurés par `SPRING_KAFKA_PROPERTIES_*`. Les scripts de vérification passent par l'écoute locale dans k3s.
- Incident : un message d'erreur de `kafka-configs` a affiché un mot de passe généré, avant création du compte. Secret régénéré, script corrigé.
- Vérifié : refus du clair, d'un mauvais mot de passe, d'un nom faux et des topics non autorisés. Pannes Kafka, DLT et smoke réussis. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : Temporal en mTLS dans k3s, phase 8 lot C2

- `deploy/k3s/data/temporal-tls.yaml` : certificats du serveur Temporal, de l'administration et de chaque appelant Java. Serveur avec authentification cliente obligatoire, nom du service vérifié pour le trafic interne, certificats relus toutes les 10 minutes.
- Chart `spring-service` : option `temporalTls.secret` et `fsGroup`. audit-service et workflow-worker passent à l'image `9af0ebb`, qui contient `TemporalConnection`. Job `temporal-namespace` et pod de CLI en mTLS. `k3s-check.sh` attend que Temporal réponde.
- Vérifié : certificat admis, refus du clair, de l'absence de certificat, d'un nom faux et d'un certificat à usage serveur. Pannes Temporal et smokes réussis, sept workflows de revue intacts. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : client Temporal capable de mTLS, phase 8 lot C2a

- `TemporalConnection` : mTLS du publieur d'audit-service et de workflow-worker quand les trois fichiers TLS sont configurés. Configuration partielle ou matériel invalide : arrêt au démarrage, jamais de repli en clair. Sans configuration, comportement inchangé.
- `TemporalConnectionTest` : cinq cas, certificat généré pendant le test. `./mvnw -B verify` réussi en local.
- Non vérifié : connexion mTLS réelle, prévue au lot C2b après publication des images. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : PostgreSQL en TLS vérifié dans k3s, phase 8 lot C1

- Lot C découpé : C1 pour PostgreSQL, en configuration seule, C2 pour Temporal en mTLS, qui demande une modification du client Java.
- PostgreSQL : certificat cert-manager de 90 jours, TLS 1.2 au minimum, `pg_hba.conf` versionné qui refuse toute connexion réseau en clair. Conteneur `reload` qui recharge la configuration quand le certificat ou la règle change.
- `scripts/k3s-ca-bundle.sh` publie la racine hors ligne dans la ConfigMap `internal-ca`. Tous les clients passent en `verify-full` : services Spring et migrations par l'option `internalCa` du chart, Keycloak, BFF, Temporal, Jobs d'amorçage et sauvegarde.
- Jobs `web-schema` et `grants` : attente de PostgreSQL avant connexion, après un échec de la bascule dû au délai d'admission réseau des nouveaux pods.
- Vérifié : toutes les connexions en TLS 1.3, clair refusé, nom et autorité vérifiés, smokes, pannes Kafka et Temporal, sauvegarde et restauration réussis. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : sauvegardes du cluster, phase 7

- Phase 7 avancée avant le lot C de la phase 8, choix de l'utilisateur. ADR-0027 accepté : le poste tire les sauvegardes et les chiffre avec restic, Garage est copié à chaud par S3 avec une clé en lecture seule.
- `deploy/k3s/backup/backup.yaml` : namespace `backup` restreint, CronJob quotidien de `pg_dump` par un rôle `backup` en lecture seule, puis copie rclone du bucket des preuves. Rôle ajouté à `postgres-bootstrap`, règle d'entrée de PostgreSQL étendue.
- `scripts/k3s-backup-setup.sh`, `scripts/k3s-backup.sh`, `scripts/k3s-restore-test.sh` et `scripts/seed-backup-evidence.py` : identités, tirage restic, exercice de restauration isolé avec contrôle d'empreinte des preuves, preuves synthétiques.
- Vérifié : rôle sans droit d'écriture, clé Garage en lecture seule, jeu complet tiré dans restic, restauration isolée de sept bases réussie, deux preuves sur deux avec le même SHA-256. Non vérifié : détection d'une preuve altérée. Agent launchd quotidien pas encore installé. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : autorité interne dans k3s, phase 8 lot B

- ADR-0026 accepté : racine hors ligne, choix de l'utilisateur. Racine ECDSA P-384 chiffrée sur le poste, intermédiaire ECDSA P-256 chargée dans cert-manager.
- `deploy/k3s/cert-manager.yaml` : cert-manager v1.21.3, images épinglées, namespace `restricted` avec refus par défaut des entrées. `scripts/k3s-internal-ca.sh`, lancé par l'utilisateur, et ClusterIssuer `auditsphere-internal-ca`.
- Vérifié : certificat de test émis, chaîne vérifiée jusqu'à la racine hors ligne, secret supprimé avec le certificat. cert-manager occupe 51 Mio. Aucun service ne l'utilise encore. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : NetworkPolicy dans k3s, phase 8 lot A

- Refus par défaut des entrées dans `auditsphere` et `data`. Règles par composant dans les charts `spring-service` et `keycloak`, règles de PostgreSQL, Kafka et Temporal dans `deploy/k3s/data/network-policies.yaml`.
- Vérifié : pods prêts, smoke du BFF et de l'API, pannes Kafka réussies sous les règles. Pannes Temporal réussies au second essai, après un 503 isolé sur une soumission, cause non établie. Connexions refusées depuis des pods non autorisés vers audit-service, Keycloak, PostgreSQL, Kafka et Temporal.
- Limite : sorties non filtrées. Découpage de la phase 8 en cinq lots et décision de sauvegarde avant le lot C dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : smoke et pannes dans k3s, phase 4 lot E

- `scripts/target.py` : les scripts de smoke, de pannes et de nettoyage visent Compose par défaut ou k3s avec `AUDITSPHERE_TARGET=k3s`. `scripts/k3s-check.sh` ouvre la gateway par port-forward et un pod éphémère de CLI Temporal.
- `scripts/smoke-bff.py` : connexion de navigateur par l'origine HTTPS du tailnet, session, missions et déconnexion.
- Vérifié dans le cluster : smoke du BFF et de l'API, pannes Kafka et Temporal réussies, six missions de test nettoyées, données de démonstration intactes. Mémoire après les scénarios : 1,58 Gio disponibles, aucun OOM. Phase 4 terminée.
- Non vérifié : arrêt isolé de la base des notifications, sans équivalent avec un PostgreSQL partagé, et chemin Compose des scripts modifiés. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : publication GHCR rétablie

- Cause : l'ancien dépôt, renommé `AuditSphere-old`, restait source des paquets. Le nouveau dépôt n'avait que le rôle Read.
- Rôle Write donné au nouveau dépôt et ancienne source retirée sur les cinq paquets, par l'utilisateur. Run de `main` réussi à la sixième tentative, images de `ddb8cc1` publiées et lisibles sans authentification. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : Keycloak configuré et données de démonstration, phase 4 lot D

- `scripts/k3s-setup-keycloak.py` : client d'automatisation à la place de l'administrateur d'amorçage, administrateur `kc-admin` avec OTP, clients et six comptes synthétiques. Aucun secret affiché.
- `seed-demo-missions.py` paramétrable par variables d'environnement, valeurs locales par défaut. 40 missions synthétiques créées dans le cluster.
- Vérifié : connexion complète par le BFF en HTTPS, jetons attendus, droits des rôles par la gateway, notifications Kafka et revues Temporal.
- Arrêt brutal de l'hôte, batterie à 0 % sans charge. Retour automatique complet sans perte de données.
- CI de `main` en échec sur la publication GHCR, `permission_denied: write_package` : les paquets appartiennent à l'ancien dépôt. Accès à accorder au nouveau dépôt dans les réglages des paquets. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-26 : condition d'ouverture publique et dépôt distant

- Condition d'ouverture publique écrite dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md) et la roadmap : lots D et E de la phase 4, puis phases 8, 6, 7 et 10. Préparation DNS et tunnel possible dès maintenant.
- Le dépôt distant a été recréé le 2026-10-25, désormais public. Historique et branches conservés, numérotation des pull requests repartie de 1. Les mentions des PR #1 à #15 dans ce journal désignent l'ancien dépôt.

## 2026-10-26 : frontend et Traefik dans k3s, phase 4 lot C

- Chart `infra/helm/web` : BFF Next.js, Traefik v3.7.16 en bordure et entrée Tailscale `app`. Schéma des sessions et droits appliqués par un Job Helm.
- Renvoi DNS de `ts.net` vers MagicDNS pour que le BFF joigne Keycloak en HTTPS depuis le pod.
- NetworkPolicy : BFF joignable par Traefik seulement, Traefik par le proxy Tailscale seulement.
- Vérifié : page servie en HTTPS avec les en-têtes de sécurité, redirection OIDC avec PKCE, en-tête d'adresse transmis au BFF, accès direct refusé depuis un autre namespace. Connexion complète au lot D. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-25 : Keycloak dans k3s, phase 4 lot B

- Chart `infra/helm/keycloak` : Keycloak 26.8.0 épinglé en mode production, base PostgreSQL de la phase 3, royaume de production importé une seule fois, entrée Tailscale `auth`.
- Vérifié : découverte OIDC en HTTPS avec l'émetteur attendu, HTTPS reconnu derrière l'entrée, clés publiques lisibles en interne par les services.
- Les pods ne résolvent pas les noms du tailnet : les services gardent l'adresse interne. Jeton d'un vrai client à vérifier au lot D. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-25 : services Spring dans k3s, phase 4 lot A

- Chart Helm générique `infra/helm/spring-service` et valeurs pour cinq releases. Images GHCR épinglées, conteneurs non root en lecture seule, namespace `auditsphere` au niveau `restricted`.
- Migrations Flyway et droits SQL minimaux dans un Job Helm exécuté avant chaque déploiement, avec le rôle propriétaire de chaque base.
- Bucket et clé Garage dédiés au cluster. Valeurs du tailnet dans `.local/k3s.env`, hors dépôt.
- Vérifié : 18 migrations audit, huit contrôles de droits, worker connecté à Temporal, gateway à 401 sans jeton, second déploiement idempotent. Nœud à 4,0 Gio. Keycloak et le frontend restent à déployer. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-25 : couche de données dans k3s

- PostgreSQL 17.9, Kafka 4.0.2 et Temporal 1.32.1 déployés dans le namespace `data`, manifestes dans `deploy/k3s/data`, images épinglées.
- Sept bases et leurs rôles créés par un Job idempotent. Isolation vérifiée par douze contrôles. Mots de passe générés et stockés en secrets, jamais affichés.
- Temporal sans mode start-dev, persistant dans PostgreSQL, namespace `default` à sept jours. Kafka avec huit topics et conservation plafonnée.
- Redémarrage complet de l'hôte : données PostgreSQL, Kafka et Temporal retrouvées.
- Garage était injoignable depuis le redémarrage de la phase 2 : Docker démarrait avant l'adresse Tailscale. Docker attend désormais cette adresse. Garage vérifié joignable depuis le poste et depuis les pods après un nouveau redémarrage.
- `test_image_pins.py` couvre `deploy/k3s`, vérifié par deux mutations. HOST-003 terminé. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-25 : exposition publique décidée

- ADR-0025 accepté : AuditSphere sera ouvert à tout visiteur sous un domaine personnel, par Cloudflare Tunnel. Aucun port entrant, adresse du domicile masquée, zone DNS déléguée à Cloudflare, enregistrement conservé chez Namecheap.
- Seuls le frontend et Keycloak auront un nom public. Console Keycloak, gateway et services internes restent sur le tailnet. Données de démonstration uniquement.
- Phase 10 ajoutée au [guide d'hébergement](docs/technique/28-hebergement-k3s.md), HOST-010 au backlog. Ouverture conditionnée aux phases 4, 6, 7 et 8. Rien n'est configuré.

## 2026-10-25 : opérateur Tailscale

- Opérateur Tailscale `1.104.2` installé par une ressource `HelmChart` de k3s, dans [deploy/k3s/tailscale-operator.yaml](../deploy/k3s/tailscale-operator.yaml). Chart et images épinglés, opérateur limité à 128 Mio.
- Console Tailscale préparée par l'utilisateur : HTTPS, étiquettes `tag:k8s-operator` et `tag:k8s`, client OAuth à droits restreints. Secret saisi en masqué, hors dépôt.
- Vérifié : entrée de test servie en HTTPS par le tailnet avec un certificat valide, ports 80 et 443 fermés depuis le réseau local, appareil retiré du tailnet à la suppression. HOST-002 terminé. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-25 : hébergement privé, suite de la phase 0 et installation de k3s

- CI distante réussie sur `main` après la fusion de TECH-009 (`2743acb`) : job `verify` et cinq jobs `images`, analyse Trivy bloquante comprise, aucun job relancé.
- Relevé du serveur en lecture seule : mémoire vidéo toujours à 1 Gio, Wi-Fi seul, fichier d'échange de 4 Gio actif. Aucun composant Kubernetes installé.
- Le BIOS ne permet pas de réduire la mémoire vidéo. Réservation de 1 Gio conservée, budget mémoire inchangé. Deux modules de 4 Gio relevés.
- Machine renommée `auditsphere` par l'utilisateur côté système, dans `/etc/hosts` et côté Tailscale. Nom DNS vérifié sans suffixe après correction d'un conflit avec le poste d'administration.
- Phase 2 exécutée : k3s `v1.36.5+k3s1` à un nœud, sans Traefik ni servicelb, secrets chiffrés, réservations du kubelet. Procédure et résultats dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).
- Règles du pare-feu 80 et 443 ouvertes à toute origine, héritées d'un ancien Traefik, supprimées.
- Vérifié : nœud `Ready`, `kubectl` par le tailnet, ports 6443 et 10250 fermés depuis le réseau local, DNS et sortie Internet des pods, nœud rétabli seul après redémarrage complet. 5,2 Gio allouables.
- Non fait : opérateur Tailscale, qui demande un client OAuth. Les pods ne joignent pas encore Garage, point reporté à la phase 3.

## 2026-10-25 : publication GHCR vérifiée

- Premier run sur `main` après la fusion de la phase 1 : quatre images sur cinq refusées par GHCR (`denied`) au push du manifeste. Seule audit-service était publiée. Les paquets n'existaient pas encore.
- Jobs échoués relancés sans changement de code : run réussi. Les cinq images du commit `cb91e28` sont publiées et lisibles sans authentification.
- Cause probable : création simultanée de paquets neufs par la matrice. Non confirmée, faute d'accès aux réglages des paquets. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-25 : migration vers Spring Boot 4

- ADR-0024 accepté : Spring Boot 4.0.9, Spring Framework 7.0.10 et Spring Cloud 2025.1.4. Spring Boot 4.1 attendra la version stable de Spring Cloud 2026.0.0.
- Starters modulaires : `spring-boot-starter-webmvc`, `spring-boot-starter-kafka`, `spring-boot-starter-flyway` et `spring-boot-starter-security-oauth2-resource-server`. Tests MockMvc par `spring-boot-starter-webmvc-test`, Testcontainers 2.0.
- Code passé à Jackson 3, paquet `tools.jackson`, sans changement du JSON produit.
- `lz4-java` forcé en 1.11.4 dans le parent Maven. Image finale sur `eclipse-temurin:21-jre-noble`, sans le binaire `pebble` vulnérable.
- Analyse Trivy bloquante dans la CI sur les vulnérabilités hautes et critiques corrigeables, après production du rapport et du SBOM.
- Vérifié localement : `./mvnw -B verify` avec 108 tests, pile relancée, smoke, pannes Kafka et Temporal, aucun arrêt OOM, 30 parcours Playwright, Trivy sans vulnérabilité corrigeable sur les quatre images Java. CI distante non exécutée. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-24 : hébergement privé, phases 0 et 1

- Phase 0 démarrée : un port d'administration local joignable par le tailnet écoute désormais sur 127.0.0.1, vérifié depuis le tailnet. Mémoire vidéo, nom neutre et liaison réseau restent à faire.
- `apps/web/Dockerfile` : image standalone sur Node 24.22.1 Alpine épinglé par digest, utilisateur non root, npm retiré de l'image finale, aucun secret embarqué.
- JVM réglées par `JAVA_TOOL_OPTIONS` : SerialGC, tas à 60 % de la limite, piles de 512 Kio. Pools HikariCP à 5 connexions et 50 threads Tomcat. Limites mémoire Compose sur les processus Java et Keycloak, tas de Keycloak plafonné.
- Mémoire mesurée sous limites : services Java de 2,1 Gio à 1,33 Gio, aucun arrêt OOM. audit-service relevé à 448 Mio après un premier essai trop serré à 320 Mio.
- CI : job `images` avec Trivy 0.75.0 épinglé, SBOM CycloneDX et publication GHCR depuis `main` uniquement.
- Trivy révèle des vulnérabilités critiques dans Spring 6.2, Tomcat et Netty. Spring Boot 3.5 n'a plus de correctifs libres. Analyse en mode rapport, migration Spring Boot 4 ajoutée au backlog (TECH-009).
- Vérifié localement : smoke, pannes Kafka et Temporal, typecheck, build Next.js, conteneur frontend et tests Python. Playwright non relancé en local, CI distante et publication GHCR en attente. Détails dans le [guide d'hébergement](docs/technique/28-hebergement-k3s.md).

## 2026-10-24 : hébergement privé avec k3s

- Relevé du serveur privé en lecture seule : 8 threads, 6,7 Gio de mémoire visible, 168 Go libres, aucune alimentation secourue, Kubernetes absent.
- ADR-0023 accepté : cluster k3s à un nœud sur le serveur privé, outils professionnels répartis entre permanents et à la demande. Options Compose, Hetzner, AKS, EKS et Oracle évaluées avec tarifs relevés le jour même.
- [Guide d'hébergement](docs/technique/28-hebergement-k3s.md) proposé : budget mémoire, dix phases et critères de sortie. Backlog HOST-000 à HOST-009 et roadmap mis à jour.
- Documentation publique neutralisée : plus de lieu d'hébergement, de modèle de machine, de compte ni d'adresse privée. Libellés d'ADR-0015 et des guides 23 et 24 ajustés sans changer leurs décisions.
- Aucune installation, aucun déploiement et aucun test sur le serveur.

## 2026-10-24 : actions correctives, lot 2

- Lot 1 publié : CI distante réussie sur `226c006`, PR #6 fusionnée dans `main` par `05f6191`.
- Migration V18 d’audit-service : `missions.issue_cycle` et `findings_issue_outbox`. Émission quand la mission entre dans APPROVED ou CLOSED, retrait sur REOPEN ou DELETE d’une mission émise. Pas d’émission rétroactive des missions approuvées avant V18.
- `FindingsIssuePublisher` publie `audit.mission.findings-issued.v1` et `audit.mission.findings-withdrawn.v1`, clé `missionId`, identifiants seulement. Topics et DLT créés par `kafka-init`, schémas dans `contracts`.
- Lecture interne `GET /internal/missions/{id}/issues/{cycle}`, réservée au rôle REMEDIATION_SYNC, non routée par la gateway, protégée par un verrou partagé sur la mission.
- Rôle REMEDIATION_SYNC et client de service `remediation-service` créés par `setup-web.py`, sans rôle humain.
- Vérifié localement : six tests Java dont un test de mutation, `clean verify` complet, pile relancée, droits réels, smoke et parcours réel jusqu’au message Kafka. CI distante non exécutée. Détails dans le [guide des actions correctives](docs/technique/27-actions-correctives.md).

## 2026-10-24 : actions correctives, lot 1

- Nouveau service `remediation-service` : module Maven, port 8083, base `remediation` publiée sur 127.0.0.1:5548 en développement uniquement, route gateway `/remediation/**`.
- Migration V1 : suivis, actions, notes d’avancement, justificatifs, journal, événements traités et outbox. Contraintes CHECK avec `IS NOT NULL` explicite pour les valeurs obligatoires.
- Droits `remediation_runtime` : ajout seul sur le journal et les notes d’avancement. Scripts de préparation, de migration et de démarrage étendus.
- Rôle AUDITEE dans tous les environnements, compte `auditee` en local uniquement et refusé en production. Rôle attribuable depuis l’administration des comptes.
- Lecture `GET /remediation/me/actions` réservée à l’audité, avec retard calculé à la lecture.
- Clients Keycloak `remediation-service` et `remediation-directory` reportés aux lots 2 et 4, qui les utilisent.
- Vérifié localement : `clean verify` complet, tests Python, pile relancée, smoke par la gateway et droits réels. CI distante non exécutée. Détails dans le [guide des actions correctives](docs/technique/27-actions-correctives.md).

## 2026-10-24 : constats publiés, cadrage des actions correctives

- F-005 clôturé : CI distante réussie sur `92dd3ef` (exécution `40113987183`), avec 69 tests audit-service et `findings.spec.ts`. PR #3 fusionnée dans `main` par `65e4331`, CI de `main` réussie. Le [guide des constats](docs/technique/26-constats.md) passe à vérifié et publié.
- Tranche F-006 cadrée dans le [plan métier](docs/produit/04-plan-metier.md) : suivi d’un constat démarré à l’approbation de la mission, actions avec responsable audité, échéance et justificatifs, vérification par l’équipe d’audit et clôture par le validateur.
- [Guide des actions correctives](docs/technique/27-actions-correctives.md) proposé : remediation-service, synchronisation par outbox et lecture interne, base et API, lots de tests.
- ADR-0022 accepté : actions correctives dans un service dédié, sans texte de constat dans Kafka. Catalogue d’événements complété. Aucun code ni test pour F-006.

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
- Garage privé installé sur le serveur privé et raccordé par Tailscale.
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
