# Hébergement privé avec k3s

**Date :** 2026-10-24
**Statut :** phase 0 en cours, phase 1 implémentée et mesurée localement. Aucune installation de Kubernetes sur le serveur.
**Décision :** [ADR-0023](../decisions/ADR-0023-hebergement-prive-k3s.md)

## Objectif

Héberger AuditSphere sur un serveur privé administré par l'utilisateur, dans un cluster Kubernetes, avec les outils d'une plateforme professionnelle. L'usage visé est d'une ou deux personnes. L'architecture reste prête à monter en charge, mais chaque composant tourne avec une seule réplique et des ressources mesurées.

## Relevé du serveur

Relevé effectué le 2026-10-24 par SSH, avec des commandes en lecture seule.

| Élément | Valeur relevée |
| --- | --- |
| Processeur | x86, 4 cœurs, 8 threads, virtualisation matérielle présente |
| Mémoire | 6,7 Gio visibles, 5,6 Gio disponibles, 4 Gio de swap |
| Disque | NVMe de 1 To, 168 Go libres sur la partition ext4 de Linux |
| Système | Ubuntu 24.04 LTS |
| Réseau | Liaison unique sans redondance, Tailscale actif |
| Alimentation | Aucune alimentation secourue |
| Déjà en service | Docker, Garage, outils de protection et de supervision de l'hôte, mises à jour automatiques |
| Kubernetes | Aucun |

Les services de l'hôte consomment environ 1,2 Gio. La charge moyenne relevée est de 0,02. Le reste du disque n'est pas disponible pour Linux et ne sert pas aux volumes.

Points à confirmer par l'utilisateur, car ils demandent sudo ou le BIOS :
- La mémoire n'est probablement pas extensible. Le firmware déclare deux emplacements. `sudo dmidecode -t memory` permet de confirmer.
- L'écart entre 8 Go et 6,7 Gio visibles correspond probablement à la mémoire réservée au processeur graphique intégré. La réduire dans le BIOS peut rendre 1 à 1,5 Gio.
- Les ports d'administration locaux doivent n'écouter que sur 127.0.0.1.

## Budget mémoire

Limites ajustées après les mesures locales de la phase 1. Les lignes de PostgreSQL, Temporal, k3s et Tailscale restent des estimations jusqu'aux phases 2 et 3. Une réplique par composant.

| Composant | Limite visée |
| --- | --- |
| k3s, SQLite intégré, sans Traefik ni servicelb | environ 500 Mio |
| PostgreSQL unique, une base par service | 256 Mio |
| Kafka, un broker KRaft, tas de 256 Mio | 512 Mio |
| Temporal, processus unique persistant dans PostgreSQL | 384 Mio |
| Keycloak, tas réglé | 640 Mio |
| audit-service | 448 Mio |
| api-gateway | 384 Mio |
| notification-service, remediation-service | 2 × 320 Mio |
| workflow-worker | 256 Mio |
| Frontend Next.js | 192 Mio |
| Opérateur Tailscale | environ 100 Mio |
| **Application et socle** | **environ 4,2 Gio** |

Avec l'hôte, le total atteint environ 5,4 Gio sur 6,7 Gio. Les outils de plateforme se répartissent en deux paliers.

| Palier | Outils |
| --- | --- |
| Permanent | Traefik en ClusterIP, cert-manager, Argo CD minimal, Sealed Secrets, Prometheus réglé |
| À la demande | Grafana, Loki, Tempo, collecteur OpenTelemetry, interface Argo CD |

La pile complète en permanence demanderait environ 8,7 Gio. Elle ne tient pas sur cette machine sans second nœud.

## Feuille de route

Chaque phase passe à vérifié uniquement avec ses critères de sortie prouvés.

### Phase 0 : préparation de l'hôte

- Réduire la mémoire vidéo réservée dans le BIOS et confirmer le type de mémoire.
- Vérifier que les ports d'administration locaux n'écoutent que sur 127.0.0.1.
- Fiabiliser la liaison réseau si possible.
- Conserver l'outil actuel de supervision de l'hôte.
- Donner à la machine un nom Tailscale neutre, sans indication de lieu ni de propriétaire.

Sortie : mémoire visible relevée après réglage, aucun port d'administration joignable hors de l'hôte, nom de machine neutre.

Avancement au 2026-10-24 : un port d'administration local d'un outil de protection de l'hôte écoutait sur toutes les interfaces. Le pare-feu le bloquait sur le réseau local, mais il restait joignable par le tailnet. Il écoute désormais sur 127.0.0.1 uniquement. Vérifié depuis le poste d'administration : port fermé par le tailnet, service local actif. SSH et Garage restent joignables par le tailnet seulement. Le réglage de la mémoire vidéo, le relevé des emplacements mémoire, le nom neutre et la liaison réseau restent à faire.

Avancement au 2026-10-25 : relevé en lecture seule par SSH. La mémoire vidéo réservée vaut toujours 1 Gio, la liaison réseau reste en Wi-Fi seul et un fichier d'échange de 4 Gio est actif. L'utilisateur a renommé la machine `auditsphere` côté système, dans `/etc/hosts` et côté Tailscale. Un premier renommage avait attribué ce nom au poste d'administration, d'où un suffixe `-1` sur le serveur. Après correction dans la console, le nom DNS du serveur est `auditsphere` sans suffixe, vérifié depuis le serveur.

Le BIOS, consulté par l'utilisateur le 2026-10-25, ne propose aucun réglage de la mémoire vidéo. La réservation de 1 Gio est donc définitive et le budget reste calé sur 6,7 Gio visibles. Il indique deux modules de 4 Gio à 2400 MHz. Restent ouverts en phase 0 : la liaison réseau, toujours en Wi-Fi. Ports vérifiés : outils d'administration sur 127.0.0.1, Garage sur l'adresse Tailscale, SSH sur toutes les interfaces. Son filtrage par le pare-feu, relevé le 2026-10-24, n'a pas été revérifié faute de sudo.

### Phase 1 : images légères

- Créer un Dockerfile pour `apps/web` en sortie standalone, utilisateur non root, image de base épinglée par digest.
- Régler les JVM : `-XX:+UseSerialGC -XX:MaxRAMPercentage=60 -Xss512k`, pool HikariCP de 5 connexions, 50 threads Tomcat au plus. Limiter le tas de Keycloak. La valeur de 70 % prévue au départ laissait trop peu de place hors du tas.
- Publier les images dans GHCR avec un tag par SHA de commit, analyse Trivy et SBOM.
- Mesurer la mémoire réelle de chaque service sous limites avec Compose avant tout déploiement sur le serveur.

Sortie : tableau des mémoires mesurées, images publiées et analysées par la CI.

#### Réalisation

- `apps/web/Dockerfile` : trois étapes sur `node:24.22.1-alpine` épinglé par digest, sortie `standalone`, utilisateur 10001, aucun secret dans l'image. npm et corepack sont retirés de l'image finale, car le serveur ne les exécute pas et leurs dépendances embarquées portaient des vulnérabilités. Les en-têtes de sécurité sont calculés au build : l'argument `APP_ORIGIN` vaut une origine HTTPS par défaut pour émettre HSTS.
- `Dockerfile` des services Java : `JAVA_TOOL_OPTIONS` porte les réglages JVM. Kubernetes pourra les surcharger par variable d'environnement.
- audit-service, notification-service et remediation-service : pool HikariCP de 5 connexions et 50 threads Tomcat. Les flux SSE de notification-service sont asynchrones et ne gardent pas de thread. La gateway est réactive, sans Tomcat ni pool SQL.
- Compose : `mem_limit` sur les cinq processus Java et Keycloak. Keycloak reçoit `JAVA_OPTS_KC_HEAP` avec un tas à 55 % de sa limite. Le développement local et la CI tournent donc sous les mêmes limites que la cible.
- CI : job `images` après `verify`, en matrice sur les cinq images. Construction taguée par SHA de commit, analyse Trivy 0.75.0 épinglée par digest, SBOM CycloneDX en artefact. Les pull requests construisent et analysent. Seul un push sur `main` publie dans GHCR.
- GHCR : le premier push crée le paquet de chaque image. Les paquets sont publics, k3s pourra donc tirer les images sans secret. Si un push échoue avec `denied` lors de la création d'un paquet, relancer les jobs échoués avant de modifier la CI.

#### Mesures

Mesures du 2026-10-24 sur Docker Desktop arm64, après le smoke et les scénarios de pannes Kafka et Temporal. La mémoire anonyme exclut le cache de fichiers récupérable. Le serveur est en x86 : les valeurs y seront proches mais restent à confirmer en phase 4.

| Conteneur | Avant réglage | Limite | Mémoire anonyme | Pic cgroup |
| --- | --- | --- | --- | --- |
| audit-service | 636 Mio | 448 Mio | 396 Mio | 422 Mio |
| api-gateway | 428 Mio | 384 Mio | 302 Mio | 308 Mio |
| notification-service | 385 Mio | 320 Mio | 259 Mio | 264 Mio |
| remediation-service | 343 Mio | 320 Mio | 228 Mio | 233 Mio |
| workflow-worker | 347 Mio | 256 Mio | 178 Mio | 183 Mio |
| Keycloak, mode développement | 654 Mio | 640 Mio | 479 Mio | 640 Mio, cache compris |
| Frontend, conteneur | non mesuré | 192 Mio | 63 Mio | 173 Mio, cache compris |
| Kafka, inchangé | 508 Mio | aucune | 373 Mio | 516 Mio |

La colonne avant réglage provient de `docker stats` sans limite, avec G1 et un tas maximal au quart de la mémoire de la VM. Les services Java sans limite totalisaient 2,1 Gio. Ils occupent 1,33 Gio sous limites. Aucun arrêt OOM n'a été relevé.

Un premier essai à 320 Mio et 70 % de tas a passé les tests, mais audit-service atteignait 295 Mio de mémoire anonyme et touchait sa limite 432 fois. Il lui faut environ 140 Mio hors du tas pour Hibernate, Kafka, Temporal et le client S3. Avec un tas à 60 %, un tas série finit par occuper son plafond : la mémoire anonyme d'audit-service suit donc sa limite, avec environ 50 Mio de marge.

#### Analyse des vulnérabilités

Avant TECH-009, Trivy relevait des vulnérabilités hautes et critiques corrigeables dans les quatre images Java : Tomcat 10.1.55, Netty 4.1.135, Jackson 2.21.4, pilote PostgreSQL 42.7.11, BouncyCastle dans la gateway et le binaire `pebble` de l'image de base. Spring Framework 6.2.19 portait des vulnérabilités critiques corrigées uniquement en 7.0.9. L'analyse restait en mode rapport.

La migration vers Spring Boot 4.0.9 et Spring Framework 7.0.10, décidée par [ADR-0024](../decisions/ADR-0024-migration-spring-boot-4.md), corrige ces dépendances. L'analyse locale du 2026-10-25, avec Trivy 0.75.0 et la base du 2026-10-24, a ensuite relevé deux restes :

- `lz4-java` 1.10.1, tiré par le client Kafka 4.1.2 dans audit-service et notification-service. Le parent Maven force la version 1.11.4.
- La bibliothèque standard Go de `/usr/bin/pebble`, présent dans `eclipse-temurin:21-jre` depuis son passage à Ubuntu 26.04. L'empreinte épinglée était déjà la plus récente du tag. L'image finale passe à `eclipse-temurin:21-jre-noble`, sur Ubuntu 24.04 LTS, avec le même JDK 21.0.12.1 et sans ce binaire.

Après ces corrections, les quatre images Java ne présentent aucune vulnérabilité haute ou critique corrigeable. La commande de la CI avec `--exit-code 1` renvoie 0 pour chacune.

L'analyse est désormais bloquante dans la CI. Le rapport JSON, le tableau du résumé et le SBOM sont produits d'abord, puis l'étape de blocage échoue sur toute vulnérabilité haute ou critique corrigeable. Une image bloquée n'est pas publiée dans GHCR mais laisse ses preuves en artefact. Une vulnérabilité publiée après la fusion peut donc bloquer une pull request sans rapport avec elle : il faut alors mettre à jour la dépendance concernée. L'image du frontend ne présentait aucune vulnérabilité haute ou critique corrigeable lors de la phase 1. Elle n'a pas été réanalysée par TECH-009.

### Phase 2 : installation de k3s

- k3s à un nœud avec SQLite intégré et `--disable traefik --disable servicelb`.
- Réservations `--system-reserved` et `--kube-reserved` avec seuils d'éviction, pour protéger l'hôte.
- API Kubernetes sur le port 6443 joignable uniquement par Tailscale. `kubectl` utilisé depuis le Mac par le tailnet.
- Opérateur Kubernetes Tailscale pour l'entrée et le certificat HTTPS sur `*.ts.net`.
- Les certificats HTTPS de Tailscale sont inscrits dans les journaux publics Certificate Transparency. Les noms de machine, d'entrée et de tailnet deviennent publics. Les choisir neutres avant la première émission.
- Docker reste installé pour Garage. k3s utilise son propre containerd.

Sortie : API et entrée injoignables hors du tailnet, nœud stable après redémarrage.

#### Procédure proposée

Statut : exécutée et vérifiée le 2026-10-25, opérateur Tailscale compris. Les commandes `sudo` sont lancées par l'utilisateur, qui saisit lui-même son mot de passe. Les valeurs entre chevrons restent hors du dépôt.

Version retenue : `v1.36.5+k3s1`, canal stable au 2026-10-25. La branche 1.37 est trop récente pour un premier cluster.

Prérequis :

- Nom neutre définitif, sans suffixe, et `/etc/hosts` aligné, fait le 2026-10-25. Ce nom sert de nom de nœud et figure dans `tls-san`.
- Règles du pare-feu de l'hôte, selon la documentation k3s, avec l'API limitée à l'interface Tailscale :

```bash
sudo ufw allow in on tailscale0 to any port 6443 proto tcp
sudo ufw allow from 10.42.0.0/16 to any
sudo ufw allow from 10.43.0.0/16 to any
```

Fichier `/etc/rancher/k3s/config.yaml`, écrit avant l'installation :

```yaml
write-kubeconfig-mode: "0600"
disable:
  - traefik
  - servicelb
secrets-encryption: true
tls-san:
  - "<nom-dns-tailscale>"
kubelet-arg:
  - "system-reserved=cpu=500m,memory=768Mi"
  - "kube-reserved=cpu=500m,memory=512Mi"
  - "eviction-hard=memory.available<256Mi,nodefs.available<10%"
  - "eviction-soft=memory.available<512Mi"
  - "eviction-soft-grace-period=memory.available=2m"
```

Ces réservations laissent environ 5,2 Gio allouables sur 6,7 Gio, avant le gain attendu de la mémoire vidéo. Le budget de l'application et du socle, hors k3s, est d'environ 3,7 Gio.

Installation, script téléchargé puis relu avant exécution. Le script vérifie l'empreinte SHA-256 du binaire publié :

```bash
curl -sfL https://get.k3s.io -o k3s-install.sh
sudo INSTALL_K3S_VERSION=v1.36.5+k3s1 sh k3s-install.sh
```

Accès depuis le poste d'administration : le fichier `/etc/rancher/k3s/k3s.yaml` est copié une seule fois par `scp`, son adresse `127.0.0.1` remplacée par le nom DNS Tailscale, puis il est conservé en mode 600 hors du dépôt. Il donne un accès administrateur complet au cluster.

Points à vérifier après l'installation :

- Le kubelet démarre malgré le fichier d'échange actif. Sinon, ajouter `fail-swap-on=false` aux arguments du kubelet.
- L'hôte porte déjà des règles de pare-feu pour Docker et pour l'outil de protection. Vérifier que les pods résolvent les noms et sortent vers Internet, et que Garage reste joignable.
- `kubectl get nodes` renvoie `Ready` depuis le poste par le tailnet.
- Les ports 6443 et 10250 sont injoignables depuis le réseau local.
- Le nœud revient `Ready` après un redémarrage complet de l'hôte.

L'opérateur Tailscale est installé ensuite, une fois le nœud validé. Voir la section dédiée ci-dessous.

#### Exécution du 2026-10-25

- Le pare-feu de l'hôte portait trois règles héritées d'un ancien Traefik : 80/tcp, 443/tcp et 443/udp ouverts à toute origine, en IPv4 et IPv6. Rien n'y écoutait, mais tout service futur sur ces ports aurait été exposé au réseau local. Elles sont supprimées, conformément à ADR-0023.
- La règle 6443 sur l'interface Tailscale est redondante avec la règle existante qui autorise tout le trafic de cette interface. Elle est conservée pour documenter l'intention.
- Un script unique, exécuté avec `sudo`, applique le pare-feu, la configuration et l'installation, puis attend le nœud. Le script d'installation téléchargé est identique à celui du dépôt k3s, et il a vérifié l'empreinte du binaire.
- Le kubelet démarre avec le fichier d'échange actif, sans argument supplémentaire.
- Le kubeconfig est copié sur le poste d'administration en mode 600, hors du dépôt, avec le nom DNS Tailscale comme adresse. La copie temporaire sur le serveur est supprimée.

Résultats :

- Nœud `Ready` en `v1.36.5+k3s1`, coredns, local-path-provisioner et metrics-server en fonctionnement.
- `kubectl` fonctionne depuis le poste par le tailnet.
- Ports 6443 et 10250 fermés depuis le réseau local, avant et après redémarrage. Le serveur répond au ping sur ce réseau, la fermeture vient donc bien du pare-feu.
- Un pod de test résout les noms internes et externes, sort vers Internet et joint l'API. La politique `deny (routed)` du pare-feu ne bloque pas le trafic des pods.
- Mémoire allouable : 5,2 Gio sur 6,7 Gio, conforme aux réservations.
- Après un redémarrage complet de l'hôte, k3s, Tailscale, Docker et Garage repartent seuls. Le nœud revient `Ready` et les pods système redémarrent sans intervention.
- Mémoire du service k3s mesurée à environ 440 Mio juste après le démarrage, sous l'estimation de 500 Mio. Charge totale de l'hôte relevée par metrics-server : environ 1,8 Gio.

Limites et suites :

- Un pod ne joint pas Garage : connexion refusée sur son port publié. Le refus vient probablement des règles Docker propres à l'hôte, non lisibles sans `sudo`. À traiter en phase 3, avant d'utiliser Garage depuis le cluster.
- Liaison réseau toujours en Wi-Fi.

#### Opérateur Tailscale

Statut : installé et vérifié le 2026-10-25.

Préparation dans la console Tailscale, faite par l'utilisateur :

- HTTPS activé avec MagicDNS.
- Étiquettes déclarées dans la politique du tailnet : `tag:k8s-operator` sans propriétaire humain, `tag:k8s` détenue par `tag:k8s-operator`.
- Client OAuth limité aux droits d'écriture sur les appareils, les clés d'authentification et les services, avec l'étiquette `tag:k8s-operator`. Il n'expire pas, contrairement à une clé d'API.
- Secret `tailscale/operator-oauth`, clés `client_id` et `client_secret`, créé depuis le poste par une saisie masquée. Il n'apparaît ni dans le dépôt, ni dans l'historique du shell, ni dans les comptes rendus.

Installation : [deploy/k3s/tailscale-operator.yaml](../../../deploy/k3s/tailscale-operator.yaml) déclare une ressource `HelmChart` lue par le contrôleur Helm intégré à k3s. Aucun binaire Helm n'est nécessaire sur le poste. Le chart `1.104.2` est épinglé, ainsi que les images de l'opérateur et du proxy par empreinte. L'opérateur est limité à 128 Mio. Le proxy du serveur d'API reste désactivé : `kubectl` passe par l'API directement joignable sur le tailnet. Argo CD reprendra ce fichier en phase 5.

Vérifications :

- L'opérateur rejoint le tailnet comme appareil étiqueté et crée la classe d'entrée `tailscale`.
- Un service de test exposé par une entrée `tailscale` obtient sa propre adresse du tailnet et un certificat Let's Encrypt valide. HTTPS répond 200 depuis le poste par le tailnet.
- Ports 80 et 443 fermés depuis le réseau local. Aucun service `NodePort` ni `LoadBalancer` dans le cluster.
- Mémoire mesurée : 24 Mio pour l'opérateur, 19 Mio par proxy d'entrée. L'estimation de 100 Mio du budget couvre l'opérateur et quelques proxys.
- À la suppression du service de test, son proxy et son appareil du tailnet disparaissent.

Limites :

- Chaque entrée publie son nom dans les journaux Certificate Transparency. Le nom du service de test est neutre. Les noms des futures entrées doivent l'être aussi.
- L'accès depuis un réseau extérieur au tailnet n'est pas testé directement. Il découle de l'absence de port exposé sur l'hôte et de l'adressage du tailnet, non routable depuis Internet.
- `test_image_pins.py` couvre les manifestes de `deploy/k3s` depuis la phase 3, y compris les images écrites en élément de liste. Deux mutations, image non épinglée et empreinte vide, font échouer le test.

### Phase 3 : couche de données

- Un StatefulSet PostgreSQL avec une base et des rôles runtime et migrateur par service.
- Kafka à un broker sur l'image officielle `apache/kafka`. Rétention de sept jours conservée, plafond de taille ajouté.
- Temporal en processus unique regroupant ses services, persistant dans PostgreSQL. Le mode start-dev reste interdit selon ADR-0020.
- Garage reste dans Docker hors du cluster, joint par l'adresse Tailscale.
- Volumes local-path sur la partition ext4.

Sortie : reprise des données après redémarrage du nœud, isolation des comptes SQL vérifiée.

#### Exécution du 2026-10-25

Statut : implémentée et vérifiée le 2026-10-25. Manifestes dans [deploy/k3s/data](../../../deploy/k3s/data), namespace `data`, images épinglées par empreinte.

- `postgres.yaml` : PostgreSQL 17.9 en StatefulSet, volume local-path de 10 Gio, mémoire limitée à 256 Mio, `shared_buffers` à 32 Mo, 80 connexions au plus, utilisateur non root.
- `postgres-bootstrap.yaml` : Job idempotent qui crée sept bases et leurs rôles. Pour audit, notification, remediation et web : un rôle de migration propriétaire et un rôle d'exécution qui peut seulement se connecter et utiliser le schéma, avec délais de requête et de verrou. Keycloak et Temporal ont chacun un rôle propriétaire, Temporal possède `temporal` et `temporal_visibility`. Les droits sur les tables viendront après les migrations Flyway, en phase 4.
- `kafka.yaml` : Kafka 4.0.2 à un broker KRaft, tas de 256 Mio, conservation de sept jours et de 256 Mio au plus par partition. Un Job crée les huit topics de `compose.yaml`. `enableServiceLinks` est désactivé : Kubernetes injecte sinon des variables `KAFKA_*` pour le service `kafka`, que l'image lirait comme des réglages du broker.
- `temporal.yaml` : Temporal 1.32.1 en processus unique, persistant dans PostgreSQL, sans mode start-dev. Un Job pose et met à jour les schémas avec `temporal-sql-tool`, un autre crée le namespace `default` avec sept jours de rétention. Quatre shards d'historique, valeur définitive. L'image attend un fichier de configuration dynamique, fourni vide par une ConfigMap. Sans lui, le serveur s'arrête au démarrage.
- Mots de passe générés aléatoirement depuis le poste et écrits directement dans les secrets `postgres-superuser` et `postgres-roles`. Ils ne sont ni affichés ni versionnés.

Vérifications :

- Isolation SQL : douze contrôles réussis. Un rôle d'exécution ne se connecte qu'à sa base et ne crée pas de table. Un rôle de migration ne joint pas les autres bases. Aucun rôle applicatif ne crée de rôle.
- Le Job de préparation relancé ne change rien et réussit.
- Message Kafka produit puis relu, workflow Temporal démarré puis retrouvé.
- Après un redémarrage complet de l'hôte : ligne témoin PostgreSQL, message Kafka et workflow Temporal retrouvés. Temporal échoue une fois au démarrage, PostgreSQL n'acceptant pas encore de connexion, puis Kubernetes le relance avec succès.
- Mémoire relevée après redémarrage : PostgreSQL 79 Mio, Kafka 416 Mio, Temporal 67 Mio. Charge totale du nœud : 2,7 Gio.

Garage :

- Le refus constaté en phase 2 venait de l'hôte. Au démarrage, Docker démarrait avant que Tailscale n'ait son adresse. La publication du port de Garage sur cette adresse échouait et le conteneur restait sans réseau, joignable par personne, poste compris.
- Correctif appliqué par l'utilisateur avec `sudo` : Docker démarre après Tailscale et attend au plus 60 s son adresse par `/usr/local/sbin/wait-tailscale-ip.sh`, appelé depuis un fichier complémentaire de l'unité systemd. Le conteneur a été recréé une fois, ses données restant dans les répertoires de l'hôte.
- Vérifié après redémarrage complet : port publié seul, Garage joignable depuis le poste et depuis un pod, réponse S3 403 sans identifiants.

Limites :

- Kafka occupe 416 Mio sur 512. La marge est faible, à surveiller en phase 6.
- Aucune NetworkPolicy : tout pod du cluster joint PostgreSQL, Kafka et Temporal. Le frontal Temporal n'a ni TLS ni authentification. À traiter en phase 8.
- Les règles Docker de l'hôte autorisent encore un ancien sous-réseau local. Sans effet tant que Garage n'écoute que sur l'adresse Tailscale, à nettoyer.
- Pas de sauvegarde de ces volumes avant la phase 7.

### Phase 4 : application avec Helm

- Chart générique `infra/helm/spring-service` et un fichier de valeurs par service, plus Keycloak avec le royaume de production et le frontend.
- Migrations Flyway dans un Job avec le compte migrateur.
- Sondes, demandes basses, limites explicites et une réplique par déploiement.
- Traefik en ClusterIP derrière l'entrée Tailscale, pour les en-têtes de sécurité, la limitation de débit et le routage.
- Smoke et scénarios de pannes Kafka et Temporal exécutés par le tailnet, avec des données synthétiques uniquement.

Sortie : smoke réussi par l'URL HTTPS du tailnet, scénarios de pannes réussis sur le cluster. Atteinte le 2026-10-26 au lot E.

Choix du 2026-10-25, faits par l'utilisateur : profil de démonstration avec comptes synthétiques, noms du tailnet `app` pour le frontend et `auth` pour Keycloak. La phase est découpée en cinq lots : A services Spring, B Keycloak, C frontend et Traefik, D configuration de Keycloak, Garage et données de démonstration, E smoke, pannes et mesure.

#### Lot A : services Spring

Statut : implémenté et vérifié le 2026-10-25.

- Chart générique [infra/helm/spring-service](../../../infra/helm/spring-service) et un fichier de valeurs par release dans `infra/helm/values` : audit-service, notification-service, remediation-service, workflow-worker et api-gateway, dans le namespace `auditsphere` au niveau Pod Security `restricted`.
- Images GHCR du commit `294a0ff` de `main`, tirées par empreinte. Le code applicatif est identique à celui du dernier commit de `main`.
- Conteneurs non root, système de fichiers en lecture seule avec `/tmp` en mémoire, aucun jeton de compte de service monté, variables de liens de service désactivées. Sondes de démarrage, de disponibilité et de vivacité sur les points de santé Actuator.
- Avant chaque installation ou mise à jour, un Job Helm exécute Flyway avec le rôle de migration du service, puis applique les droits minimaux du rôle d'exécution avec le même rôle propriétaire. Les droits reprennent ceux de `scripts/harden-databases.py` : journaux et notes en ajout seul.
- `scripts/k3s-deploy-services.sh` installe les cinq releases depuis le poste avec Helm 4.3.1, installé par Homebrew. Les valeurs propres au tailnet, origine OIDC et adresse de Garage, viennent de `.local/k3s.env`, ignoré par Git.
- Secrets créés dans le cluster sans affichage : mots de passe SQL copiés depuis `data`, secrets des clients Keycloak provisoires jusqu'au lot D.
- Bucket Garage `auditsphere-evidence-k3s` et clé dédiée au cluster, en lecture et écriture sur ce seul bucket. Le développement local garde son bucket et sa clé.

Vérifications :

- Flyway applique 18 migrations à audit, puis celles de notification et de remediation. Les cinq services démarrent sans erreur.
- Huit contrôles de droits réussis : lecture des missions, refus de suppression ou de modification des journaux, refus de lecture de l'historique Flyway pour le rôle d'exécution.
- Le worker interroge la file Temporal `audit-review-v1`.
- La gateway répond 401 sans jeton.
- Un second déploiement est idempotent : aucune migration à appliquer, droits réappliqués, pods conservés.
- `test_image_pins.py` vérifie aussi les empreintes des valeurs Helm. Une empreinte vidée fait échouer le test.
- Mémoire : audit-service 354 Mio, notification-service 182 Mio, api-gateway 176 Mio, remediation-service 159 Mio, worker 106 Mio. Charge du nœud : 4,0 Gio.

Limites :

- Keycloak n'est pas encore déployé. Les jetons ne peuvent pas encore être émis ni validés de bout en bout.
- La marge mémoire devient faible : Keycloak, le frontend et Traefik ajoutent environ 0,9 Gio de limites. À mesurer au lot E.
- Le worker ne journalise rien après le démarrage de la JVM. Sa connexion est vérifiée côté Temporal.

#### Lot B : Keycloak

Statut : implémenté et vérifié le 2026-10-25, sauf l'émission d'un jeton par un vrai client, prévue au lot D.

- Chart [infra/helm/keycloak](../../../infra/helm/keycloak) : Keycloak 26.8.0 épinglé, mode `start` de production, base `keycloak` de la phase 3, mémoire limitée à 640 Mio. `scripts/k3s-deploy-services.sh` l'installe avant les services.
- Le royaume `deploy/keycloak/production-realm.json` est importé au premier démarrage seulement : royaume, rôles et `sslRequired` à `all`. Clients et comptes de démonstration viendront au lot D.
- Entrée Tailscale `auth`. Le proxy Tailscale termine le TLS. `KC_HOSTNAME` porte l'origine HTTPS du tailnet, lue dans `.local/k3s.env`, et `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` laisse les services joindre `keycloak:8080` en interne.
- Administrateur d'amorçage dans le secret `keycloak-admin`, généré sans affichage.
- Sondes sur le port de gestion 9000, conteneur non root sans privilège.

Vérifications :

- Royaume `auditsphere` importé, découverte OIDC en HTTPS par le tailnet, émetteur égal à l'origine HTTPS, points d'autorisation en HTTPS.
- Keycloak reconnaît le HTTPS derrière l'entrée : la page de compte s'affiche sans erreur « HTTPS required ».
- Les clés publiques du royaume sont lisibles depuis un pod par l'adresse interne, celle utilisée par les services pour valider les jetons.
- Une demande de jeton par l'adresse interne HTTP reçoit un refus du client inconnu, pas un refus HTTPS.
- Mémoire : Keycloak 573 Mio, proxy d'entrée 65 Mio. Demandes mémoire du nœud à 54 % de l'allouable, limites à 71 %. L'hôte garde 2,8 Gio disponibles.

Limites :

- Les pods ne résolvent pas les noms du tailnet. Les services doivent garder l'adresse interne de Keycloak.
- La console d'administration répond sur le nom `auth` du tailnet. Elle devra être refusée sur le nom public selon ADR-0025.
- L'administrateur d'amorçage est temporaire. Le lot D doit créer un administrateur permanent avec OTP puis le retirer.

#### Lot C : frontend et Traefik

Statut : implémenté et vérifié le 2026-10-26, sauf la connexion complète, qui attend le client `auditsphere-web` du lot D.

- [deploy/k3s/coredns-tailnet.yaml](../../../deploy/k3s/coredns-tailnet.yaml) fait résoudre `ts.net` par MagicDNS. Le BFF fait la découverte OIDC et lit les clés de Keycloak côté serveur, par l'origine HTTPS du tailnet. Sans ce renvoi, les pods ne résolvaient pas ces noms. Appliqué à la main avec `kubectl apply`, CoreDNS redémarré une fois.
- Chart [infra/helm/web](../../../infra/helm/web) : BFF Next.js du commit `294a0ff`, Traefik v3.7.16 en bordure et entrée Tailscale `app`. `scripts/k3s-deploy-services.sh` l'installe en dernier.
- Un Job Helm applique le schéma des sessions de `apps/web/db` avec `web_migrator`, puis donne au seul rôle `web_runtime` les droits de lecture et d'écriture sur `web_sessions`.
- Clé de chiffrement des sessions et secret provisoire du client OIDC dans le secret `web-secrets`, générés sans affichage.
- Traefik écrase `X-Real-Ip`, limite le débit à 50 requêtes par seconde en moyenne avec une rafale de 100, et ajoute HSTS, `nosniff`, le refus des cadres et la politique de référent. Le BFF ajoute sa propre CSP avec nonce.
- Deux NetworkPolicy : le BFF n'accepte que Traefik, Traefik n'accepte que le proxy de l'entrée Tailscale. Un autre pod ne peut donc pas forger `X-Real-Ip`.
- Conteneurs non root, système de fichiers en lecture seule, cache de Next.js en mémoire.

Vérifications :

- `GET /` par le tailnet répond 200 avec HSTS, CSP, `nosniff`, `X-Frame-Options` et `Referrer-Policy`.
- `/auth/login` redirige vers Keycloak sur l'origine `auth`, avec PKCE S256 et le retour attendu. La découverte OIDC fonctionne donc depuis le pod.
- Une route d'API répond 401 sans session, et non 503 : l'en-tête posé par Traefik arrive au BFF.
- Depuis un autre namespace, le BFF et Traefik sont injoignables. La gateway reste joignable, comme prévu jusqu'à la phase 8.
- `test_image_pins.py` vérifie aussi les valeurs par défaut des charts. Une image psql non épinglée et une empreinte remplacée par `latest` font échouer le test.
- Mémoire : BFF 51 Mio, Traefik 17 Mio, proxy de l'entrée 19 Mio. Demandes du nœud à 57 % de l'allouable, limites à 77 %. L'hôte garde 2,7 Gio disponibles.

Limites :

- Traefik voit l'adresse du proxy Tailscale, pas celle de l'utilisateur. La limitation de débit s'applique donc à tous les utilisateurs ensemble.
- Le renvoi DNS couvre tout `ts.net`, pas seulement le tailnet, pour ne pas versionner son nom.

#### Lot D : Keycloak, comptes de démonstration et données

Statut : implémenté et vérifié le 2026-10-26.

- [scripts/k3s-setup-keycloak.py](../../../scripts/k3s-setup-keycloak.py) configure Keycloak de façon idempotente par l'origine HTTPS du tailnet. Il lit les secrets dans le cluster et n'affiche aucune valeur secrète.
- Automatisation : client `k3s-provisioner` du royaume `master`, par identifiants client, secret dans `keycloak-provisioner`. Il remplace l'administrateur d'amorçage, supprimé au premier passage.
- Administration humaine : compte `kc-admin`, OTP exigé à la première connexion.
- Royaume `auditsphere` : rôles, client `auditsphere-web` en code avec PKCE, clients serveur annuaire, gestion des utilisateurs et `remediation-service` avec les secrets déjà montés dans les pods. Aucun redéploiement n'est nécessaire.
- Six comptes synthétiques : manager, auditor, outsider, validator, superadmin et auditee. Mots de passe dans `.local/k3s-demo-accounts.env`, ignoré par Git, en mode 600.
- Client public `auditsphere-demo` avec mot de passe direct, pour le peuplement et le smoke, sur le tailnet seulement.
- Protection contre la force brute, journal des événements, rotation des refresh tokens, inscription libre désactivée, HTTPS exigé partout.
- `scripts/seed-demo-missions.py` accepte désormais `SEED_API_BASE`, `SEED_TOKEN_URL`, `SEED_ACCOUNTS_FILE` et `SEED_MANIFEST`. Les valeurs par défaut restent celles de la pile locale. La gateway est jointe par un `kubectl port-forward` temporaire.

Vérifications :

- Second passage du script idempotent, par le client d'automatisation.
- Un compte de service obtient un jeton par l'adresse interne HTTP malgré `sslRequired` à `all`.
- Après redémarrage de Keycloak, le royaume `master` ne contient que `kc-admin` : l'administrateur d'amorçage n'est pas recréé.
- Jetons de démonstration avec l'émetteur HTTPS attendu, l'audience `auditsphere-api` et les rôles attendus. La gateway sert les missions. Les actions correctives répondent 403 au responsable et à l'auditeur, 200 à l'audité.
- Connexion complète simulée comme un navigateur : page de Keycloak, retour sur `/missions`, cookie `__Host-` de session, `/api/session` qui renvoie le compte attendu.
- 40 missions synthétiques créées, tous statuts couverts. Le validateur reçoit ses notifications par Kafka. Temporal compte sept revues en cours, une par mission en attente de revue.

À faire avant toute ouverture publique, en phase 8 :

- Désactiver `auditsphere-demo` et le mot de passe direct.
- Exiger l'OTP du compte `superadmin`. Il n'est pas exigé maintenant pour garder les tests par mot de passe.
- Refuser la console d'administration sur le nom public.

Incident du 2026-10-26 :

- L'hôte s'est arrêté sans extinction propre, puis a été rallumé par l'utilisateur. La batterie indique 0 % et ne charge pas : elle ne protège plus contre une coupure.
- Tout est revenu seul : nœud, 14 pods, Garage publié. Les données étaient intactes, ce qui confirme les essais de redémarrage des phases 2 et 3.
- La cause exacte n'est pas établie : le journal système de l'hôte n'est lisible qu'avec `sudo`.

Publication GHCR après la recréation du dépôt, le 2026-10-26 :

- Le run de `main` sur `ddb8cc1` réussit la vérification et l'analyse des images, puis échoue à la publication avec `permission_denied: write_package` pour les cinq images.
- Les paquets `auditsphere-*` restent liés à l'ancien dépôt. Le jeton du nouveau dépôt n'y a pas accès en écriture.
- L'ancien dépôt n'a pas été supprimé mais renommé `AuditSphere-old`, privé. Il restait source des paquets avec le rôle Admin, le nouveau dépôt n'avait que le rôle Read.
- Correctif appliqué par l'utilisateur sur chacun des cinq paquets : rôle Write pour `AuditSphere` dans l'accès des actions, puis retrait de la source `AuditSphere-old`. La publication suivante relie le paquet au nouveau dépôt grâce au label `org.opencontainers.image.source` posé par la CI.
- Vérifié : sixième tentative du run réussie, les cinq images de `ddb8cc1` répondent en HTTP 200 sans authentification. Le cluster utilise toujours les images de `294a0ff`, déjà publiées.
- Reste possible : retirer `AuditSphere-old` de l'accès des actions et ramener le rôle à Write là où Admin a été donné.

#### Lot E : smoke, pannes et mesure

Statut : implémenté et vérifié le 2026-10-26. La phase 4 est terminée.

Outillage :

- [scripts/target.py](../../../scripts/target.py) choisit la pile visée par les scripts de vérification : Compose local par défaut, k3s avec `AUDITSPHERE_TARGET=k3s`. Il fournit SQL, `pg_dump`, outils Kafka, CLI Temporal, arrêt et démarrage des composants.
- Dans k3s, un arrêt ramène le déploiement à zéro réplique et attend la disparition du pod. Un démarrage remet une réplique et attend la sonde de disponibilité. Un redémarrage est un arrêt suivi d'un démarrage, pour obtenir une vraie coupure.
- `smoke.py`, `test-notifications.py`, `test-temporal.py` et `cleanup-test-data.py` passent par ce module. Le destinataire des tests est lu dans le jeton de l'auditeur au lieu d'un identifiant fixe.
- [scripts/k3s-check.sh](../../../scripts/k3s-check.sh) lance un de ces scripts contre le cluster : `kubectl port-forward` temporaire vers la gateway, comptes de `.local/k3s-demo-accounts.env`, secret du client `remediation-service` lu dans le cluster sans affichage.
- La CLI Temporal tourne dans un pod éphémère `temporal-cli`, décrit par [deploy/k3s/tools/temporal-cli.yaml](../../../deploy/k3s/tools/temporal-cli.yaml) et supprimé en fin de script. L'image du serveur ne contient pas la CLI.
- [scripts/smoke-bff.py](../../../scripts/smoke-bff.py) se connecte comme un navigateur par l'origine HTTPS du tailnet : formulaire Keycloak, retour sur `/missions`, session, missions servies par la gateway, déconnexion.
- Le nettoyage refuse de supprimer des preuves dans k3s : la clé de stockage locale ne doit jamais toucher le bucket du cluster.

Vérifications, le 2026-10-26 :

- Smoke du BFF par l'URL HTTPS du tailnet : connexion OIDC, cookie `__Host-` sécurisé et HttpOnly, en-têtes HSTS, CSP, `X-Content-Type-Options` et `Referrer-Policy`, 40 missions servies au responsable, 401 sans session, session supprimée après déconnexion.
- Smoke de l'API par la gateway : création, isolation entre comptes, 401 sans jeton ou avec un jeton invalide, refus par rôle, validation, actions correctives réservées à l'audité, compte de service `REMEDIATION_SYNC` sans rôle humain.
- Pannes Kafka : livraison nominale, rejeu sans doublon, message invalide en DLT puis message suivant livré, reprise après arrêt de notification-service et après arrêt de Kafka avec une notification unique.
- Pannes Temporal : trois cycles de revue dont un retrait, décisions acceptées pendant l'arrêt du worker, workflow conservé après redémarrage de Temporal, audit-service redémarré pendant l'arrêt de Temporal, rejeu sans nouveau workflow.
- Nettoyage des six missions de test, sauvegarde privée prise avant suppression. Données de démonstration intactes : 40 missions, dont 7 en attente de revue, et 7 workflows Temporal en cours.
- Mémoire après les scénarios : 1,58 Gio disponibles sur le nœud, aucun échange en swap, aucun arrêt OOM ni éviction. Keycloak 549 Mio, audit-service 362 Mio, Kafka 323 Mio, gateway 232 Mio, notification-service 207 Mio, remediation-service 201 Mio, PostgreSQL 139 Mio, frontend 124 Mio, Traefik 94 Mio, Temporal 92 Mio. Limites des pods à 79 % de la mémoire allouable, demandes à 58 %.
- `kubectl top node` affiche 102 % de la mémoire allouable avant les scénarios. Ce chiffre inclut le cache de pages. La mémoire réellement disponible restait de 1,37 Gio.

Limites :

- L'arrêt seul de la base des notifications n'est pas testé : PostgreSQL est partagé dans le cluster. Ce scénario reste couvert en local.
- Le pic de mémoire pendant les redémarrages n'est pas mesuré, seulement l'état avant et après. La supervision de la phase 6 le suivra.
- Le chemin Compose des scripts modifiés n'a pas été relancé. Seules la compilation et les tests unitaires des scripts sont vérifiés.
- Premier essai des pannes Kafka en échec : l'attente d'arrêt visait aussi le pod terminé du Job de migration. Corrigé en ne comptant que les pods actifs, second essai réussi.
- Images toujours celles de `294a0ff` : le code applicatif est identique à celui de `ddb8cc1`, aucun déploiement n'était utile.
- Un nouveau redémarrage du nœud a été constaté le 2026-10-26, avant le lot E. Tout est revenu seul. La cause n'est pas établie.

### Phase 5 : GitOps

- Argo CD en installation minimale, sans Dex, notifications ni ApplicationSet. Interface démarrée à la demande.
- Sealed Secrets pour les secrets, jamais de secret en clair dans Git.
- La CI écrit les digests d'images dans `infra/gitops`. Le retour arrière redéploie le digest précédent.

Sortie : déploiement d'un commit et retour arrière vérifiés depuis Git seul.

### Phase 6 : observabilité par paliers

- Prometheus permanent avec rétention de deux jours et collecte toutes les 60 secondes.
- Grafana, Loki et Tempo démarrés à la demande, mis à zéro réplique ensuite.
- Alertes : disque, mémoire des JVM, retard Kafka, messages en DLT, workflows bloqués, erreurs 5xx, expiration des certificats. Envoi vers le téléphone de l'utilisateur.

Sortie : une alerte de test reçue, une requête tracée de la gateway au service.

### Phase 7 : sauvegardes et restauration

- CronJob `pg_dump` de chaque base, sauvegarde Garage par `deploy/garage/backup.sh`, copies restic chiffrées hors machine. La cible est le Mac par le tailnet ou un stockage objet externe.
- La clé de chiffrement est conservée hors du serveur.
- L'état de Temporal est couvert par la sauvegarde PostgreSQL.
- Exercice de restauration dans un namespace isolé avec contrôle d'empreinte des preuves.

Sortie : restauration complète réussie et datée. Cette phase est prioritaire, car le serveur n'a aucune protection électrique.

#### Exécution du 2026-10-26

Statut : implémenté et vérifié le 2026-10-26, sortie de phase atteinte. Agent launchd du poste non encore installé. Décision : [ADR-0027](../decisions/ADR-0027-sauvegardes-tirees-par-le-poste.md), le poste tire les sauvegardes et Garage est copié à chaud par S3. La phase 7 est avancée avant le lot C de la phase 8, choix de l'utilisateur.

Serveur, [deploy/k3s/backup/backup.yaml](../../../deploy/k3s/backup/backup.yaml) :

- Namespace `backup` au niveau Pod Security `restricted`, refus par défaut des entrées, volume local-path de 5 Gio.
- CronJob `backup` chaque jour à 2 h 30 UTC, une exécution à la fois, 30 minutes au plus. Premier conteneur : `pg_dump` au format personnalisé de chaque base, rôles sans mots de passe, fichier `SHA256SUMS`. Le jeu est écrit dans un dossier partiel, renommé une fois complet, puis le marqueur `LATEST` avance. Sept jours conservés.
- Second conteneur : rclone 1.75.2 copie le bucket `auditsphere-evidence-k3s` sans jamais supprimer, avec `--immutable`.
- Rôle PostgreSQL `backup` créé par `postgres-bootstrap` quand son mot de passe existe dans `postgres-roles` : membre de `pg_read_all_data`, droit de connexion sur chaque base, aucun droit d'écriture. Règle d'entrée de PostgreSQL étendue aux pods `backup` du namespace `backup`.
- [scripts/k3s-backup-setup.sh](../../../scripts/k3s-backup-setup.sh) : `postgres` génère le mot de passe du rôle sans l'afficher. `garage`, lancé par l'utilisateur, crée la clé Garage `auditsphere-backup` par SSH vers l'hôte, limitée à la lecture du bucket, et écrit le secret `garage-backup`. Il lit `GARAGE_SSH` dans `.local/k3s.env`.

Poste, [scripts/k3s-backup.sh](../../../scripts/k3s-backup.sh) :

- `init` crée le mot de passe restic dans le trousseau macOS, service `auditsphere-restic`, puis le dépôt dans `~/AuditSphere-backups/restic`, hors du dépôt Git.
- `run` déclenche le CronJob tout de suite. `pull` lit le dernier jeu par le pod éphémère [backup-reader](../../../deploy/k3s/tools/backup-reader.yaml), monté en lecture seule, vérifie les empreintes et l'ajoute à restic. Rétention : 7 jours, 8 semaines, 12 mois, puis `restic check`. Le dossier de transit est effacé à la fin.
- `schedule` installe un agent launchd qui lance `pull` chaque jour à 9 h.

Restauration, [scripts/k3s-restore-test.sh](../../../scripts/k3s-restore-test.sh) : instantané restic restauré sur le poste, empreintes des dumps vérifiées, chaque base restaurée dans un PostgreSQL vide du namespace `restore-test`, sans entrée ni sortie réseau ([manifeste](../../../deploy/k3s/tools/restore-test.yaml)). Contrôles : tables présentes dans chaque base, missions dans `audit`, royaume dans `keycloak`, namespaces dans `temporal`. Chaque preuve de la base restaurée doit exister dans la copie de Garage avec le même SHA-256. Le namespace est supprimé à la fin.

[scripts/seed-backup-evidence.py](../../../scripts/seed-backup-evidence.py) dépose deux preuves synthétiques, un PDF et un PNG, sur une mission en cours. Les données de démonstration n'en avaient aucune, ce qui aurait rendu le contrôle d'empreinte sans objet.

Vérifications, le 2026-10-26 :

- Rôle `backup` : ni superutilisateur ni création de rôle, membre de `pg_read_all_data`, connexion admise sur les huit bases.
- Premier essai du dump refusé à la connexion : la règle réseau n'admet un nouveau pod qu'après environ deux secondes. Le script attend désormais PostgreSQL 60 secondes au plus. Le même essai a montré qu'un échec de `psql` dans une liste de boucle n'arrêtait pas le script, corrigé. Second essai réussi en trois secondes.
- Deux preuves synthétiques déposées par la gateway, empreintes renvoyées identiques à celles calculées.
- Dépôt restic initialisé, restic 0.19.1, mot de passe dans le trousseau. L'utilisateur en a fait une copie hors du poste, non vérifiable par l'agent.
- Clé Garage `auditsphere-backup` créée par SSH : lecture seule sur le seul bucket des preuves, aucune création de bucket. Le rôle `backup` n'a ni INSERT, ni UPDATE, ni DELETE sur les tables d'audit.
- Jeu complet par `run` : dumps et copie Garage réussis. Tirage par `pull` : 12 fichiers, 594 Kio, empreintes vérifiées, instantané restic enregistré, `restic check` réussi.
- Exercice de restauration réussi et daté, de 12 h 31 min 20 s à 12 h 31 min 37 s UTC le 2026-10-26 : sept bases restaurées, 40 missions et migration 18 dans `audit`, royaume présent dans `keycloak`, deux namespaces dans `temporal`. Deux preuves sur deux présentes avec le même SHA-256. Namespace `restore-test` supprimé ensuite.
- Mémoire du nœud après l'exercice : 84 % utilisés.

Non vérifié : la détection d'une preuve altérée ou absente n'a pas été provoquée. Agent launchd non installé, il attend l'accord de l'utilisateur.

Limites :

- Les copies hors machine n'avancent que poste allumé. L'âge du dernier tirage devra être surveillé en phase 6.
- Dumps en clair sur le disque du serveur pendant sept jours, écart à ADR-0020 accepté par ADR-0027.
- Ni les métadonnées de Garage, ni les secrets Kubernetes, ni Kafka ne sont copiés. Voir les conséquences d'ADR-0027.
- La copie Garage relit la liste complète du bucket à chaque passage, et le tirage transfère toute la copie. Acceptable au volume actuel, à revoir si les preuves dépassent quelques gigaoctets.

### Phase 8 : sécurité C7

- NetworkPolicies, intégrées à k3s sans coût supplémentaire.
- cert-manager avec une autorité interne, Kafka en SASL_SSL avec ACL, Temporal en mTLS, PostgreSQL en TLS vérifié, Garage en HTTPS.
- Critères de sortie d'ADR-0020 appliqués au cluster.

Sortie : tous les critères d'ADR-0020 vérifiés. Aucune donnée réelle avant les phases 7 et 8.

Découpage proposé le 2026-10-26, en cinq lots :

- A : NetworkPolicy en entrée, refus par défaut dans `auditsphere` et `data`.
- B : cert-manager et autorité interne, mémoire mesurée.
- C : PostgreSQL en TLS vérifié et Temporal en mTLS.
- D : Kafka en SASL_SSL avec une identité et des ACL par service.
- E : Garage en HTTPS, durcissement de Keycloak avant ouverture, rotation des certificats et critères de sortie.

Décision à prendre avant le lot C : ADR-0020 exige une sauvegarde et une restauration vérifiée avant de basculer les connexions en TLS. Or la phase 7 vient après la phase 8 dans l'ordre retenu. Deux voies possibles : avancer la phase 7, ou faire un `pg_dump` daté avec un essai de restauration avant chaque bascule. Le lot A ne bascule aucune connexion et n'est pas concerné. Décision de l'utilisateur, le 2026-10-26 : la phase 7 est avancée avant le lot C.

#### Lot A : NetworkPolicy

Statut : implémenté et vérifié le 2026-10-26.

- Refus par défaut des entrées dans les deux namespaces : [deploy/k3s/auditsphere-network-policies.yaml](../../../deploy/k3s/auditsphere-network-policies.yaml) et [deploy/k3s/data/network-policies.yaml](../../../deploy/k3s/data/network-policies.yaml). Un nouveau pod sans règle propre ne reçoit rien.
- Chart `spring-service` : une règle par service, alimentée par `allowFrom` dans les valeurs. Sans port HTTP, comme le worker, aucune entrée n'est admise.
- Chart `keycloak` : entrées du proxy Tailscale et des quatre services qui lisent les clés publiques ou demandent un jeton.
- Le Job `web-schema` porte désormais `app.kubernetes.io/name: web-schema`, pour être désigné dans la règle de PostgreSQL.

| Cible | Port | Entrées admises |
| --- | --- | --- |
| api-gateway | 8080 | web |
| audit-service | 8081 | api-gateway, notification-service, remediation-service |
| notification-service | 8082 | api-gateway |
| remediation-service | 8083 | api-gateway |
| workflow-worker | aucun | aucune |
| keycloak | 8080 | proxy Tailscale, api-gateway, audit-service, notification-service, remediation-service |
| web et edge | 3000, 8000 | inchangés depuis la phase 4 : edge, puis proxy Tailscale |
| postgres | 5432 | Temporal, Jobs d'amorçage, audit, notification, remediation, Keycloak, web, web-schema |
| kafka | 9092 | audit-service, notification-service |
| temporal | 7233 | lui-même sur tous ses ports, CLI éphémère, Job du namespace, audit-service, workflow-worker |

Les Jobs de migration Flyway portent le nom de leur service et héritent de ses droits vers PostgreSQL. Les sondes du kubelet, `kubectl exec` et `kubectl port-forward` ne passent pas par ces règles : les scripts de vérification fonctionnent sans exception.

Vérifications, le 2026-10-26 :

- Tous les pods restent prêts après l'application. Aucun redémarrage provoqué.
- Smoke du BFF et smoke de l'API réussis avec toutes les règles en place.
- Refus constatés : depuis le BFF, audit-service, Keycloak, Kafka et Temporal refusent la connexion. Depuis Kafka, PostgreSQL et Temporal la refusent aussi. Le BFF joint la gateway et PostgreSQL.
- Pannes Kafka réussies sous les règles.
- Pannes Temporal : premier essai en échec, réponse 503 à une soumission pendant l'arrêt de Temporal et de notification-service. Second essai réussi sans changement. Sept missions de test nettoyées, données de démonstration intactes : 40 missions, dont 7 en attente de revue.

Limites :

- Les sorties ne sont pas filtrées. Un pod compromis peut joindre Internet, le tailnet et Garage. Un filtrage des sorties demande d'abord l'inventaire des adresses du tailnet utilisées par le BFF et audit-service.
- Les futurs composants, Prometheus et sauvegardes, devront être ajoutés aux règles des cibles qu'ils interrogent.
- Les règles de `data` sont appliquées par `kubectl apply`, hors Helm. Leur application a été faite par l'utilisateur.
- Cause du 503 non établie : les journaux ont été perdus au redémarrage des pods par le test. Hypothèse : le contrôle de révocation de la gateway, borné à 2 secondes, a expiré vers audit-service. Les règles ne sont pas en cause, ce flux est autorisé et le second essai réussit. À suivre avec la supervision de la phase 6.

#### Lot B : cert-manager et autorité interne

Statut : implémenté et vérifié le 2026-10-26. Décision de structure : [ADR-0026](../decisions/ADR-0026-autorite-interne-racine-hors-ligne.md), racine hors ligne.

- [deploy/k3s/cert-manager.yaml](../../../deploy/k3s/cert-manager.yaml) : cert-manager v1.21.3 installé par le contrôleur Helm de k3s depuis le registre OCI de l'éditeur. Images épinglées par empreinte, solveur ACME compris. Namespace au niveau Pod Security `restricted`, refus par défaut des entrées. Job de contrôle de démarrage et métriques Prometheus désactivés.
- [scripts/k3s-internal-ca.sh](../../../scripts/k3s-internal-ca.sh), lancé par l'utilisateur dans son terminal : racine ECDSA P-384 chiffrée dans `.local/ca`, intermédiaire ECDSA P-256 avec `pathlen:0`, secret `cert-manager/internal-ca`, puis ClusterIssuer. La clé de l'intermédiaire n'existe plus sur le poste après le chargement. Le script refuse de remplacer un secret existant.
- [deploy/k3s/internal-ca-issuer.yaml](../../../deploy/k3s/internal-ca-issuer.yaml) : ClusterIssuer `auditsphere-internal-ca`.
- `enableCertificateOwnerRef` : supprimer un Certificate supprime son secret.

Vérifications, le 2026-10-26 :

- Les trois pods de cert-manager sont prêts. Un essai côté serveur passe par le webhook malgré le refus par défaut : l'API de k3s le joint depuis le nœud.
- Racine valable jusqu'en 2036, intermédiaire jusqu'en 2028, chaîne vérifiée avec OpenSSL. Clé racine chiffrée, fichiers en mode 600, dossier ignoré par Git.
- ClusterIssuer prête. Certificat de test émis dans `data` : chaîne vérifiée jusqu'à la racine du poste, `ca.crt` identique à cette racine, SAN, usage serveur seul et `CA:FALSE` attendus. Son secret disparaît avec lui.
- Mémoire : cert-manager 24 Mio, cainjector 16 Mio, webhook 11 Mio. Nœud : 2,67 Gio disponibles. Limites des pods à 83 % de la mémoire allouable, demandes à 59 %.

Limites :

- Premier lancement du script interrompu en apparence : il attendait la troisième saisie de la phrase secrète. Le fichier de l'intermédiaire existe vide pendant cette attente.
- La copie hors ligne de la clé racine et le rangement de la phrase secrète sont à la charge de l'utilisateur. Ils ne sont pas vérifiables par l'agent.
- Aucun service n'utilise encore ces certificats : la bascule en TLS commence au lot C.
- Le renouvellement automatique et la rotation de l'intermédiaire sont testés au lot E.

#### Lot C1 : PostgreSQL en TLS vérifié

Statut : implémenté et vérifié le 2026-10-26. C2, Temporal en mTLS, reste à faire.

Le lot C est découpé en deux. C1 bascule PostgreSQL et ne demande que de la configuration. C2, Temporal en mTLS, demande un changement du client Java, donc de nouvelles images publiées par la CI après fusion.

- [deploy/k3s/data/postgres.yaml](../../../deploy/k3s/data/postgres.yaml) : Certificate `postgres-tls` émis par `auditsphere-internal-ca`, ECDSA P-256, 90 jours, renouvelé 30 jours avant l'échéance, nouvelle clé à chaque renouvellement. Noms : `postgres`, `postgres.data`, `postgres.data.svc` et le nom complet du service.
- `ssl=on`, TLS 1.2 au minimum. `pg_hba.conf` vient de la ConfigMap `postgres-hba` : socket local en confiance pour les sondes et `kubectl exec`, `hostssl` avec SCRAM, refus de toute connexion réseau en clair. Les règles en confiance sur la boucle locale de l'image sont retirées.
- Conteneur `reload` dans le pod : toutes les 60 secondes, il compare l'empreinte du certificat et de `pg_hba.conf`, et demande `pg_reload_conf()` par le socket local si elles changent. PostgreSQL ne relit ni l'un ni l'autre sans ce rechargement.
- [scripts/k3s-ca-bundle.sh](../../../scripts/k3s-ca-bundle.sh) publie la racine hors ligne dans la ConfigMap `internal-ca` des namespaces `data`, `auditsphere` et `backup`. La source est la copie locale de la racine, jamais un certificat lu dans le cluster.
- Clients en `verify-full` contre cette racine, montée en `/etc/auditsphere/ca` : audit, notification et remediation et leurs Jobs de migration par l'option `internalCa` du chart `spring-service`, Keycloak, BFF et Job `web-schema`, Temporal et son Job de schéma, `postgres-bootstrap`, CronJob de sauvegarde.

Bascule, selon ADR-0020 :

1. Sauvegarde fraîche tirée sur le poste, jeu `20261026T143600Z`.
2. Racine publiée, puis PostgreSQL redémarré avec TLS et une règle de transition qui admet encore le clair.
3. Clients de `data` et de `backup` basculés, puis Keycloak, services et BFF redéployés par `scripts/k3s-deploy-services.sh`.
4. Contrôle de `pg_stat_ssl`, puis règle définitive appliquée et rechargée sans redémarrage.

Vérifications, le 2026-10-26 :

- Certificate prête. PostgreSQL redémarré une fois, `ssl` actif, aucune erreur dans les règles chargées.
- Depuis un pod de test autorisé : `verify-full` réussi en TLS 1.3, connexion par adresse IP refusée car le nom ne correspond pas, connexion refusée avec les seules autorités du système.
- Après les smokes, `pg_stat_ssl` montre toutes les connexions réseau en TLS 1.3 : audit, notification, remediation, BFF, Keycloak et Temporal. Aucune connexion en clair.
- Règle définitive rechargée par le conteneur `reload`, sans redémarrage. Connexion en clair refusée avec `pg_hba.conf rejects connection ... no encryption`.
- Smoke de l'API et smoke du BFF réussis. Jeu de sauvegarde par TLS, tirage et exercice de restauration réussis : sept bases, deux preuves sur deux avec le même SHA-256.
- Pannes Kafka et Temporal réussies avec PostgreSQL en TLS. Quatre missions de test nettoyées par `cleanup-test-data.py`, sauvegarde privée préalable. Restent 40 missions de démonstration, dont 7 en attente de revue, et les deux preuves témoins.
- Mémoire du nœud après les scénarios : 79 %. Aucun pod en échec.

Incident pendant la bascule : le Job `web-schema` a échoué trois fois avec `Connection refused`, ce qui a fait échouer la mise à jour Helm du BFF. Même cause que le premier essai de sauvegarde : la règle réseau n'admet un nouveau pod qu'après environ deux secondes. Le Job et le conteneur `grants` des migrations attendent désormais PostgreSQL 60 secondes au plus. Seconde mise à jour réussie. Le BFF déjà en service n'a pas été interrompu.

Limites :

- Le conteneur `reload` relit la configuration au renouvellement, mais le renouvellement réel n'est testé qu'au lot E.
- Le socket local reste en confiance : quiconque peut lancer `kubectl exec` dans le pod est superutilisateur. C'était déjà le cas avant ce lot.
- Les clients ne présentent pas de certificat : l'authentification reste SCRAM par mot de passe.

#### Lot C2 : Temporal en mTLS

Statut : implémenté et vérifié le 2026-10-26. C2a, client Java, fusionné par #8, images de `9af0ebb` publiées. C2b, configuration du cluster, appliquée le même jour.

C2a, client Java :

- `TemporalConnection` construit les options du client Temporal pour le publieur d'audit-service et pour workflow-worker. mTLS est activé quand `TEMPORAL_TLS_CA_FILE`, `TEMPORAL_TLS_CERT_FILE` et `TEMPORAL_TLS_KEY_FILE` sont définis. Sans aucune de ces variables, la connexion reste en clair, pour Compose et les tests.
- Configuration partielle, fichier illisible ou clé invalide : le service refuse de démarrer. Il ne retombe jamais en clair.
- La clé doit être au format PKCS#8. Le nom du serveur est vérifié contre l'hôte de `TEMPORAL_TARGET`.
- `TemporalConnectionTest` : cinq cas, dont un certificat EC généré par `keytool` pendant le test. Aucune clé n'est versionnée.

C2b, cluster :

- [deploy/k3s/data/temporal-tls.yaml](../../../deploy/k3s/data/temporal-tls.yaml) : quatre Certificate de l'autorité interne, 90 jours. Serveur Temporal avec usages serveur et client et noms du service. `temporal-admin` pour le Job `temporal-namespace` et le pod de CLI. Un certificat par appelant Java, `audit-service` et `workflow-worker`, clé PKCS#8, usage client seul.
- Serveur : `TEMPORAL_TLS_REQUIRE_CLIENT_AUTH` actif, racine hors ligne comme seule autorité cliente. Le trafic interne du processus vise l'adresse du pod : `TEMPORAL_TLS_INTERNODE_SERVER_NAME` fait vérifier le nom du service à la place. Certificats relus toutes les 10 minutes par `TEMPORAL_TLS_REFRESH_INTERVAL`.
- Chart `spring-service` : option `temporalTls.secret`, qui monte le certificat client et définit les trois variables de `TemporalConnection`. Le rendu échoue si `internalCa.enabled` est absent. `fsGroup` à 10001 : les secrets montés en 0440 restent lisibles par le seul processus.
- Pods Temporal avec `fsGroup` à 1000 pour la même raison. La CLI prend `TEMPORAL_TLS_CLIENT_CERT_PATH`, `TEMPORAL_TLS_CLIENT_KEY_PATH` et `TEMPORAL_TLS_SERVER_CA_CERT_PATH`.
- `scripts/k3s-check.sh` attend que Temporal réponde au pod de CLI avant de lancer un script.

Bascule : sauvegarde `20261026T153220Z` tirée sur le poste, certificats émis, serveur redémarré en mTLS, puis audit-service et workflow-worker redéployés aussitôt avec l'image `9af0ebb`. Seul changement d'audit-service depuis l'image précédente : C2a.

Vérifications, le 2026-10-26 :

- En local, avant fusion : `./mvnw -B verify` réussi, tests d'intégration compris.
- Job `temporal-namespace` réussi par mTLS. Aucune erreur TLS dans les journaux de Temporal, d'audit-service et du worker.
- Depuis le pod de CLI : certificat d'administration admis. Refusés : connexion en clair, TLS sans certificat client, connexion par adresse IP sur le nom du certificat, certificat de PostgreSQL à usage serveur seul.
- Les premiers essais ont reçu `connection refused` avant toute négociation TLS : délai d'admission réseau d'un nouveau pod, déjà vu aux lots précédents.
- Pannes Temporal réussies sous mTLS : trois cycles dont un retrait, décision durable, reprise et rejeu. Smoke de l'API et smoke du BFF réussis. Outbox des revues vide après la bascule.
- Sept workflows de revue en cours, autant que de missions en attente de revue : aucun n'a été perdu. Missions de test nettoyées, 40 missions de démonstration intactes.
- Mémoire du nœud : 83 %. Aucun pod en échec.

Limites :

- Le client Java charge ses certificats au démarrage. Un renouvellement demande un redémarrage du pod, à traiter au lot E.
- mTLS authentifie l'appelant mais ne restreint pas ses droits : tout certificat client de l'autorité interne est admis. Une autorisation par nom de certificat reste à décider.

### Phase 9 : extension

- Second nœud agent k3s joint par Tailscale, puis HPA si une mesure le justifie.
- Essai ponctuel d'AKS ou EKS sur crédits gratuits, piloté par Terraform et détruit le jour même, avec alerte de budget.
- Images natives GraalVM envisageables plus tard, en tenant compte des contraintes de Hibernate, Kafka et Temporal.
- Aucun accès public avant la vérification des phases 6, 7 et 8.

### Phase 10 : exposition publique

Décidée par [ADR-0025](../decisions/ADR-0025-exposition-publique-cloudflare-tunnel.md). Statut : proposée le 2026-10-25, rien n'est configuré.

Préparation possible dès maintenant, par l'utilisateur :

- Ajouter le domaine à Cloudflare, offre gratuite, et vérifier que les enregistrements DNS existants sont recopiés.
- Remplacer les serveurs de noms chez Namecheap par ceux indiqués par Cloudflare, puis attendre l'activation de la zone.
- Créer un tunnel géré à distance dans Cloudflare Zero Trust, sans nom public, et conserver son jeton hors du dépôt.

Mise en œuvre, après les phases 4, 6, 7 et 8 :

- `cloudflared` dans le namespace `cloudflared`, image épinglée, jeton dans un secret créé par saisie masquée.
- Deux noms publics : l'application vers le service du frontend, l'identité vers le service de Keycloak.
- Console d'administration de Keycloak sur un nom du tailnet, chemins `/admin` refusés sur le nom public.
- Règles Cloudflare : TLS 1.2 minimum, HSTS, règles gérées du pare-feu applicatif, limitation de débit sur la connexion.
- Keycloak : inscription désactivée, détection de force brute, MFA pour les administrateurs.

Sortie : application servie en HTTPS sur le domaine, aucun port entrant ouvert sur l'hôte ni sur la box, gateway et services internes injoignables depuis Internet, console Keycloak refusée sur le nom public.

#### Condition d'ouverture

Arrêtée le 2026-10-26 avec l'utilisateur, conformément à ADR-0023 et ADR-0025. Aucun nom public ne sert l'application avant que ces quatre éléments soient vérifiés :

1. HOST-004, lots D et E : connexion de bout en bout, comptes de démonstration et smoke réussi dans le cluster. Vérifié le 2026-10-26.
2. HOST-008, sécurité C7 : NetworkPolicy autour de la gateway, de PostgreSQL, de Kafka et de Temporal, vérifiées le 2026-10-26 au lot A, TLS et authentification de Temporal, vérifiés le 2026-10-26 au lot C2, console Keycloak refusée sur le nom public, MFA des administrateurs, inscription libre désactivée.
3. HOST-006 : supervision avec alertes de disponibilité et d'abus.
4. HOST-007 : sauvegardes hors machine et restauration vérifiée.

Ordre retenu : lots D et E, puis phases 8, 6, 7 et 10. Révisé le 2026-10-26 par l'utilisateur : la phase 7 passe avant le lot C de la phase 8, pour disposer d'une restauration vérifiée avant toute bascule en TLS. Ordre en vigueur : lots A et B de la phase 8, phase 7, lots C à E, puis phases 6 et 10.

Préparation possible sans attendre, par l'utilisateur : délégation DNS du domaine à Cloudflare, après recopie des enregistrements existants, et création du tunnel sans nom public.

Décision restant à prendre avant l'ouverture : ce qu'un visiteur anonyme peut faire, par exemple un compte de démonstration en lecture seule.

Seule la phase 7 pourrait être reportée, puisque l'instance publique ne porte que des données de démonstration. Ce report exigerait un amendement d'ADR-0025. Les phases 6 et 8 ne sont pas négociables.

## Limites connues

- Pas de haute disponibilité. Un reboot interrompt le service.
- Liaison réseau unique et aucune alimentation secourue.
- Un seul disque, d'où l'obligation de sauvegardes externes.
- Les budgets mémoire sont des estimations jusqu'aux mesures de la phase 1. Mesure du cluster complet au lot E de la phase 4.

## Vérifications effectuées

- Relevé matériel et logiciel du serveur par SSH le 2026-10-24, sans sudo ni modification.
- Tarifs cloud relevés le 2026-10-24 pour l'arbitrage d'ADR-0023. Ils ne sont pas vérifiés sur les pages officielles d'AWS et d'Azure.
- Aucune installation, aucun déploiement et aucun test sur le serveur à la date du relevé.
- Phase 0 : port d'administration local refermé sur 127.0.0.1 le 2026-10-24, vérifié depuis le tailnet. Commande de configuration exécutée par l'utilisateur avec sudo.
- Phase 1, le 2026-10-24 en local : smoke, scénarios de pannes Kafka et Temporal réussis sous limites, aucun arrêt OOM. Build Next.js et typecheck réussis avec la sortie standalone. Conteneur frontend démarré en utilisateur 10001, pages servies et HSTS présent. Analyse Trivy locale des cinq images. Tests Python d'épinglage étendus et réussis.
- Non vérifié : parcours Playwright locaux non relancés, car un serveur de développement occupait le port 3000. Ils sont exécutés par la CI. Publication GHCR non vérifiée avant la fusion dans `main`, vérifiée ensuite.
- Publication GHCR, le 2026-10-25 sur `main` après la fusion de la phase 1 : premier run, quatre images sur cinq refusées par GHCR avec `denied` au push du manifeste, alors que les couches étaient déjà poussées. Seule audit-service était publiée. Les jobs échoués, relancés sans changement de code, ont réussi. Les cinq images du commit `cb91e28` répondent en HTTP 200 sans authentification. Cause probable : création simultanée de paquets neufs par la matrice. Non confirmée, faute d'accès aux réglages des paquets.
- TECH-009, le 2026-10-25 en local : `./mvnw -B verify` réussi avec 108 tests Java. Pile relancée par `start-local.sh`, puis smoke, pannes Kafka et Temporal réussis. Aucun arrêt OOM ni redémarrage sous les limites existantes. Mémoire relevée après les scénarios : audit-service 369 Mio sur 448, gateway 188 Mio sur 384, notification-service 206 Mio sur 320, remediation-service 172 Mio sur 320, worker 109 Mio sur 256. Analyse Trivy bloquante réussie sur les quatre images Java. `test-browser-isolated.sh` réussi : 13 suites, 30 parcours Playwright.
- Phase 3, le 2026-10-25 sur le serveur : couche de données, isolation SQL, redémarrage complet et correctif de Garage. Détails dans l'exécution de la phase 3.
- Phase 2, le 2026-10-25 sur le serveur : installation de k3s, contrôle des ports depuis le réseau local, pod de test et redémarrage complet. Détails dans l'exécution du 2026-10-25 ci-dessus. Opérateur Tailscale installé, entrée HTTPS testée par le tailnet.
- TECH-009, le 2026-10-25 sur `main` : CI distante réussie sur `2743acb` après la fusion. Job `verify` et cinq jobs `images` réussis, analyse Trivy bloquante comprise. Aucun job relancé.
- Phase 4 lot E, le 2026-10-26 sur le serveur : smoke du BFF et de l'API, pannes Kafka et Temporal réussis dans le cluster, nettoyage des données de test, mesure mémoire sans OOM. Détails dans le lot E.
- Phase 8 lot A, le 2026-10-26 sur le serveur : NetworkPolicy en entrée, refus constatés depuis des pods non autorisés, smoke et pannes Kafka réussis sous les règles, pannes Temporal réussies au second essai après un 503 isolé. Détails dans le lot A.
- Phase 7, le 2026-10-26 sur le serveur et le poste : dumps et copie Garage, tirage restic chiffré sur le poste, restauration isolée réussie avec contrôle d'empreinte des preuves. Détails dans l'exécution de la phase 7.
- Phase 8 lot C2, le 2026-10-26 sur le serveur : Temporal en mTLS, certificat client par appelant, refus du clair, de l'absence de certificat, d'un nom faux et d'un certificat à usage serveur. Pannes Temporal et smokes réussis, aucun workflow perdu. Détails dans le lot C2.
- Phase 8 lot C1, le 2026-10-26 sur le serveur : PostgreSQL en TLS vérifié pour tous ses clients, clair refusé, smokes, pannes, sauvegarde et restauration réussis. Détails dans le lot C1.
- Phase 8 lot B, le 2026-10-26 sur le serveur : cert-manager installé, autorité intermédiaire chargée par l'utilisateur, certificat de test émis et vérifié jusqu'à la racine hors ligne, mémoire mesurée. Détails dans le lot B.
