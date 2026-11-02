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

Révision du 2026-10-30, à la demande de l'utilisateur, qui lisait 20 % comme la mémoire utilisée :

- Relevé par `free -m` sur l'hôte : 5 500 Mio utilisés sur 6 867, 1 367 Mio disponibles, 290 Mio d'échange. La tuile de mémoire du tableau de bord Grafana calcule `min(node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)` : ses 20 % sont la mémoire libre. Le « Memory usage » affiché à la connexion vient de `/var/lib/landscape/landscape-sysinfo.cache`, figé au 2026-10-28 à 22:38 UTC.
- Hausse générale écartée : les JVM dimensionnent leur tas en pourcentage de leur limite, l'usage réel suivrait donc. Les limites du nœud atteignaient déjà 143 % de la mémoire allouable.
- Hausse ciblée sur les conteneurs proches de leur limite : Keycloak de 640 à 768 Mio pour 542 Mio relevés, notification-service de 320 à 384 Mio pour 274 Mio relevés. Grafana était déjà porté à 640 Mio le même jour, audit-service à 768 Mio depuis le 2026-10-28.
- Déployé par `helm upgrade --reuse-values`. Le rendu à blanc ne changeait que la limite, plus le Job de migration de notification-service. Pods prêts sans redémarrage, `smoke.py` réussi par `k3s-check.sh`.
- Mesure juste après : Keycloak 545 Mio, notification-service 231 Mio, 1 419 Mio disponibles sur l'hôte. Demandes du nœud à 78 % de la mémoire allouable, limites à 149 %.
- Non vérifié : comportement sous charge et pic de mémoire au prochain redémarrage de l'hôte.

Limites en vigueur à cette date, différentes du tableau : Keycloak 768 Mio, audit-service 768 Mio, notification-service 384 Mio.

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

L'analyse est désormais bloquante dans la CI. Le rapport JSON, le tableau du résumé et le SBOM sont produits d'abord, puis l'étape de blocage échoue sur toute vulnérabilité haute ou critique corrigeable. Une image bloquée n'est pas publiée dans GHCR mais laisse ses preuves en artefact. Une vulnérabilité publiée après la fusion peut donc bloquer une pull request sans rapport avec elle : il faut alors mettre à jour la dépendance concernée. Cas du 2026-10-27, sur la pull request du lot 6C-b, sans changement de code Java : CVE-2026-102554 dans `guava` 33.4.8-android, tiré par le SDK Temporal dans audit-service, et CVE-2026-108106 et CVE-2026-93451 dans `snappy-java` 1.1.10.7, tiré par le client Kafka dans audit-service et notification-service. Le parent Maven force `guava` 33.7.2-android, même variante, et `snappy-java` 1.1.10.9. Cas du 2026-10-29, sur la pull request #38 de documentation : CVE-2026-111497 dans `libbrotli1` 1.1.0-2build2 de l'image de base, dans les quatre images Java. L'empreinte de `eclipse-temurin:21-jre-noble` passe à celle publiée le 2026-10-26, qui porte `libbrotli1` 1.1.0-2ubuntu0.1. Analyse locale de l'image de remediation-service avec Trivy 0.75.0 : aucune vulnérabilité haute ou critique corrigeable. Images de `3066fbc` publiées après analyse réussie, puis déployées le même jour pour les cinq charges Spring : smoke de l'API réussi, 22 cibles actives, aucune alerte après le redémarrage, mission du smoke retirée, 40 missions de démonstration. L'image du frontend ne présentait aucune vulnérabilité haute ou critique corrigeable lors de la phase 1. Elle n'a pas été réanalysée par TECH-009.

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
- `kafka.yaml` : Kafka 4.0.2 à un broker KRaft, tas de 256 Mio, conservation de sept jours et de 256 Mio au plus par partition. Un Job crée les huit topics de `compose.yaml`, remplacé au lot D de la phase 8 par `scripts/k3s-kafka-security.sh`. `enableServiceLinks` est désactivé : Kubernetes injecte sinon des variables `KAFKA_*` pour le service `kafka`, que l'image lirait comme des réglages du broker.
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
- Alertes : disque, mémoire des JVM, retard Kafka, messages en DLT, workflows bloqués, erreurs 5xx, expiration des certificats. Envoi vers le téléphone de l'utilisateur par un sujet ntfy.sh au nom aléatoire, gardé dans un secret Kubernetes, choix de l'utilisateur du 2026-10-27.

Sortie : une alerte de test reçue, une requête tracée de la gateway au service.

#### Lot 6A : socle de supervision et alertes d'infrastructure

Statut : implémenté et vérifié le 2026-10-27, réception sur le téléphone confirmée par l'utilisateur le même jour. Décision : [ADR-0029](../decisions/ADR-0029-supervision-prometheus-ntfy.md). Lots suivants : 6B, métriques applicatives et alertes d'abus, avec de nouvelles images, puis 6C, Grafana, Loki et Tempo à la demande et une requête tracée.

[deploy/k3s/monitoring/monitoring.yaml](../../../deploy/k3s/monitoring/monitoring.yaml), installé par le contrôleur Helm de k3s comme cert-manager :

- Namespace `monitoring` au niveau Pod Security `privileged`, car node-exporter lit `/proc` et `/sys` de l'hôte avec son réseau. Refus par défaut des entrées. Seul Prometheus joint Alertmanager, kube-state-metrics et l'exportateur blackbox.
- Chart `prometheus` 29.36.0, publié le 2026-10-07 : Prometheus 3.15.0, Alertmanager 0.34.1, node-exporter 1.12.1, kube-state-metrics 2.20.0. Pushgateway désactivée. Collecte et évaluation toutes les 60 secondes, deux jours ou 1 Go de données, volume de 2 Gio.
- Chart `prometheus-blackbox-exporter` 11.20.0, publié le même jour, exportateur 0.29.0. Sondes : bordure `edge`, préparation de la gateway, document de découverte OIDC du royaume Keycloak, et Garage en HTTPS avec réponse 403 attendue. Le service Keycloak n'expose pas son port de santé, d'où la découverte OIDC.
- Règles d'entrée ajoutées : l'exportateur blackbox vers `edge`, `api-gateway` et `keycloak` dans `auditsphere`, Prometheus vers le port 9402 des trois pods de cert-manager.
- cert-manager : métriques activées, annotations de collecte sur les pods, sans opérateur.
- k3s sert toutes ses métriques internes, environ 43 000 séries, à la fois sur l'API et sur le kubelet. Ces deux cibles ne gardent que leur disponibilité. cAdvisor reste collecté pour les conteneurs.
- 16 règles d'alerte : disque de l'hôte au-delà de 85 %, mémoire disponible sous 10 %, échange au-delà de 1 Gio, charge, node-exporter muet, conteneur arrêté par manque de mémoire, redémarrages répétés, CrashLoopBackOff, Deployment ou StatefulSet incomplet, Job en échec, aucune sauvegarde réussie depuis 26 heures, certificat expirant sous 14 jours ou non prêt, sonde en échec, cible injoignable, échec d'envoi d'Alertmanager.
- Alertmanager envoie les alertes au sujet ntfy.sh par un webhook, avec le gabarit `alertmanager` de ntfy. Les alertes critiques partent en priorité maximale et sont répétées toutes les heures, les autres toutes les six heures. Les adresses, qui contiennent le sujet, sont lues dans un fichier par `url_file`.

[scripts/k3s-monitoring.sh](../../../scripts/k3s-monitoring.sh) :

- `apply` crée le sujet aléatoire dans `.local/ntfy.env` s'il est absent, puis les secrets `alertmanager-ntfy` et `prometheus-private-targets`. Le second porte l'adresse de Garage, propre au tailnet. Il applique ensuite le manifeste. Aucune valeur secrète n'est affichée.
- `test` crée une alerte `MonitoringTest` dans Alertmanager, puis lit le sujet pour confirmer sa publication.
- Consultation depuis le poste : `kubectl --namespace monitoring port-forward svc/prometheus-server 19090:80`. Aucune interface n'est publiée.

Vérifications, le 2026-10-27 :

- Avant l'installation : 16 règles validées par `promtool check rules`, configuration d'Alertmanager validée par `amtool check-config`, rendu des deux charts relu. Gabarit `alertmanager` de ntfy essayé sur un sujet jetable avec une alerte synthétique : titre et résumé corrects.
- Après correction : 15 cibles de collecte sur 15 actives, quatre sondes réussies, aucune alerte active. 13 160 échantillons par cycle, contre environ 98 000 avant le filtrage des métriques de k3s.
- Chaîne complète par une vraie règle : les deux premières sondes visaient de mauvais ports. `ProbeFailed` est partie à 02:07:57 UTC en priorité maximale, puis sa résolution à 02:12:58 après correction. Alerte de test publiée à 02:13:27. Ces trois messages sont relus sur le sujet ntfy. Alertmanager compte trois envois réussis et aucun échec.
- Après abonnement de l'utilisateur au sujet dans l'application ntfy, une nouvelle alerte de test par `scripts/k3s-monitoring.sh test` est reçue sur son téléphone, confirmé par l'utilisateur. Critère de sortie de la phase 6 « une alerte de test reçue » atteint.
- Mémoire après installation : supervision 422 Mio, dont Prometheus 333 Mio sur une limite de 448 Mio. Prometheus garde encore en mémoire les séries d'avant le filtrage, jusqu'à sa prochaine compaction. Nœud : demandes à 66 % de la mémoire allouable, limites à 101 %. 1,8 Gio disponibles sur l'hôte, 95 Mio d'échange utilisés.

Incidents :

- Premières sondes en échec : le service `edge` publie le port 80, pas 8000, et le service Keycloak n'a pas le port 9000. Cibles corrigées.
- Deux cibles de cert-manager refusées : le webhook et l'injecteur exposent aussi leurs métriques, et la règle n'admettait que le contrôleur. Les métriques propres de kube-state-metrics, sur le port 8081, étaient aussi refusées. Règles élargies.
- Alertmanager n'était pas collecté par défaut : l'alerte d'échec d'envoi n'aurait jamais pu se déclencher. Collecte ajoutée.

Limites :

- Aucune alerte si Prometheus, Alertmanager ou le nœud s'arrête. Veille externe ajoutée le 2026-10-30 selon ADR-0036, voir la veille externe de la supervision.
- Toute personne qui connaît le sujet ntfy peut lire les alertes et en publier de fausses. Les messages ne contiennent ni secret ni donnée d'audit.
- Les limites mémoire du nœud dépassent la mémoire allouable. Une pression mémoire forte peut provoquer des arrêts. La mémoire de Prometheus est à remesurer après sa compaction.
- Pas encore de métriques applicatives : erreurs 5xx, mémoire des JVM, retard Kafka, DLT, workflows bloqués et tentatives d'abus viennent au lot 6B. Pas de journaux centralisés avant le lot 6C.
- La sonde de la bordure suit le chemin interne. Les sondes du tunnel sont en service depuis le lot B de la phase 10.
- Comme cert-manager et l'opérateur Tailscale, les charts sont épinglés par version, pas les images par empreinte. Les applications restent épinglées par empreinte.

#### Lot 6B : métriques applicatives et alertes d'abus

Statut : implémenté et vérifié le 2026-10-27. 6B-a, code et chart, fusionné par #15, images de `788e3c8` publiées. Métriques de la bordure et de Keycloak et alertes d'abus en service le même jour. 6B-b, activation des métriques des services Spring, déployé et vérifié le même jour.

Comme aux lots C2 et E2, le lot est découpé : 6B-a change le code et produit de nouvelles images, 6B-b les déploie et ajoute les règles.

6B-a :

- `micrometer-registry-prometheus` dans api-gateway, audit-service, notification-service et remediation-service. Spring Security admet `/actuator/prometheus` sans jeton, comme la santé.
- Chart `spring-service`, option `managementPort`, nulle par défaut. Active, elle place Actuator sur ce port, y expose `prometheus`, étiquette les métriques avec le nom du service, déplace les sondes de Kubernetes sur ce port et ajoute les annotations de collecte. Une règle d'entrée admet sur ce port Prometheus et l'exportateur blackbox du namespace `monitoring`, et personne d'autre. Le port routé reste fermé aux métriques.
- Option nulle : rendu du chart identique octet pour octet à celui de `main` pour les cinq services, vérifié par `diff`. Le déploiement actuel ne change pas tant que les valeurs n'activent pas l'option.
- audit-service, `OutboxMetrics` : nombre d'événements non publiés et âge du plus ancien, pour `assignment_outbox`, vers Kafka, et `review_workflow_outbox`, vers Temporal. Lus en base à chaque collecte. Une lecture en échec donne une absence de valeur, pas un zéro trompeur.
- `remediation_outbox` existe en base mais aucun code ne la publie encore : pas de mesure.

Vérifications de 6B-a, le 2026-10-27, dans l'image Maven de construction :

- `ManagementPortTest`, gateway démarrée avec les réglages du chart : métriques servies sans jeton sur le port de gestion avec l'étiquette du service, refusées sur le port routé, santé servie sur le port de gestion. Trois cas réussis.
- `OutboxMetricsTest` : jauges enregistrées pour chaque outbox, lecture des seuls événements non publiés, absence de valeur en cas d'échec. Trois cas réussis.
- Non vérifié localement : le même comportement dans les services servlet. Il sera constaté dans le cluster en 6B-b.

Bordure, Keycloak et règles, configuration seule, déployées le 2026-10-27 :

- Bordure Traefik : métriques Prometheus sur l'écoute `ping`, port 8082, jamais sur l'écoute web. Compteur par code de réponse, dont les refus 429 de la limite de débit.
- Keycloak : `KC_METRICS_ENABLED` et `KC_EVENT_METRICS_USER_ENABLED`, servis sur le port de gestion 9000. Compteur `keycloak_user_events_total` par événement, erreur et royaume.
- Annotations de collecte sur les deux pods. Règle d'entrée `metrics-from-prometheus` dans `auditsphere` : Prometheus seul vers les ports 8082 de la bordure et 9000 de Keycloak.
- Neuf règles ajoutées, 25 au total. Applications : tas des JVM au-delà de 90 %, au moins 10 erreurs 5xx en 10 minutes, retard d'un consommateur Kafka au-delà de 100 messages, message envoyé en DLT, outbox bloquée depuis plus de 10 minutes. Abus : plus de 50 refus 429 de la bordure en 5 minutes, plus de 20 requêtes par seconde sur la bordure pendant 10 minutes, plus de 20 échecs de connexion en 10 minutes, plus de 50 réponses 401, 403 ou 429 de la gateway en 10 minutes.
- Les règles des applications et celle de la gateway restent sans données jusqu'à 6B-b.
- Compteurs : `increase()` ignore la première valeur d'une série. Une série qui apparaît avec la première rafale d'une attaque, comme le premier code 429, comptait donc pour zéro. Les règles comptent désormais une série absente au début de la fenêtre pour sa valeur entière.

Vérifications, le 2026-10-27 :

- 18 cibles sur 18 actives, dont la bordure et Keycloak. 25 règles validées par `promtool check rules`.
- Correction des compteurs essayée par `promtool test rules` : alerte pour une série apparue avec 28 échecs, alerte pour une série ancienne en hausse de 25, aucune alerte hors fenêtre.
- Premier essai d'abus, avant la correction : 25 échecs de connexion avec un compte inexistant et 400 requêtes rapides sur la bordure, dont 192 refusées en 429. Aucune alerte : les deux séries venaient d'apparaître.
- Second essai après la correction, à 03:15 UTC : 25 échecs de connexion, 209 refus 429 sur 400 requêtes. `LoginFailures` et `EdgeRateLimited` publiées sur le sujet ntfy à 03:16:38.
- Keycloak et la bordure redémarrés une fois par le déploiement. Aucune autre interruption.

6B-b :

- api-gateway, audit-service, notification-service, remediation-service et workflow-worker passent aux images de `788e3c8`, tirées par empreinte. Port de gestion 9464 actif pour les quatre services HTTP. Le worker n'a pas de serveur HTTP et reste sans métriques.
- Chart `spring-service` : le service publie aussi le port de gestion, pour la sonde blackbox de la gateway, désormais sur 9464. La règle d'entrée générale des sondes ne vise plus la gateway.
- Scripts de vérification : la santé d'Actuator quitte le port routé. `target.py` lit `AUDITSPHERE_HEALTH_BASE` et fournit `api_healthy()`. `k3s-check.sh` ouvre aussi le port de gestion de la gateway. `smoke.py` et `test-temporal.py` l'utilisent. Compose garde la santé sur le port routé.
- audit-service : limite mémoire portée de 448 à 512 Mio, voir l'incident ci-dessous.

Vérifications de 6B-b, le 2026-10-27 :

- 22 cibles sur 22 actives. Métriques des JVM pour les quatre services, étiquetées par l'application. Retard des consommateurs Kafka de notification-service. Jauges des deux outbox à zéro. Sonde de la gateway réussie sur 9464.
- Port routé de la gateway : réponse 404 pour `/actuator/prometheus` et `/actuator/health`. Port de gestion : réponse 200 sans jeton.
- Depuis le pod `edge`, hors du namespace `monitoring` : connexion refusée vers le port 9464 de la gateway et d'audit-service. Témoin depuis le même pod vers `web` : réussi.
- Test des notifications avec pannes, par `k3s-check.sh` : livraison, rejeu sans doublon, DLT, reprise après arrêt de notification-service et de Kafka réussis.
- `KafkaDeadLetter` non déclenchée par ce test : le producteur de la DLT naît au premier envoi, et le test arrête notification-service avant la collecte suivante. Un seul message invalide injecté ensuite, sans arrêt : alerte publiée sur le sujet ntfy trois minutes plus tard.
- Test Temporal réussi par le nouveau chemin de santé : trois cycles dont un retrait, rejeu sans nouveau workflow.
- Tas des JVM entre 18 et 22 % de leur maximum. Aucun faux positif sur les règles applicatives pendant les essais.

Incidents :

- audit-service arrêté par manque de mémoire à 13:47 UTC, 15 minutes après le déploiement. L'ancienne image se stabilisait à 400 Mio pour une limite de 448. La nouvelle est montée à 423 Mio, puis a dépassé la limite. Le serveur du port de gestion et le registre Prometheus en sont la cause probable : la mémoire hors du tas atteignait 186 Mio pour 53 Mio de tas. Alerte `ContainerOOMKilled` reçue. Limite portée à 512 Mio, demande inchangée. Mesure sur les 16 minutes suivantes : stable vers 393 Mio, aucun redémarrage. Nœud à 85 % de sa mémoire allouable, aucune alerte active à 14:06 UTC.
- Kafka arrêté par manque de mémoire à 13:36 UTC pendant le test avec pannes, et déjà une fois avant l'installation de la supervision. Sa mémoire de travail restait vers 400 Mio pour une limite de 512. Alerte `ContainerOOMKilled` reçue, puis résolue. Corrigé le même jour, voir ci-dessous.

Limites :

- Les compteurs d'un producteur Kafka disparaissent avec son pod. Une DLT suivie d'un redémarrage avant la collecte suivante n'est pas signalée.
- Le worker Temporal n'expose aucune métrique. Ses pannes restent visibles par `WorkloadUnavailable`, `PodCrashLooping` et `OutboxStuck` côté audit-service.

#### Correctif : mémoire de Kafka

Statut : implémenté et vérifié le 2026-10-27. Constat issu des alertes du lot 6B.

Diagnostic, sur le pod en service avant le correctif :

- Mémoire anonyme du conteneur : 400 Mio sur 512, cache de fichiers négligeable. Le processus Java en occupe l'essentiel.
- Journal du ramasse-miettes : tas plein. Une collecte jeune ne libère rien, de 244 à 244 Mio, puis une collecte complète laisse 166 à 179 Mio vivants dans un tas de 247 Mio. 34 collectes complètes en deux heures, une toutes les 13 minutes, de 110 à 150 ms chacune. Métaspace : 17 Mio seulement.
- Cause : `log.cleaner.dedupe.buffer.size` vaut 128 Mio par défaut. Le nettoyeur des topics compactés, comme `__consumer_offsets`, garde cette table en permanence dans le tas, soit la moitié des 256 Mio. Le broker n'a que 74 partitions et quelques groupes.
- Le tas étant saturé, toute hausse de la mémoire hors du tas, par exemple lors des reconnexions après une panne, franchissait la limite du conteneur.

Correctif, [deploy/k3s/data/kafka.yaml](../../../deploy/k3s/data/kafka.yaml) : `KAFKA_LOG_CLEANER_DEDUPE_BUFFER_SIZE` à 16 Mio, soit environ 700 000 clés. Tas et limite du conteneur inchangés. Valeur active constatée dans la configuration du broker.

Vérifications, le 2026-10-27 :

- Test des notifications avec pannes réussi après le correctif, dont l'arrêt et la reprise de Kafka qui avaient provoqué l'arrêt par manque de mémoire.
- Nouveau pod, démarré à 15:39:35 UTC, mesuré 26 minutes plus tard : environ 60 Mio vivants après chaque collecte jeune, tas resté à 123 Mio réservés, aucune collecte complète, pauses jeunes vers 25 ms. Mémoire anonyme 236 Mio, mémoire de travail 240 Mio, contre 400 Mio avant. Aucun redémarrage, aucun événement OOM, aucune alerte active.

Suivi : la mémoire de travail montait encore lentement à la fin de la mesure, par remplissage de la vieille génération. Relevé par Prometheus : 246 Mio à 16:19 UTC, 282 Mio à 17:04, 287 Mio à 17:49, donc un palier vers 285 Mio. À confirmer sur une journée.

#### Lot 6C : traces et journaux à la demande

Statut : implémenté et vérifié le 2026-10-27. 6C-a, code et chart, fusionné par #18, images de `119bb1e` publiées. 6C-b, session d'observation et requête tracée, vérifié le même jour. Sortie de la phase 6 atteinte. Décision : [ADR-0030](../decisions/ADR-0030-traces-journaux-a-la-demande.md).

6C-a :

- `spring-boot-starter-opentelemetry` dans api-gateway, audit-service, notification-service et remediation-service. Export coupé par défaut, `management.tracing.export.enabled=false` : aucun service n'exporte en local ni dans le cluster tant que la session n'est pas ouverte.
- Kafka : observation du producteur dans audit-service, du producteur et du consommateur dans notification-service. Le contexte de trace suit les événements.
- Chart `spring-service`, option `tracing.enabled`, fausse par défaut : export activé, échantillonnage à 100 %, point d'accès OTLP de Tempo dans `monitoring`. Option fausse : rendu identique octet pour octet à celui de `main` pour les cinq services, vérifié par `diff`.
- Noms des propriétés lus dans les métadonnées de Spring Boot 4.0.9 : `management.tracing.export.enabled`, vrai par défaut, `management.tracing.sampling.probability`, 0,1 par défaut, `management.opentelemetry.tracing.export.otlp.endpoint`, sans valeur par défaut.

Vérifications de 6C-a, le 2026-10-27, dans l'image Maven de construction :

- `TracingExportTest`, gateway démarrée avec les réglages de l'option : une requête refusée en 401 produit un envoi OTLP vers un collecteur local, avec le nom du service, en sept secondes. Un dernier envoi échoue à l'arrêt du test, après la fermeture du collecteur : sans effet.
- `ManagementPortTest`, `OutboxMetricsTest` et `RequestLimitsTest` toujours réussis.
- Non vérifié localement : la propagation de la gateway vers un service et par Kafka. Elle sera constatée dans le cluster en 6C-b.

Incident de 6C-a : la première CI a échoué sur les 66 tests d'intégration d'audit-service. Le starter apporte aussi `micrometer-registry-otlp`, qui pousse les métriques en OTLP par défaut et exige protobuf 4, alors que le SDK Temporal impose protobuf 3.25.8. Le contexte ne démarrait plus, avec `NoClassDefFoundError: com/google/protobuf/RuntimeVersion$RuntimeDomain`. Ce registre est exclu des quatre services : les métriques restent sur Prometheus. `./mvnw verify` réussi ensuite en local avec Testcontainers : 124 tests, aucun échec.

6C-b, [scripts/k3s-observability.sh](../../../scripts/k3s-observability.sh) et [deploy/k3s/observability](../../../deploy/k3s/observability) :

- `up` installe Tempo, Loki, Alloy et Grafana dans `monitoring`, applique les règles d'entrée de la session, puis active les traces dans les quatre services Spring par `helm upgrade --reuse-values`. `down` coupe les traces, retire les règles et désinstalle la pile. `grafana` ouvre Grafana sur `http://127.0.0.1:13000` par `port-forward`.
- Tempo 3.1.0, chart `grafana-community/tempo` 3.1.0, processus unique, stockage local dans un volume éphémère de 1 Gio. Loki 3.6.11, chart `grafana/loki` 7.3.0, binaire unique, système de fichiers dans un volume éphémère de 2 Gio, sans passerelle, caches, canari ni MinIO. Alloy 1.20.1, chart `grafana/alloy` 1.13.1, en Deployment. Grafana 13.2.4, chart `grafana-community/grafana` 13.5.1. Versions publiées au moins deux semaines avant. Les charts `grafana` et `tempo` du dépôt de Grafana Labs sont dépréciés au profit de `grafana-community`.
- Alloy lit les journaux des pods de `auditsphere` et `data` par l'API Kubernetes, sans montage de l'hôte. Ses droits sont des Roles limités à ces deux namespaces, lecture des pods et de leurs journaux. Les règles par défaut du chart lisaient aussi les secrets à l'échelle du cluster.
- Grafana : sources Prometheus, Tempo et Loki provisionnées. Lien des journaux vers les traces par l'expression `[traceId-spanId]` que Spring Boot écrit dans chaque ligne d'une requête tracée. Accès anonyme en éditeur, formulaire de connexion désactivé : Grafana n'est joignable que par `port-forward`.
- Règles d'entrée de la session : pods de `auditsphere` vers l'OTLP de Tempo, port 4318. Alloy et Grafana vers Loki. Grafana vers Tempo et Prometheus.
- Valeurs des services : images de `119bb1e`, tirées par empreinte.

Vérifications de 6C-b, le 2026-10-27 :

- Session ouverte de 19:58 à 20:17 UTC. Les trois sources de Grafana répondent. Journaux repris depuis le démarrage de chaque conteneur, Keycloak depuis 03:04.
- Smoke de l'API réussi pendant la session. Tempo trouve 12 traces contenant la gateway et audit-service. Dans la trace `173f40a4`, la portée serveur `http get /missions` d'audit-service a pour parent la portée cliente `HTTP GET` de la gateway : requête tracée de la gateway au service, critère de sortie de la phase 6.
- Kafka : une trace part de l'éditeur d'outbox d'audit-service, et la portée consommateur de notification-service a pour parent sa portée productrice.
- 22 lignes de journaux portent un identifiant de trace, dans audit-service, notification-service et remediation-service. L'identifiant lu dans une ligne ouvre bien la trace dans Tempo.
- Mémoire pendant la session : pile 580 Mio, dont Grafana 314 Mio. audit-service à 429 Mio sur 512 avec les traces actives. Nœud à 96 % de sa mémoire allouable.
- Session fermée en 51 secondes : traces coupées, pile et règles retirées, aucun volume restant. Nœud revenu à 84 %. Smoke de l'API réussi ensuite.
- Neuf missions de test de la journée retirées par `cleanup-test-data.py`, sauvegarde privée préalable : 40 missions de démonstration, dont 7 en attente de revue.

Incidents :

- Premiers journaux absents de Loki : Loki n'interroge son ingesteur que sur les trois dernières heures par défaut, et les journaux repris par Alloy datent souvent de plus loin. `query_ingesters_within` mis à zéro.
- Grafana à 298 Mio pour une limite de 320 dès les premières minutes. Limite portée à 448 Mio.
- Le 2026-10-30, Grafana est arrêté pour dépassement mémoire à 448 Mio à l'ouverture de l'éditeur de tableaux de bord, ce qui coupe le `port-forward`. Il occupe 401 Mio dès son redémarrage. Limite portée à 640 Mio et appliquée par `helm upgrade` le même jour. Le nœud gardait alors 1,6 Gio disponibles. Le remplacement du pod efface les tableaux de bord enregistrés : les exporter en JSON avant toute mise à jour ou fermeture.

Tableau de vue d'ensemble, ajouté le 2026-10-30 :

- [vue-ensemble.json](../../../deploy/k3s/observability/dashboards/vue-ensemble.json), dossier `AuditSphere` de Grafana, uid `auditsphere-vue-ensemble`. `k3s-observability.sh up` le passe au chart par `--set-file` et un fournisseur de fichiers le charge. Il revient donc à chaque session. Une modification faite dans Grafana se perd à la fermeture : l'exporter en JSON et remplacer le fichier du dépôt.
- Bandeau d'état en tête : alertes actives hors Watchdog, cibles en panne, sondes en échec, mémoire libre du nœud, disque libre, redémarrages sur 24 heures, erreurs 5xx servies par Traefik sur une heure, délai avant l'expiration du premier certificat. Seuils repris des alertes Prometheus.
- Quatre sections : trafic utilisateur vu par Traefik et connexions Keycloak, services avec débit, temps moyen, erreurs 5xx, refus de la gateway et pool PostgreSQL, traitements asynchrones avec retard Kafka et outbox, ressources avec mémoire rapportée à la limite, processeur, tas Java et ramasse-miettes.
- Variable `service` sur le label `app_kubernetes_io_name`. Les sondes et la collecte des métriques sont exclues des débits. Fenêtres de 5 minutes, car Prometheus collecte toutes les 60 secondes.
- Limites : les métriques HTTP des services n'ont pas d'histogramme, d'où un temps moyen au lieu d'un percentile. Le percentile 95 vient de Traefik. Aucun envoi vers une DLT n'est encore mesuré par `kafka_producer_topic_record_send_total`, le tableau ne l'affiche pas.

Vérifications du 2026-10-30 : les 26 requêtes du tableau sont acceptées par Prometheus, les vides correspondent à des événements absents, connexions échouées et refus de la gateway. Tableau importé par l'API de Grafana dans la session en cours, puis affiché avec ses données. Le rendu du chart par `helm template` contient la ConfigMap, le fournisseur et le montage. Le chargement par `up` n'est pas encore exécuté : il sera constaté à la prochaine session.
- Ouverture trop lente : les services redémarraient l'un après l'autre, 19 minutes au total. Ils redémarrent désormais en parallèle. Fermeture mesurée ensuite : 51 secondes. Ouverture en parallèle non encore mesurée.
- Un smoke lancé juste après la fermeture est resté bloqué plus de dix minutes, sans cause établie. Relancé seul, il a réussi en 42 secondes.

Limites :

- Pendant une session, le nœud monte à 96 % de sa mémoire allouable. Une session doit rester courte et ne pas coïncider avec une charge forte.
- Ouvrir ou fermer une session redémarre les quatre services Spring.
- Alloy lit seulement le fichier de journal courant de chaque conteneur. Les journaux déjà tournés par le nœud ne sont pas repris.

#### Veille externe de la supervision

Statut : implémenté et vérifié le 2026-10-30. Décision : [ADR-0036](../decisions/ADR-0036-veille-externe-healthchecks.md), healthchecks.io choisi par l'utilisateur le même jour.

Origine : le 2026-10-30, une coupure de courant a éteint le serveur environ 14 heures. Prometheus et Alertmanager tournent dans le cluster : `NodeDown` et `TargetDown` ne pouvaient pas partir. Aucune alerte avant le retour du courant.

Fonctionnement :

- Règle `Watchdog`, expression `vector(1)`, gravité `none`, groupe `supervision` de [monitoring.yaml](../../../deploy/k3s/monitoring/monitoring.yaml). Elle est toujours active.
- Route dédiée, placée avant la route critique : `group_wait` nul, `group_interval` d'une minute, `repeat_interval` de cinq minutes. `Watchdog` part seulement vers le récepteur `heartbeat`, jamais vers ntfy.
- Récepteur `heartbeat` : webhook POST vers l'adresse de ping d'un check healthchecks.io, sans message de résolution. Alertmanager la lit par `url_file` dans le secret `alertmanager-heartbeat`, monté sur `/etc/alertmanager-heartbeat`.
- Check healthchecks.io, offre gratuite : période de 5 minutes, grâce de 10 minutes. Sans ping pendant 15 minutes, healthchecks.io publie un message « DOWN » en priorité maximale sur le sujet ntfy des alertes, et un courriel. Au retour du ping, il publie un message « UP ».
- [scripts/k3s-monitoring.sh](../../../scripts/k3s-monitoring.sh) `apply` lit `HEALTHCHECKS_PING_URL` dans `.local/healthchecks.env` et crée le secret. Sans adresse valide, il s'arrête avant toute modification, car Alertmanager refuserait sa configuration.
- Le check, son intégration ntfy et le compte sont créés à la main par l'utilisateur sur healthchecks.io. Aucun réglage du check n'est dans Git.

Vérifications, le 2026-10-30 :

- Avant l'application, dans les pods du cluster : configuration d'Alertmanager validée par `amtool check-config`, 27 règles validées par `promtool check rules`. `amtool config routes test` envoie `Watchdog` vers `heartbeat`, et `NodeDown` critique toujours vers `ntfy-urgent`.
- Application à 15:01 UTC. Alertmanager redémarré avec le secret monté, règle chargée par Prometheus à 15:01:40.
- Premier ping à 15:02:16, second à 15:07:28, aucun échec d'envoi. `Watchdog` était la seule alerte active, les deux envois sont donc les siens. Check passé à l'état « up » sur healthchecks.io, constaté par l'utilisateur.
- Battement suspendu par un silence Alertmanager sur `Watchdog` à 15:07:37. Message « AuditSphere k3s watchdog is DOWN » publié sur le sujet ntfy à 15:22:22, en priorité 5, 15 minutes après le dernier ping. Relu sur le sujet.
- Silence levé à 15:22:48. Ping suivant reçu, message « AuditSphere k3s watchdog is UP » publié à 15:23:27 en priorité normale. Relu sur le sujet.
- Messages DOWN et UP reçus sur le téléphone, confirmé par l'utilisateur. Le DOWN n'apparaissait qu'après une actualisation du sujet dans l'application iOS.
- Non vérifié : vrai arrêt du nœud.

Limites :

- Délai de détection : 15 minutes au plus après le dernier ping, soit environ 20 minutes après l'arrêt réel.
- Une coupure de l'accès Internet du serveur, ou une panne de healthchecks.io, produit la même alerte qu'un arrêt du nœud.
- Toute personne qui connaît l'adresse de ping peut simuler un signal de vie. healthchecks.io connaît le nom du sujet ntfy.
- Le signal prouve que la chaîne d'alerte tourne, pas que chaque règle est juste ni que l'application répond.
- Un silence posé sur toutes les alertes coupe aussi le battement et déclenche l'alerte de veille 15 minutes plus tard.

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
| kafka | 9092 | audit-service, notification-service, remediation-service depuis HOST-013 |
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

#### Lot D : Kafka en SASL_SSL avec ACL

Statut : implémenté et vérifié le 2026-10-26.

- [deploy/k3s/data/kafka.yaml](../../../deploy/k3s/data/kafka.yaml) : seule l'écoute `SASL_SSL` sur 9092 est joignable par le réseau, avec SCRAM-SHA-512 et un certificat de l'autorité interne. Magasin de clés PKCS12 produit par cert-manager, mot de passe dans `kafka-keystore-password`. L'image exige ces fichiers dans `/etc/kafka/secrets` dès qu'une écoute annoncée utilise SSL.
- Le contrôleur, 9093, et une écoute locale des outils, 9094, n'écoutent que sur `127.0.0.1`. Leur principal `ANONYMOUS` est le seul super-utilisateur : seuls les processus du pod l'obtiennent, par `kubectl exec`. La sonde de vie passe de 9093 à 9092.
- `StandardAuthorizer`, `allow.everyone.if.no.acl.found` à faux, création automatique des topics toujours désactivée.
- [scripts/k3s-kafka-security.sh](../../../scripts/k3s-kafka-security.sh) remplace le Job `kafka-topics` : topics, comptes SCRAM, ACL et secrets `kafka-audit-service` et `kafka-notification-service`, de façon idempotente. Les mots de passe passent par l'entrée standard et ne sont jamais affichés.
- ACL : audit-service écrit et décrit ses quatre topics, sans rien lire. notification-service lit les affectations et les demandes de revue, lit son groupe `notification-service-v1` et écrit leurs deux DLT. Les topics de constats n'ont aucun lecteur.
- Clients Spring : `SPRING_KAFKA_PROPERTIES_*` pour le protocole, le mécanisme et le magasin de confiance PEM, ligne JAAS lue dans le secret. La liaison de ces variables vers les clés `security.protocol` et voisines a été vérifiée avec le `Binder` de Spring Boot 4.0.9 avant la bascule.
- Scripts de vérification : `target.KAFKA_BOOTSTRAP` vaut `localhost:9094` dans k3s et reste `localhost:9092` pour Compose.

Bascule : jeu de sauvegarde `20261026T185715Z` tiré sur le poste, outbox vides, broker redémarré en SASL_SSL, comptes et ACL posés, puis audit-service et notification-service redéployés.

Vérifications, le 2026-10-26 :

- Broker démarré avec l'autorisation active. Aucune erreur Kafka dans les journaux des deux services. Groupe `notification-service-v1` reconnecté, retard nul sur les six partitions.
- Depuis un pod de test admis par la règle réseau mais jamais prêt, donc hors du service : connexion en clair rejetée à la négociation TLS, mauvais mot de passe refusé par SCRAM, connexion par adresse IP refusée sur le nom du certificat.
- Avec le compte de notification-service : seuls ses topics sont visibles, écriture sur `audit.mission.assigned.v1` et lecture de `audit.mission.findings-issued.v1` refusées par `TopicAuthorizationException`.
- Le client en clair échoue par manque de mémoire : il lit la réponse TLS comme une taille de message. Le broker journalise `Failed authentication ... (SSL handshake failed)`.
- Pannes Kafka réussies sous SASL_SSL : rejeu sans doublon, message invalide mis en DLT puis message suivant livré, reprise après arrêt de notification-service et de Kafka. Smoke de l'API réussi.
- Quatre missions de test nettoyées, 40 missions de démonstration intactes, dont 7 en attente de revue. Mémoire du nœud : 78 %, Kafka à 376 Mio sur 512.

Incident : au premier passage du script, `kafka-configs` a refusé la clé `SCRAM-SHA-512` lue depuis un fichier, et son message d'erreur a recopié le mot de passe généré pour audit-service dans la sortie. Ce compte n'avait pas encore été créé. Le secret a été supprimé puis régénéré. Le script passe désormais le mot de passe par l'entrée standard et masque les erreurs de l'outil.

Limites :

- Le broker ne relit pas son magasin de clés quand cert-manager le renouvelle. Un redémarrage ou une reconfiguration dynamique est nécessaire, à traiter au lot E.
- Une ACL retirée du script n'est pas retirée du cluster. Le script ne fait qu'ajouter.
- Kafka reste sans réplication : un broker unique, facteur de réplication 1.

#### Déploiement de `20dd6db` et topic des affectations d'actions

Statut : déployé et vérifié le 2026-10-30, sur demande de l'utilisateur de déployer les images de `main`.

- Les cinq images Spring et celle du BFF passent de `3066fbc` et `f01f9d0` à `20dd6db`, tirées par empreinte. Ce lot apporte la liste des missions sans périmètre, le worker qui attend Temporal (HOST-012) et les lots 3 à 6 de F-006 dans remediation-service et notification-service.
- Sauvegarde préalable par `k3s-backup.sh run`, car remediation-service applique `V2__scope_without_validator.sql` et notification-service `V4__action_notifications.sql`.
- `k3s-deploy-services.sh` réussi, migrations terminées, pods prêts.

Incident : le consommateur de notification-service s'est arrêté au démarrage avec `TopicAuthorizationException` sur `audit.action.assigned.v1`. Ce topic n'existait pas dans le cluster et le compte n'avait aucune ACL dessus. Spring Kafka arrête le conteneur sur une erreur d'autorisation, le groupe `notification-service-v1` restait donc sans membre et les affectations de missions n'étaient plus notifiées. `cleanup-test-data.py` l'a révélé en abandonnant, sans suppression, faute de vidage du groupe.

Correctif : `k3s-kafka-security.sh` crée `audit.action.assigned.v1` et sa DLT, et donne à notification-service la lecture du topic et l'écriture de sa DLT. Script appliqué, puis notification-service redémarré.

Vérifications du 2026-10-30 :

- Groupe `notification-service-v1` actif, retard nul sur les deux topics de missions, aucune erreur dans les journaux après le redémarrage.
- `k3s-check.sh smoke.py` et `smoke-bff.py` réussis par le nom public. Mission du smoke retirée par `cleanup-test-data.py`, sauvegarde privée préalable. 41 missions servies au responsable.
- Aucune alerte active hors `Watchdog`. Mémoire relevée : audit-service 417 Mio, remediation-service 229 Mio, notification-service 237 Mio, aucun redémarrage.

Limites :

- remediation-service n'a ni compte Kafka ni bucket Garage dans le cluster. Son consommateur cherche `localhost:9092` en boucle et journalise un avertissement par seconde environ. La synchronisation des constats, les justificatifs et la publication des affectations d'actions restent inactives dans k3s. Le service démarre et répond, comme le confirme le smoke.
- HOST-012 : le worker démarre sans redémarrage, mais le démarrage avec Temporal indisponible n'a pas été provoqué dans le cluster.

#### Accès de remediation-service à Kafka, Keycloak et Garage (HOST-013)

Statut : déployé et vérifié le 2026-10-30. Lève la limite ci-dessus : la synchronisation des constats, l'annuaire des audités, la publication des affectations d'actions et les justificatifs de F-006 fonctionnent dans k3s.

Kafka :

- [scripts/k3s-kafka-security.sh](../../../scripts/k3s-kafka-security.sh) crée le compte SCRAM `remediation-service` et son secret `kafka-remediation-service`, avec la même méthode que les deux autres comptes : mot de passe par l'entrée standard, erreurs de l'outil masquées.
- ACL de remediation-service : lecture de `audit.mission.findings-issued.v1` et `audit.mission.findings-withdrawn.v1`, écriture de leurs DLT, écriture de `audit.action.assigned.v1`, lecture du groupe `remediation-service-v1`. Aucune autre permission. Les topics sont ceux de `FindingsIssueConsumer` et de `AssignmentPublisher`.
- [deploy/k3s/data/network-policies.yaml](../../../deploy/k3s/data/network-policies.yaml) admet remediation-service sur le port 9092 de Kafka. Sans cette règle, le compte et les ACL ne suffisaient pas.

Keycloak :

- Le service a besoin de deux clients, absents jusque-là de ses valeurs : `remediation-service` pour lire l'instantané des constats émis, et `remediation-directory` pour l'annuaire des audités. Le second n'existait pas dans le royaume du cluster.
- [scripts/k3s-setup-keycloak.py](../../../scripts/k3s-setup-keycloak.py) crée `remediation-directory` avec `view-users` et `view-realm` seulement, comme `setup-web.py` en local. Son secret `REMEDIATION_DIRECTORY_CLIENT_SECRET` est généré une fois dans `app-secrets`, sans affichage, puis réutilisé.

Garage :

- [scripts/k3s-remediation-storage.sh](../../../scripts/k3s-remediation-storage.sh) crée par SSH le bucket `auditsphere-remediation-k3s` et la clé `auditsphere-remediation-k3s`, sur le modèle de la clé de sauvegarde. La clé n'a que la lecture et l'écriture sur ce bucket. Le bucket des preuves de contrôle et sa clé ne changent pas. Le secret `auditsphere/remediation-s3` porte la clé. Le script ne fait rien si le secret existe déjà.
- Le bucket n'est pas copié par `deploy/garage/backup.sh` ni par la copie restic, qui ne visent que les preuves de contrôle. Acceptable pour des justificatifs de démonstration effacés chaque nuit, à revoir avant toute donnée réelle.

Service et réinitialisation :

- [infra/helm/values/remediation-service.yaml](../../../infra/helm/values/remediation-service.yaml) : `KAFKA_BOOTSTRAP_SERVERS` et SASL_SSL comme notification-service, `OUTBOX_ENABLED=true`, `KEYCLOAK_URL`, les deux secrets clients et `REMEDIATION_S3_*` comme les preuves d'audit-service. `k3s-deploy-services.sh` passe `REMEDIATION_S3_ENDPOINT` depuis `GARAGE_ENDPOINT`.
- demo-reset efface déjà chaque nuit les lignes de justificatifs. Sans changement, leurs objets seraient restés dans Garage. L'étape `database` relève désormais `object_key` de `action_attachments` avant l'effacement, et l'étape `seed` retire ces objets comme les preuves. `scripts/evidence_storage.py` prend un préfixe, `EVIDENCE` par défaut ou `REMEDIATION`, pour signer les requêtes du bon bucket.
- Mémoire : 246 Mio au démarrage avec Kafka, 284 Mio sur 320 après un dépôt de justificatif. Limite portée à 384 Mio, comme notification-service. Le nœud reste en surengagement de limites, environ 4,1 Gio consommés par les pods.

Vérifications du 2026-10-30 :

- Script Kafka appliqué : compte créé, trois ACL de lecture, trois d'écriture, cinq de description pour remediation-service.
- Au premier démarrage, le groupe `remediation-service-v1` a lu les 106 événements restés dans les topics de constats, retard nul, aucune DLT alimentée, aucun avertissement. Résultat comparé à la base audit : 13 missions approuvées ou clôturées avec un cycle émis et 24 constats validés, pour 13 périmètres actifs et 24 suivis dans remediation. Les autres événements visaient des missions effacées par les réinitialisations, refusées en 404 ou 409 par audit-service puis acquittées, comme prévu.
- [scripts/test-remediation.py](../../../scripts/test-remediation.py), nouveau, lancé par `k3s-check.sh` : mission synthétique approuvée avec un constat validé, suivi À planifier créé par Kafka, recherche de l'audité dans l'annuaire, action affectée à `auditee`, notification reçue par lui seul, justificatif PDF déposé par l'audité, relu identique par l'auditeur, puis retiré. Bucket vide ensuite, outbox publiée, DLT vides.
- Chemin de nettoyage de demo-reset essayé depuis le poste, sans lancer de réinitialisation : écriture, lecture puis suppression d'une clé de test par `evidence_storage.py` avec le préfixe `REMEDIATION`, puis 404.
- `k3s-deploy-services.sh` rejoué : idempotent, aucun redémarrage hors demo-reset mis à jour. `k3s-check.sh smoke.py` réussi. Mission du smoke retirée par `cleanup-test-data.py`. Groupe `remediation-service-v1` stable, 247 Mio au repos sous 384, aucun redémarrage, aucune alerte hors `Watchdog`. Tests Python des scripts : 25 réussis dont deux ignorés par conception.

Incident : le premier essai de `test-remediation.py` a laissé une mission en brouillon, faute de dates prévues. Sa suppression par `DELETE /missions/{id}` a répondu 500 : audit-service supprime les risques avant les liens entre contrôles et risques, et la contrainte `mission_control_risks_mission_id_risk_id_fkey` refuse. Le brouillon a été retiré par `cleanup-test-data.py`. Le défaut est corrigé dans audit-service par TECH-010 le 2026-10-31, puis déployé et vérifié le 2026-11-01, voir le déploiement de `6eac4e9`.

Limites :

- Non vérifié : une vraie réinitialisation nocturne avec des justificatifs en base. Celle du 2026-10-31 à 5 h a réussi, mais l'étape `database` a relevé 0 justificatif à retirer : le retrait des objets n'a pas encore servi.
- La mission approuvée par `test-remediation.py` reste visible jusqu'à la réinitialisation nocturne. Depuis le lot 7 de F-006, `cleanup-test-data.py` nettoie aussi la base remediation, mais s'arrête sans rien supprimer dans k3s si des justificatifs existent.
- Un objet écrit dans Garage puis refusé par la transaction, si le processus s'arrête entre les deux, reste orphelin, comme noté au lot 5 de F-006. La réinitialisation nocturne ne voit que les objets listés en base.
- Les ACL ne sont jamais retirées par le script, comme pour les autres comptes.

#### Déploiement de `6eac4e9` : correctif TECH-010

Statut : déployé et vérifié le 2026-11-01, sur demande de l'utilisateur.

- Seul audit-service a changé depuis `20dd6db` : suppression d'un brouillon dans l'ordre des dépendances du programme (TECH-010). L'image d'audit-service et celle de workflow-worker, qui la partage, passent à `6eac4e9`, tirées par empreinte. Les autres services restent sur `20dd6db`, leur code est identique.
- Aucune migration dans ce lot. Pas de sauvegarde préalable : un retour arrière se limite à rétablir l'empreinte précédente.
- `k3s-deploy-services.sh` réussi, pods d'audit-service et de workflow-worker prêts sans redémarrage. Les Jobs de migration des autres services se sont relancés sans rien appliquer.

Vérifications du 2026-11-01 :

- Brouillon synthétique créé par la gateway avec un risque et un contrôle lié, puis `DELETE /missions/{id}` : 204, puis `GET` en 404. Avant le correctif, le même cas répondait 500.
- `k3s-check.sh smoke.py` réussi. Mission du smoke retirée par `cleanup-test-data.py`, sauvegarde privée préalable.
- Aucune alerte active hors `Watchdog`, aucune erreur dans les journaux d'audit-service. Mémoire relevée : audit-service 441 Mio, workflow-worker 128 Mio.

Limite : le script de cette vérification est resté hors du dépôt. Le test Java `draftDeletionRemovesProgramInDependencyOrder` couvre le même cas en CI.

#### Déploiement de `995eff4` : interface des actions correctives

Statut : déployé et vérifié le 2026-11-01 sur le profil `demo`, sur demande de l'utilisateur.

- Seule l'image web passe de `20dd6db` à `995eff4`, tirée par empreinte. Elle apporte le lot 7 de F-006 : route `/api/remediation` du BFF, onglet Actions correctives, page Mes actions et fiche d'action. Aucun service Java ne change, aucune migration.
- `k3s-deploy-services.sh` réussi, pod web prêt. Les Jobs de migration se sont relancés sans rien appliquer.

Vérifications du 2026-11-01 :

- `k3s-check.sh test-remediation.py` réussi : mission approuvée, suivi créé par Kafka, action notifiée à l'audité, justificatif déposé, relu puis retiré.
- Navigateur, par le nom public et l'entrée en un clic : l'audité arrive sur `/actions`, voit l'action du test et ouvre sa fiche avec la copie du constat, l'avancement, les justificatifs et l'historique. Le responsable voit l'onglet Actions correctives de la mission avec le suivi en plan d'action. Sessions de démonstration fermées ensuite.
- `k3s-check.sh smoke.py` réussi. Mission du smoke retirée par `cleanup-test-data.py`, désormais étendu à la base remediation : sauvegarde privée, services arrêtés puis revenus prêts.
- Aucune alerte active hors `Watchdog`. Pod web à 85 Mio.

Limite : la mission de `test-remediation.py` reste visible jusqu'à la réinitialisation nocturne, comme pour HOST-013. Les écritures de l'interface, avancement, dépôt, réalisation, vérification et clôture, sont couvertes par le test Playwright local et par `test-remediation.py` côté API, pas rejouées à la main dans le cluster.

#### Déploiement de `6c7cb8d` : limite de la gateway (TECH-011)

Statut : déployé et vérifié le 2026-11-01 sur le profil `demo`, sur demande de l'utilisateur.

- Images de la gateway et du web passées à `6c7cb8d`, tirées par empreinte. La gateway ne change que par TECH-011 : plafonds global et par identité réglables, valeurs par défaut inchangées. Le web répond 429 au lieu de 503 quand la gateway limite la vérification de session.
- Les valeurs Helm de la gateway ne règlent que `GATEWAY_RATE_IP_LIMIT`. Le plafond par identité reste donc 240 dans le cluster, seul Compose le relève.
- `k3s-deploy-services.sh` réussi, pods de la gateway et du web prêts sans redémarrage.

Vérifications du 2026-11-01 :

- `k3s-check.sh smoke.py` et `smoke-bff.py` réussis, le second par le nom public : connexion OIDC, session, 42 missions servies, déconnexion. Mission du smoke retirée par `cleanup-test-data.py`.
- Aucune alerte active hors `Watchdog`, aucune erreur dans les journaux du web.
- Non vérifié dans le cluster : la réponse 429 elle-même. La provoquer demanderait plus de 240 requêtes par minute sur un compte de la démo publique. Elle est couverte par le test du BFF et par les tests de la gateway.

#### Déploiement de `1cd3d78` : cohérence des écrans et pagination (TECH-012)

Statut : déployé et vérifié le 2026-11-02 sur le profil `demo`, sur demande de l'utilisateur.

- Seule l'image web passe de `6c7cb8d` à `1cd3d78`, tirée par empreinte. Elle apporte la base commune des formulaires et la pagination numérotée. Aucun service Java ne change, aucune migration.
- `k3s-deploy-services.sh` réussi, pod web prêt sans redémarrage. Les Jobs de migration se sont relancés sans rien appliquer.

Vérifications du 2026-11-02 :

- `k3s-check.sh smoke.py` et `smoke-bff.py` réussis, le second par le nom public : connexion OIDC, session, 41 missions servies, déconnexion.
- Chromium, par le nom public et l'entrée en un clic du responsable : champ, listes déroulantes et bouton des filtres à 40 pixels, listes déroulantes sans apparence native, passage à la page 2 avec `page=1` dans l'URL. Session fermée ensuite.
- Aucune alerte active hors `Watchdog`, aucune erreur dans les 200 dernières lignes du journal du web. Pod web à 84 Mio.

Limites : la mission du smoke reste visible jusqu'à la réinitialisation nocturne, car `cleanup-test-data.py` arrête brièvement les services. Non vérifié dans le cluster : rendu dans Safari, écrans de la fiche action et de `/actions`, couverts par les captures et les tests locaux.

#### Lot E1 : Garage en HTTPS et rotation des certificats

Statut : implémenté et vérifié le 2026-10-26, sauf mention contraire.

Le lot E est découpé. E1 ne demande que de la configuration. E2, rechargement à chaud du certificat client Temporal par le code Java, demande une nouvelle image. Le durcissement de Keycloak est reporté à la phase 10 par l'utilisateur, le 2026-10-26 : désactivation du client `auditsphere-demo` et de son accès direct, OTP du super-administrateur, console refusée sur le nom public. Il reste une condition d'ouverture.

Garage en HTTPS, selon [ADR-0028](../decisions/ADR-0028-garage-https-caddy-tailscale.md) :

- [deploy/garage/compose.yaml](../../../deploy/garage/compose.yaml) : Garage n'est plus publié. Le service `tls`, Caddy 2.11.6 épinglé par empreinte, publie le port 3900 sur l'adresse Tailscale de l'hôte et transmet à Garage par le réseau du projet. [deploy/garage/Caddyfile](../../../deploy/garage/Caddyfile) : API d'administration fermée, aucune redirection HTTP.
- Certificat Let's Encrypt du nom de l'hôte dans le tailnet, obtenu par Caddy auprès de Tailscale. `GARAGE_BIND_IP` et `GARAGE_TLS_NAME` sont dans le `.env` privé de l'hôte.
- `deploy/garage/backup.sh` archive aussi le Caddyfile.
- `GARAGE_ENDPOINT` de `.local/k3s.env` et la clé `endpoint` du secret `backup/garage-backup` passent en HTTPS. audit-service est redéployé avec ce point d'accès, sans autre changement.
- Essai préalable de `tailscale serve`, choix initial : HTTPS fonctionnel depuis le poste, `Connection refused` depuis les pods. Le trafic d'un pod vers l'adresse Tailscale de son hôte ne passe pas par `tailscaled`. `tailscale serve` est désactivé sur ce port.

Rechargement des certificats serveur :

- Kafka : conteneur `reload` dans le pod du broker. Toutes les 60 secondes, il compare l'empreinte du magasin PKCS12. Si elle change, il redéclare `listener.name.sasl_ssl.ssl.keystore.location` par reconfiguration dynamique sur l'écoute locale, ce qui force Kafka à relire le fichier. Mémoire du conteneur limitée à 192 Mio, tas de l'outil à 64 Mio.
- PostgreSQL : conteneur `reload` du lot C1, inchangé.
- Temporal : relecture native toutes les 10 minutes, réglée au lot C2.

Vérifications, le 2026-10-26 :

- Depuis le poste : réponse 403 de Garage en HTTPS, chaîne Let's Encrypt `YE1` valide jusqu'en janvier 2027. HTTP clair sur 3900 refusé par une réponse 400. La racine interne seule ne valide pas le certificat, erreur 20 d'OpenSSL.
- Depuis un pod du namespace `backup` : réponse 403 en HTTPS avec les seules autorités du système, réponse 400 en clair.
- Preuves témoins relues par la gateway après la bascule : deux sur deux, SHA-256 identique à la base.
- Jeu de sauvegarde `20261026T224712Z` produit avec la copie Garage en HTTPS, tiré sur le poste, puis restauration isolée réussie : sept bases, 40 missions, deux preuves sur deux.
- Certificat client Temporal expiré, CN `temporal-expiry-test` d'une durée d'une heure : connexion coupée par le serveur juste après la négociation. Le certificat d'administration obtient `SERVING` au même moment. Le secret de test est supprimé.
- Renouvellement forcé à 22:51 UTC des certificats de Kafka, PostgreSQL et Temporal. Certificat servi lu par `openssl s_client` par un `port-forward` : Kafka sert le nouveau numéro de série une minute plus tard, PostgreSQL deux minutes plus tard, sans redémarrage. Temporal sert le sien trois minutes plus tard, par sa relecture périodique. Aucun conteneur redémarré pendant la rotation : les derniers redémarrages datent du redémarrage de l'hôte.
- Test des notifications réussi pendant la rotation : livraison, rejeu sans doublon, reprise. Après la rotation : test Temporal réussi, trois cycles dont un retrait et un rejeu, smoke de l'API réussi, aucune erreur TLS dans les journaux d'audit-service et du worker. Missions de test nettoyées : 40 missions de démonstration, dont 7 en attente de revue.

Incidents :

- Après un redémarrage de l'hôte par l'utilisateur, le conteneur Caddy a démarré avant l'adresse Tailscale. Docker l'a laissé en marche sans réseau ni port publié, sans erreur visible. Recréé à la main, il fonctionne. Correctif choisi par l'utilisateur et appliqué par lui avec sudo, le 2026-10-27 : `net.ipv4.ip_nonlocal_bind = 1` dans un fichier de `/etc/sysctl.d`. Docker peut alors publier le port avant que Tailscale attribue l'adresse. Valeur active et fichier présents, vérifiés par SSH. Non vérifié : retour seul de Caddy après un redémarrage de l'hôte.
- L'exercice de restauration a échoué une fois sur `createdb` : la sonde de disponibilité du PostgreSQL de test passait par le socket, où répond aussi le serveur temporaire de l'initialisation. Elle passe désormais par TCP, et l'exercice suivant a réussi.

Limites :

- ADR-0027 prévoyait une copie à froid complète de Garage par `backup.sh` avant ce passage. Elle n'a pas été faite. Les volumes de Garage n'ont pas été modifiés, et la copie à chaud des objets est dans le dépôt restic du poste.
- Le conteneur Caddy accède au socket de `tailscaled`, donc à la configuration Tailscale de l'hôte.
- `ip_nonlocal_bind` vaut pour tout l'hôte : n'importe quel processus peut écouter sur une adresse que la machine n'a pas encore. Aucun autre service de l'hôte n'en dépend à ce jour.
- Le client Java de Temporal garde son certificat jusqu'au redémarrage du pod, à traiter au lot E2. Levée par le lot E2, vérifié le 2026-10-27.
- Revue des critères de sortie d'ADR-0020 à faire. Le critère de supervision dépend de la phase 6.

#### Lot E2 : rechargement du certificat client Temporal

Statut : implémenté et vérifié le 2026-10-27. E2a, client Java, fusionné par #12, images de `0e56ca9` publiées. E2b, déploiement et rotation réelle dans le cluster, vérifié le même jour.

Comme au lot C2, le lot est découpé : E2a change le code et produit de nouvelles images, E2b les déploie et vérifie une rotation réelle.

E2a :

- `ReloadingKeyManager` remplace le chargement unique du certificat et de la clé dans `TemporalConnection`. À chaque négociation TLS, il relit les deux fichiers PEM et compare leur empreinte SHA-256. Si elle change, il charge la nouvelle paire et journalise le numéro de série et la date d'expiration, sans jamais journaliser la clé.
- Le nom d'alias dérive de l'empreinte. La paire précédente reste disponible : une négociation qui a choisi l'ancien alias juste avant la bascule obtient toujours un certificat et une clé assortis.
- Fichiers renouvelés illisibles ou invalides : la paire déjà chargée reste en service et un avertissement est journalisé. Au démarrage, des fichiers invalides empêchent toujours le démarrage.
- Kubernetes remplace les fichiers d'un secret monté par un échange atomique de lien symbolique : le certificat et la clé changent ensemble.
- Une connexion gRPC déjà établie garde le certificat de sa négociation. Le nouveau certificat sert à la prochaine connexion. Le lot E1 a montré que le serveur vérifie le certificat client à la négociation.
- L'autorité de confiance reste chargée une seule fois. Une rotation de la racine hors ligne, selon ADR-0026, demande toujours un redémarrage des pods.

Vérifications d'E2a, le 2026-10-27, dans l'image Maven de construction :

- `ReloadingKeyManagerTest` : quatre cas réussis. Bascule vers les fichiers renouvelés avec conservation de la paire précédente, maintien de la paire courante si la clé devient invalide ou si le certificat disparaît, refus au démarrage d'une clé invalide. Deux négociations TLS 1.3 réelles avec authentification cliente obligatoire, fichiers renouvelés entre les deux : le serveur reçoit l'ancien certificat puis le nouveau.
- `TemporalConnectionTest` : cinq cas réussis, inchangés.
- Ces tests passent par le fournisseur TLS de la JVM. Dans le pod, gRPC utilise le fournisseur OpenSSL de Netty. Ce chemin est vérifié en E2b.

E2b :

- audit-service et workflow-worker déployés avec l'image `0e56ca9`, tirée par empreinte. Valeurs Helm mises à jour.
- Avant le déploiement, certificats clients d'audit-service et du worker ramenés à une heure de validité, renouvelés 40 minutes avant l'expiration, donc toutes les 20 minutes. Réémission forcée par la condition `Issuing` du statut, comme le fait `cmctl renew`. Les pods démarrent ainsi avec un certificat A expirant à 01:32:06 UTC.
- Durées de 90 jours rétablies ensuite depuis [temporal-tls.yaml](../../../deploy/k3s/data/temporal-tls.yaml) et réémission forcée : nouveaux certificats valables jusqu'en janvier 2027.

Vérifications d'E2b, le 2026-10-27 :

- Test Temporal réussi à 00:33 avec le certificat A : le fournisseur OpenSSL de Netty utilise le nouveau gestionnaire de clés dans le pod.
- workflow-worker, pod démarré à 00:34:13 avec le certificat A, jamais redémarré pendant l'essai. À 01:32:16, dix secondes après l'expiration de A, il journalise le rechargement vers le certificat émis à 01:32, valable jusqu'à 02:32. Les certificats intermédiaires n'ont jamais été chargés : aucune négociation entre-temps.
- Temporal redémarré à 01:34, avec A expiré. Le même pod de worker interroge de nouveau la file `audit-review-v1` du nouveau serveur, relevé par `temporal task-queue describe`.
- audit-service, pod démarré à 01:37:18 avec le certificat de 01:32. Après le renouvellement de 01:52 et un redémarrage de Temporal, il journalise le rechargement à 01:55:25 lors de son premier appel. Test Temporal réussi ensuite : trois cycles dont un retrait, rejeu sans nouveau workflow.
- Aucune erreur TLS dans les journaux. Tous les pods des namespaces `auditsphere` et `data` en service après le retour aux 90 jours.

Incidents et constats :

- Un premier passage du test Temporal après l'expiration a échoué dans `cleanup-test-data.py`, appelé en fin de test. Relancé seul, le nettoyage a réussi. La cause n'a pas été établie, la sortie complète n'ayant pas été conservée.
- Le nettoyage arrête puis redémarre audit-service. Ce redémarrage a remplacé le pod d'audit-service pendant l'essai : sa preuve de rechargement vient donc du second cycle, après le renouvellement de 01:52.
- À chaque redémarrage, Temporal a échoué une fois faute de connexion à PostgreSQL, puis a démarré au second essai. Délai d'admission réseau d'un nouveau pod, déjà vu aux lots précédents.
- Le worker s'est reconnecté seul juste après l'expiration de A, sans redémarrage de Temporal. La cause de cette reconnexion n'a pas été établie.

#### Revue des critères de sortie d'ADR-0020

Revue documentaire du 2026-10-27, à partir des preuves des lots A à E. Aucun nouveau test n'a été exécuté pour cette revue.

| Critère | État | Preuves |
| --- | --- | --- |
| Kafka : anonyme et topic non autorisé refusés, publication et DLT réussies | Rempli | Lot D : seule l'écoute SASL_SSL est joignable, clair rejeté à la négociation, mauvais mot de passe refusé, `allow.everyone.if.no.acl.found` à faux. Topics hors ACL refusés par `TopicAuthorizationException`. Pannes Kafka et DLT réussies |
| Temporal : sans certificat ou certificat expiré refusés, reprise du worker | Rempli | Lot C2 : clair et TLS sans certificat client refusés, pannes et reprise réussies. Lot E1 : certificat expiré refusé |
| PostgreSQL et Garage : sans TLS ou mauvaise autorité refusés | Rempli | Lot C1 : clair refusé par `pg_hba.conf`, connexion refusée avec les seules autorités du système. Lot E1 : clair refusé par Garage, certificat refusé avec la seule racine interne |
| Rotation sans perte d'événement, restauration des preuves avec empreinte | Rempli | Lot E1 : rotation des certificats serveur sans redémarrage, test des notifications réussi pendant la rotation. Lot E2b : rotation des certificats clients Temporal sans redémarrage, test Temporal réussi. Phase 7 : restauration avec SHA-256 identique |
| Journaux, métriques d'échec et alertes de saturation sans secret | Non rempli | Dépend de la phase 6, non commencée |

Points de la proposition d'ADR-0020 hors critères de sortie, relevés par la revue :

- Autorisations Temporal : aucun autoriseur n'est configuré. Tout certificat client de l'autorité interne peut agir sur tous les namespaces. Le cluster n'en a qu'un, `default`, mais la limitation au namespace de l'application n'est pas faite. Limite acceptée par l'utilisateur le 2026-10-27 : un seul namespace, un certificat par service émis sous la racine hors ligne.
- Chiffrement des volumes de preuves sur l'hôte : absent, constaté en lecture seule par SSH le 2026-10-27. Le système de fichiers racine, qui porte les volumes de k3s et de Garage, est en ext4 sans couche de chiffrement. Acceptable tant que les données sont synthétiques. À traiter avant toute donnée réelle. Les copies restic du poste sont chiffrées, avec une clé hors du serveur.
- Réplication de Kafka : broker unique, facteur 1. Limite déjà documentée au lot D, acceptée pour un hôte unique.
- Fenêtre de maintenance et anciens volumes conservés : chaque bascule a été précédée d'une sauvegarde tirée sur le poste. Exception documentée au lot E1 : la copie à froid de Garage n'a pas été faite.

Conclusion : C7 n'est pas clos. Il reste la phase 6. Mise à jour du 2026-10-27 après le lot E2b : quatre critères remplis sur cinq.

#### Correctif : socket de tailscaled après une mise à jour

Statut : rétablissement vérifié le 2026-10-29. Correctif durable appliqué et vérifié le même jour, sauf le retour après un vrai redémarrage de `tailscaled`.

Incident du 2026-10-29 :

- Alerte `ProbeFailed` critique sur l'instance `garage` à 21:59 UTC, reçue par ntfy.
- La sonde blackbox échouait sur `remote error: tls: internal error`. Caddy ne servait plus aucun certificat.
- Journaux de Caddy : `dial unix /var/run/tailscale/tailscaled.sock: connect: connection refused` à chaque poignée de main.
- Cause : mise à jour automatique de Tailscale, de 1.104.1 à 1.104.4, à 21:55 UTC, relevée dans le journal de dpkg. La mise à jour automatique est active dans les préférences de Tailscale. `tailscaled` a redémarré et recréé son socket. Le service `tls` montait le fichier lui-même, donc l'ancien socket.
- Rétablissement : `docker restart` du conteneur `tls`. Sonde relancée depuis blackbox : `probe_success` à 1, statut 403 attendu, certificat valide. Garage n'a pas été redémarré.

Pourquoi monter le dossier ne suffit pas seul : l'unité `tailscaled` déclare `RuntimeDirectory=tailscale`. Sans autre réglage, systemd supprime `/run/tailscale` à chaque arrêt et en crée un nouveau au démarrage. Un montage du dossier pointerait alors aussi vers l'ancien.

Correctif durable :

- [deploy/garage/compose.yaml](../../../deploy/garage/compose.yaml) : le service `tls` monte le dossier `/run/tailscale` au lieu du fichier du socket. Le nouveau socket y apparaît sans recréer le conteneur.
- [deploy/garage/tailscaled-runtime.conf](../../../deploy/garage/tailscaled-runtime.conf) : drop-in systemd `RuntimeDirectoryPreserve=yes`, qui garde le dossier entre deux arrêts de `tailscaled`.
- Exposition inchangée : le dossier ne contient que `tailscaled.sock`, relevé par SSH le 2026-10-29.
- La mise à jour automatique de Tailscale reste active. Elle porte les correctifs de sécurité.

Application sur l'hôte, à faire par l'utilisateur, dans cet ordre :

1. Copier le drop-in vers `/etc/systemd/system/tailscaled.service.d/runtime-directory.conf` avec sudo, puis `sudo systemctl daemon-reload`. Aucun redémarrage de `tailscaled` n'est nécessaire.
2. Copier le nouveau `compose.yaml` dans le dossier de Garage de l'hôte, puis `docker compose up -d tls` pour recréer le proxy.
3. Vérifier que `systemctl show tailscaled -p RuntimeDirectoryPreserve` affiche `yes` et que la sonde de Garage passe.

Application, le 2026-10-29 :

- Drop-in installé par l'utilisateur avec sudo par SSH, puis `daemon-reload`. `systemctl show` affiche `RuntimeDirectoryPreserve=yes`.
- `compose.yaml` copié dans le dossier de Garage de l'hôte, ancienne version gardée en `compose.yaml.before-socket-dir`. Proxy recréé par `docker compose up -d tls`, Garage non redémarré.
- Montage constaté par `docker inspect` : `/run/tailscale` vers `/var/run/tailscale`. Le socket est visible dans le conteneur.
- Sonde relancée depuis blackbox : `probe_success` à 1, statut 403. Aucune alerte active dans Alertmanager.

Non vérifié : retour seul du proxy après un vrai redémarrage de `tailscaled`. Il demande sudo et coupe brièvement le tailnet. Il sera constaté à la prochaine mise à jour automatique, ou testé par l'utilisateur.

### Phase 9 : extension

- Second nœud agent k3s joint par Tailscale, puis HPA si une mesure le justifie.
- Essai ponctuel d'AKS ou EKS sur crédits gratuits, piloté par Terraform et détruit le jour même, avec alerte de budget.
- Images natives GraalVM envisageables plus tard, en tenant compte des contraintes de Hibernate, Kafka et Temporal.
- Aucun accès public avant la vérification des phases 6, 7 et 8.

### Phase 10 : exposition publique

Décidée par [ADR-0025](../decisions/ADR-0025-exposition-publique-cloudflare-tunnel.md). Statut : en cours depuis le 2026-10-28. Lot A, durcissement de Keycloak sans le tunnel, vérifié. Lot B, tunnel et noms publics, déployé et vérifié le même jour. L'application est servie sur le domaine public. Lot C, profil `demo` d'ADR-0031, déployé et vérifié le même jour : entrée en un clic par le nom public.

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
- Profil `demo` d'ADR-0031 : boutons de démonstration en un clic, CronJob de réinitialisation nocturne, bandeau de démonstration. Voir le lot C.

Sortie : application servie en HTTPS sur le domaine, aucun port entrant ouvert sur l'hôte ni sur la box, gateway et services internes injoignables depuis Internet, console Keycloak refusée sur le nom public.

#### Lot A : durcissement de Keycloak

Statut : implémenté et vérifié le 2026-10-28. Décision : [ADR-0032](../decisions/ADR-0032-client-demonstration-bascule.md).

Livré :

- `auditsphere-demo`, client du mot de passe direct, est désactivé au repos. `scripts/k3s-setup-keycloak.py` le remet désactivé à chaque passage.
- [scripts/k3s-demo-client.py](../../../scripts/k3s-demo-client.py) `status|enable|disable` bascule ce client avec le compte de service `k3s-demo-switch`, limité à `view-clients` et `manage-clients` du royaume `auditsphere`. Son secret est créé dans le cluster par `k3s-setup-keycloak.py`.
- [scripts/k3s-check.sh](../../../scripts/k3s-check.sh) active le client avant le script et le désactive à la sortie. Il refuse de démarrer si le client est déjà actif. Il fournit aussi les variables `SEED_*` : `seed-demo-missions.py` se lance désormais par lui.
- OTP exigé pour `superadmin`, enrôlé à sa prochaine connexion par le navigateur. Un OTP déjà configuré n'est pas modifié.
- Sonde blackbox `demo_client_disabled`, toutes les 5 minutes, et alerte `DemoClientEnabled` après 30 minutes de client actif. `ProbeFailed` exclut cette sonde.
- Déjà en place depuis le lot D de la phase 4 : inscription libre désactivée, détection de force brute, `kc-admin` avec OTP.

Vérifications du 2026-10-28 :

- Client désactivé : le jeton est refusé avec `invalid_client`, y compris en mot de passe direct.
- Compte `k3s-demo-switch` : 200 sur les clients du royaume, 403 sur ses utilisateurs et sur le royaume `master`.
- Smoke par `k3s-check.sh` réussi, client désactivé ensuite.
- Activation refusée quand le client est déjà actif, sans le désactiver.
- Interruption par Ctrl+C, simulée sur le groupe de processus pendant le smoke : client désactivé.
- Sonde : `probe_success` vaut 1 client désactivé et 0 client actif. Règles chargées sans erreur, aucune alerte active ensuite.
- `superadmin` en mot de passe direct, client actif : refusé avec `Account is not fully set up`.
- Deux missions de smoke retirées par `cleanup-test-data.py`, sauvegarde privée préalable : 40 missions de démonstration.

Non vérifié : déclenchement réel de `DemoClientEnabled` après 30 minutes, et enrôlement de l'OTP de `superadmin`, qui revient à l'utilisateur.

Reste en phase 10 au lot A : `cloudflared` et les deux noms publics, règles Cloudflare, console `/admin` refusée sur le nom public, sonde du tunnel.

#### Lot B : tunnel et noms publics

Statut : déployé et vérifié le 2026-10-28. Décision : [ADR-0033](../decisions/ADR-0033-origine-publique-unique.md), acceptée par l'utilisateur, qui a demandé la bascule.

Fait par l'utilisateur le 2026-10-28 :

- Zone du domaine ajoutée à Cloudflare, offre gratuite. Enregistrements de la page de parking de Namecheap supprimés.
- Serveurs de noms remplacés chez Namecheap. Vérifié : les serveurs du `.online` délèguent aux deux serveurs Cloudflare. Zone active.
- Tunnel `auditsphere` créé dans Cloudflare, sans nom public. Offre Zero Trust Free.
- Namespace `cloudflared` créé, et secret `cloudflared-token` créé par saisie masquée. Vérifié sans lire sa valeur : clé `token` présente.

Préparé dans le dépôt :

- [deploy/k3s/cloudflared.yaml](../../../deploy/k3s/cloudflared.yaml) : namespace au niveau `restricted`, refus par défaut en entrée, métriques ouvertes à Prometheus seul. `cloudflared` 2026.10.5 épinglé par empreinte, une réplique, 96 Mio au plus, racine en lecture seule.
- NetworkPolicy : `edge` et Keycloak, port 8080 seul, acceptent le pod `cloudflared`. Le port de gestion de Keycloak reste fermé au tunnel.
- Chart Keycloak : valeur `adminOrigin`, passée à `KC_HOSTNAME_ADMIN` depuis `KEYCLOAK_ADMIN_ORIGIN`. Vide, rien ne change.
- `k3s-setup-keycloak.py` et `k3s-demo-client.py` appellent Keycloak par `KEYCLOAK_ADMIN_ORIGIN` quand elle existe.
- Traefik : limite de débit par l'en-tête `Cf-Connecting-Ip`, valeur `edge.clientIpHeader`.
- Supervision : modules `https_tunnel`, qui exige HTTPS et l'en-tête `Cf-Ray`, et `https_public_refused`, qui attend 403 ou 404. Jobs `blackbox-tunnel` et `blackbox-public-refused`. Leurs cibles sont écrites par `k3s-monitoring.sh` dans `prometheus-private-targets`, vides tant que les origines sont celles du tailnet. L'alerte `ProbeFailed` les couvre.

Vérifications du 2026-10-28, sans déploiement :

- Rendu Helm de Keycloak avec et sans `adminOrigin`, rendu du chart web.
- Essai à blanc côté serveur de `cloudflared.yaml`, du chart Keycloak, du chart web et de `monitoring.yaml` : acceptés. Le namespace `restricted` admet le Deployment.
- `test_image_pins.py` réussi. Syntaxe des scripts modifiés vérifiée.

Bascule du 2026-10-28 :

- `cloudflared` déployé : quatre connexions enregistrées vers Cloudflare, 19 Mio de mémoire mesurés, métrique `cloudflared_tunnel_ha_connections` à 4 dans Prometheus.
- Règles Cloudflare créées par l'utilisateur : refus de `/admin` et `/realms/master` sur le nom `auth`, limitation de débit sur les formulaires de connexion. Routes du tunnel vers `edge` et Keycloak, sans route `http_status:404` : la règle du pare-feu suffit.
- Règle de transformation sur `X-Real-Ip` refusée par Cloudflare. Remplacée par la lecture de `Cf-Connecting-Ip` dans le BFF, voir ADR-0033. Image web de `04baf82` déployée avec `trustedProxyIpHeaders` à `cf-connecting-ip,x-real-ip`.
- Premières réponses 502 : les NetworkPolicy des charts n'étaient pas encore appliquées. Charts Keycloak et web mis à jour avec leurs valeurs, seul `edge` a redémarré.
- `.local/k3s.env` passé aux noms publics, ancienne version gardée hors Git. `k3s-deploy-services.sh`, `k3s-setup-keycloak.py` et `k3s-monitoring.sh apply` réussis.
- Les scripts de vérification recevaient `error code: 1010` : la vérification d'intégrité du navigateur de Cloudflare refuse l'agent `Python-urllib`. `scripts/target.py` et `seed-demo-missions.py` envoient désormais l'agent `AuditSphere-checks`. Node, utilisé par le BFF, n'est pas refusé.

Vérifications du 2026-10-28 après la bascule :

- Émetteur des jetons sur le nom public, console et API d'administration de Keycloak par le tailnet : `k3s-setup-keycloak.py` et `k3s-demo-client.py status` réussis.
- Smoke de l'API et smoke du BFF par `k3s-check.sh`, par les noms publics : connexion OIDC, cookie `__Host-` sécurisé, en-têtes de sécurité, 41 missions servies, déconnexion. Smoke du BFF repassé sur l'image de `04baf82`. Client `auditsphere-demo` désactivé ensuite.
- `/admin/`, `/admin/master/console/` et `/realms/master/` : 403 sur le nom public.
- Points internes : `/metrics` et `/health` de Keycloak, `/actuator/health` et `/metrics` de l'application en 404. API sans session en 401.
- Sondes `blackbox-tunnel` et `blackbox-public-refused` à 1, aucune alerte active.
- Limite de connexion du BFF par le nom public : 30 requêtes acceptées, la 31e refusée en 429. Un `X-Real-Ip` forgé ne crée pas de nouveau compteur. Un `Cf-Connecting-Ip` forgé est refusé par Cloudflare en 403. Par le tailnet, le compteur est distinct.

Console de compte fermée le 2026-10-28, selon [ADR-0034](../decisions/ADR-0034-console-compte-fermee.md) :

- Constat : `/realms/auditsphere/account/` répondait 200 sur le nom public. Le paramètre `kc_action` ouvrait aussi les formulaires du mot de passe et de l'OTP depuis la page de connexion, sans ressaisie du mot de passe pendant la session Keycloak.
- `k3s-setup-keycloak.py` désactive les clients `account` et `account-console` et règle `max_auth_age` à 0 sur les actions que `kc_action` peut lancer.
- Sonde `blackbox-public-refused` étendue à `/realms/auditsphere/account/` par `k3s-monitoring.sh`.
- Console d'administration bloquée par le tailnet : sa connexion passait par le royaume `master` sur le nom public, refusé en 403. `k3s-setup-keycloak.py` règle l'URL frontale de `master` sur `KEYCLOAK_ADMIN_ORIGIN`, voir ADR-0033. Vérifié : console servie et connectée par le tailnet, `/realms/master/` toujours en 403 sur le nom public.
- Vérifié : 404 de Keycloak sur la console et son API par les deux noms, mot de passe redemandé avant `kc_action` dans une session ouverte, inscription de l'OTP de `superadmin` toujours proposée, smoke de l'API et du BFF réussis par `k3s-check.sh`, sonde à 1, aucune alerte active.

Bascule, dans cet ordre, pour mémoire :

1. `kubectl apply -f deploy/k3s/cloudflared.yaml`. Le tunnel passe à l'état sain dans Cloudflare.
2. Dans Cloudflare, sur la zone : TLS 1.2 minimum, TLS 1.3, Always Use HTTPS, HSTS sur 6 mois sans preload, Bot Fight Mode désactivé.
3. Règle personnalisée du pare-feu applicatif, action Block : nom `auth` et chemin commençant par `/admin` ou `/realms/master`.
4. Règle de limitation de débit : nom `auth` et chemin contenant `/login-actions/`, 10 requêtes par 10 secondes et par adresse. Jamais sur le point de jeton.
5. Image du BFF qui comprend une liste d'en-têtes, et `trustedProxyIpHeaders` du chart web à `cf-connecting-ip,x-real-ip`, dans le même déploiement. La règle de transformation sur `X-Real-Ip` prévue d'abord est refusée par Cloudflare.
6. Noms publics du tunnel : le domaine vers `http://edge.auditsphere.svc.cluster.local:80`, `auth` vers `http://keycloak.auditsphere.svc.cluster.local:8080`. En premier, une entrée `auth` au chemin `^/(admin|realms/master)` vers `http_status:404`.
7. Dans `.local/k3s.env` : `KEYCLOAK_ADMIN_ORIGIN` reçoit l'ancienne `OIDC_ORIGIN`, puis `APP_ORIGIN` et `OIDC_ORIGIN` reçoivent les noms publics.
8. `scripts/k3s-deploy-services.sh`, puis `scripts/k3s-setup-keycloak.py` pour les adresses de retour, puis `scripts/k3s-monitoring.sh apply`.

Sortie du lot, atteinte le 2026-10-28 :

- Connexion de bout en bout par le nom public, et smoke par `k3s-check.sh`.
- `/admin/` et `/realms/master/` refusés sur le nom public, console joignable par le tailnet.
- Sondes du tunnel à 1, aucune alerte active.
- Compteurs du BFF séparés par visiteur, adresse forgée sans effet.
- Gateway et services internes injoignables depuis Internet. Aucun port entrant ajouté : le tunnel est sortant. Le pare-feu de l'hôte n'a pas été revérifié.
- Mémoire de `cloudflared` mesurée.

Limites :

- La connexion à l'application dépend de Cloudflare, même depuis le tailnet.
- Les appels du BFF à Keycloak sortent du cluster et reviennent par le tunnel.
- La limite de Traefik par `Cf-Connecting-Ip` n'a pas été poussée jusqu'au refus. Elle a été vérifiée par configuration seulement.
- Le nom `www` n'est pas servi.
- Les scripts de vérification dépendent de l'agent `AuditSphere-checks`, accepté par Cloudflare aujourd'hui.
- Plus de console de compte dans le royaume `auditsphere`, même par le tailnet. Un mot de passe se change par l'administration des comptes.
- Qui connaît le mot de passe d'un compte peut encore le changer par `kc_action`. L'entrée en un clic du lot C n'utilise aucun mot de passe, et la réinitialisation nocturne rétablit les mots de passe et retire les OTP des comptes de démonstration.

#### Lot C : profil demo

Statut : déployé et vérifié le 2026-10-28. Image du BFF du commit `87d5848` de `main`, tirée par empreinte. Décisions : [ADR-0031](../decisions/ADR-0031-profils-demonstration-production.md) et [ADR-0035](../decisions/ADR-0035-entree-demonstration-assertion.md).

Choix du mécanisme, détaillé dans ADR-0035 :

- Aucun authentificateur intégré de Keycloak 26.8 n'identifie un compte sans formulaire. Un flux dédié avec `Username Form` demande deux clics et ouvre une session Keycloak au visiteur. `kc_action` y relance alors le même flux sans mot de passe, et le verrouillage de force brute bloque l'entrée de tous.
- Retenu : octroi par assertion JWT, RFC 7523, actif par défaut dans Keycloak 26.8. Le BFF signe une assertion de 60 secondes au plus, à usage unique, pour le compte choisi. Keycloak la vérifie avec la clé publique du fournisseur `demo-entry` et ne retrouve que les comptes liés à ce fournisseur.
- Aucun mot de passe, aucune session Keycloak dans le navigateur, donc aucun `kc_action`. Le verrouillage de force brute ne s'applique pas à cet octroi.

Livré :

- `AUDITSPHERE_PROFILE` dans `.local/k3s.env` : `demo` par défaut, ou `production`. Lu par `scripts/k3s-setup-keycloak.py` et `scripts/k3s-deploy-services.sh`.
- [scripts/demo_accounts.py](../../../scripts/demo_accounts.py) : comptes synthétiques, profils et comptes ouverts en un clic, `manager`, `auditor`, `validator` et `auditee`. `superadmin` n'y figure pas.
- `k3s-setup-keycloak.py`, profil `demo` : clé ES256 dans le secret `web-demo-entry`, fournisseur `demo-entry` de type `jwt-authorization-grant`, liens des quatre comptes, octroi permis au seul client `auditsphere-web`. Compte de service `demo-reset`, limité à `view-users`, `query-users` et `manage-users`, et secret `demo-accounts` avec les mots de passe à rétablir. Profil `production` : tout cela est retiré.
- BFF : route `POST /auth/demo`, refusée sans l'en-tête `Origin` de l'application. Session chiffrée comme après une connexion normale, renouvelée par une nouvelle assertion toutes les 5 minutes. Déconnexion sans passage par Keycloak. Boutons sur la page de connexion et bandeau permanent, affichés par la valeur `demo.enabled` du chart web. Les boutons restent masqués tant que la clé manque.
- Chart [infra/helm/demo-reset](../../../infra/helm/demo-reset) : CronJob à 5 h, heure de Paris, après la sauvegarde. Étapes dans l'ordre : comptes, workflows Temporal en cours, bases, preuves et 40 missions par `seed-demo-missions.py`. Pas d'accès à l'API Kubernetes. NetworkPolicy ajoutées vers Keycloak, la gateway, PostgreSQL et Temporal. Certificat client Temporal `temporal-client-demo-reset`.
- [scripts/demo-reset.py](../../../scripts/demo-reset.py) `accounts|seed` : mots de passe, noms et adresses rétablis, actions requises vidées, OTP, WebAuthn et codes de récupération retirés, sessions fermées, verrouillage levé. Le client `auditsphere-demo` n'est actif que pendant le peuplement, et le Job échoue s'il l'est déjà.
- `evidence_storage.py` accepte les variables `EVIDENCE_S3_*` de l'environnement, pour retirer les preuves depuis le cluster.
- Depuis le jeu v2 du 2026-10-28, le ConfigMap porte aussi `demo_documents.py`, `demo_font.py` et `demo_dataset.py.gz`, compressé pour rester sous 1 Mio. Le peuplement dépose 137 justificatifs et 63 constats. Détails dans le [jeu de démonstration](17-donnees-demonstration.md).

Vérifications du 2026-10-28 :

- Octroi par assertion avec la clé du cluster et le secret du client web : jetons de `auditor`, `validator` et `auditee`, émetteur public, audience `auditsphere-api`, rôle attendu, sans jeton de rafraîchissement, valables 300 secondes. `manager` et `superadmin`, non liés, refusés avec `User not found`. Assertion rejouée refusée.
- Première exécution manuelle du CronJob : 5 minutes 6 secondes, 7 workflows arrêtés, 2 preuves retirées du stockage, 40 missions recréées : 8 brouillons, 12 en cours, 7 en revue, 7 approuvées, 6 clôturées. Client `auditsphere-demo` désactivé ensuite.
- Deuxième exécution, après avoir renommé `auditor`, changé son mot de passe et exigé un OTP : nom et actions requises rétablis. Mot de passe rétabli, prouvé par le smoke. `superadmin` inchangé, OTP conservé.
- `scripts/k3s-check.sh smoke.py` et `smoke-bff.py` réussis ensuite, avec l'image actuelle du BFF. La mission du smoke reste jusqu'à la réinitialisation suivante.
- Page de connexion locale avec `DEMO_ENTRY` : trois boutons et bandeau, sur ordinateur et sur téléphone. Clé invalide : retour sur la page avec le message d'indisponibilité.

Déploiement du BFF le 2026-10-28, image de `87d5848` et `demo.enabled` à vrai :

- Par le nom public, un clic sur chaque bouton ouvre la liste des missions sous le bon compte : Julien Moreau, auditeur, 21 missions. Thomas Laurent, validateur, 25 missions. Nadia Rousseau, audité, aucune mission, comme attendu pour ce rôle. Bandeau présent, barre latérale au-dessus de lui.
- Session de l'audité toujours ouverte après 5 minutes 30 secondes, au-delà des 300 secondes du jeton : renouvellement par une nouvelle assertion réussi.
- Déconnexion de chaque compte : retour sur la page de connexion avec le message de déconnexion, sans passage par Keycloak.
- `smoke.py` et `smoke-bff.py` réussis par `k3s-check.sh` avec la nouvelle image. 90 Mio de mémoire mesurés pour le BFF.

Responsable ajouté le 2026-10-28, à la demande de l'utilisateur. Seul ce rôle crée une mission et la soumet au validateur. `manager` lié au fournisseur par `k3s-setup-keycloak.py`, quatrième bouton dans le BFF. Octroi vérifié : jeton de `manager` avec le rôle `AUDIT_MANAGER`, `outsider` et `superadmin` toujours refusés.

Image du BFF de `41c1c94` déployée le même jour. Par le nom public, le bouton du responsable ouvre la session de Claire Martin : 42 missions, bouton de création de mission présent. Déconnexion sans passage par Keycloak.

Image du BFF de `f01f9d0` déployée le 2026-10-29, après le build vert de `main`. Elle ajoute la signature de l'auteur au bandeau. Vérifié par le nom public : réponse 200, ligne « Conçu et développé par » et lien vers le profil GitHub présents. Déploiement en révision 11 du release `web`, déploiement `web` sorti sans erreur. Chart `demo-reset` en révision 3 avec le jeu v2, appliqué à la prochaine réinitialisation.

Jeu v2 peuplé le 2026-10-29 par la réinitialisation manuelle `demo-reset-manual-2`, réussie en 9 minutes 19 secondes. Compteurs en base conformes au jeu v2, audit-service sans redémarrage, 565 Mio sur 768 après le Job. Nœud à 95 % de sa mémoire allouable ensuite. Détails dans le [jeu de démonstration](17-donnees-demonstration.md).

Première exécution planifiée, le 2026-10-28 à 04:00 UTC : échec.

- Comptes, workflows et bases réussis. Le peuplement s'arrête après 33 missions sur `PUT /missions/{id}/controls: HTTP 500`. Alerte `JobFailed` reçue.
- Cause : audit-service arrêté par OOM à 04:04:06 UTC, code 137, limite de 512 Mio. Le conteneur restait déjà à 505 Mio avant le Job. Le peuplement n'a fait que dépasser la marge.
- Mesure après redémarrage : 118 Mio de tas réservé, 185 Mio hors du tas suivis par la JVM, environ 115 Mio de mémoire native non suivie. Avec un tas plafonné à 60 % de 512 Mio, soit 307 Mio, l'ensemble peut atteindre environ 600 Mio. Les 140 Mio hors du tas estimés en phase 1 ne suffisent plus en k3s.
- Le nœud était à 75 % de sa mémoire selon `MemAvailable`, avec des limites déjà au-delà de la mémoire allouable. Choix de l'utilisateur : ne relever que audit-service.
- Correctif : limite à 768 Mio, demande à 512 Mio, `JAVA_TOOL_OPTIONS` avec `MaxRAMPercentage=50` pour ce seul service. Tas maximal vérifié dans le pod à 384 Mio, soit environ 380 Mio de marge pour le reste.
- Réexécution manuelle par `kubectl create job --from=cronjob/demo-reset` après le déploiement : réussie en 5 minutes 2 secondes, 40 missions recréées. audit-service sans redémarrage, pic de mémoire de travail à 543 Mio sur 768 pendant le peuplement. Nœud à 1,57 Gio disponibles ensuite.
- Le Job échoué reste listé, avec l'alerte, jusqu'à sa suppression ou à sa sortie de l'historique de trois échecs du CronJob.

Exécution du 2026-10-30, rattrapée à 13:57 UTC : échec.

- Alerte `JobFailed` sur `demo-reset-29888880`. Le conteneur `accounts` s'arrête avec le code 1 en quelques secondes : `ConnectionRefusedError: [Errno 111] Connection refused` à la demande du jeton du compte `demo-reset`. Aucun compte modifié, `temporal` et `database` non lancés.
- Cause : k3s ne tournait pas à 04:00 UTC, l'heure prévue. Cause de cet arrêt non recherchée, l'hôte avait redémarré à 00:19 UTC. À son retour, à 13:57:43 UTC, tous les pods sont recréés en même temps et le CronJob rattrape aussitôt l'exécution manquée, car `startingDeadlineSeconds` n'est pas défini. Keycloak démarrait encore : sonde de démarrage refusée, puis sonde de disponibilité en 503.
- Les étapes `temporal` et `database` attendaient déjà leur dépendance, 90 et 60 secondes. L'étape `accounts` n'attendait pas Keycloak.
- Correctif : `demo-reset.py accounts` interroge `/realms/auditsphere` avant de demander le jeton, toutes les 5 secondes pendant 5 minutes au plus. Il continue sur un refus de connexion, un délai dépassé ou une réponse 5xx, et échoue tout de suite sur une réponse 4xx.
- Vérifié localement avec un faux serveur : succès après deux réponses 503, échec avec `Keycloak injoignable après 5 minutes.` sur un port fermé, échec immédiat sur une réponse 404.
- Job échoué supprimé, puis réexécution manuelle `demo-reset-manual-3` avec l'ancien script : réussie en 9 minutes 28 secondes, 40 missions réconciliées. Les trois redémarrages d'audit-service datent tous du redémarrage de l'hôte, aucun pendant le Job.
- Correctif déployé le même jour : chart `demo-reset` en révision 4 par `helm upgrade --reuse-values --set-file scripts.reset=scripts/demo-reset.py`. ConfigMap vérifié : nouveau script présent, les autres fichiers conservés. Non vérifié : comportement réel au prochain redémarrage de k3s.

Non vérifié :

- Mémoire réelle des trois conteneurs du Job.
- Retrait d'un OTP ou d'une clé WebAuthn ajouté par un visiteur : l'API d'administration ne permet pas d'en créer pour le test. Le code retire tout identifiant autre que le mot de passe.

Limites :

- Sans `startingDeadlineSeconds`, une exécution manquée pendant un arrêt de k3s est rattrapée au redémarrage, à n'importe quelle heure. Elle efface alors en journée les données des visiteurs en cours.
- La réinitialisation n'arrête pas les services. Un événement Kafka en vol pendant la suppression peut laisser une notification orpheline jusqu'à la nuit suivante.
- `demo-reset` porte `manage-users` sur tout le royaume, `superadmin` compris. Le code ne touche que les comptes de `demo_accounts.py`.
- La clé `web-demo-entry` ouvre les quatre comptes liés, et aucun autre.
- Le responsable peut supprimer les brouillons, retirer ou clôturer des missions et en créer. Tout disparaît à la réinitialisation suivante. La remplacer : supprimer le secret, relancer `k3s-setup-keycloak.py`, puis redémarrer le BFF.

#### Condition d'ouverture

Arrêtée le 2026-10-26 avec l'utilisateur, conformément à ADR-0023 et ADR-0025. Aucun nom public ne sert l'application avant que ces quatre éléments soient vérifiés :

1. HOST-004, lots D et E : connexion de bout en bout, comptes de démonstration et smoke réussi dans le cluster. Vérifié le 2026-10-26.
2. HOST-008, sécurité C7 : Kafka en SASL_SSL avec ACL, vérifié le 2026-10-26 au lot D, NetworkPolicy autour de la gateway, de PostgreSQL, de Kafka et de Temporal, vérifiées le 2026-10-26 au lot A, TLS et authentification de Temporal, vérifiés le 2026-10-26 au lot C2, console Keycloak refusée sur le nom public, MFA des administrateurs, inscription libre désactivée. MFA de `superadmin` exigé et inscription vérifiée désactivée le 2026-10-28 au lot A de la phase 10. OTP de `superadmin` et de `kc-admin` configurés par l'utilisateur le 2026-10-28, aucune action en attente. Console refusée sur le nom public : vérifiée le 2026-10-28 au lot B de la phase 10.
3. HOST-006 : supervision avec alertes de disponibilité et d'abus.
4. HOST-007 : sauvegardes hors machine et restauration vérifiée.

Ordre retenu : lots D et E, puis phases 8, 6, 7 et 10. Révisé le 2026-10-26 par l'utilisateur : la phase 7 passe avant le lot C de la phase 8, pour disposer d'une restauration vérifiée avant toute bascule en TLS. Ordre en vigueur : lots A et B de la phase 8, phase 7, lots C à E, puis phases 6 et 10.

Préparation possible sans attendre, par l'utilisateur : délégation DNS du domaine à Cloudflare, après recopie des enregistrements existants, et création du tunnel sans nom public.

Accès des visiteurs anonymes : proposé le 2026-10-27 par [ADR-0031](../decisions/ADR-0031-profils-demonstration-production.md), accepté le 2026-10-28 et mis en œuvre au lot C de la phase 10. Deux profils de déploiement distincts, `demo` et `production`. Le serveur actuel sert le profil `demo` : entrée en un clic par rôle, responsable, auditeur, validateur et audité, par le BFF et sans mot de passe selon [ADR-0035](../decisions/ADR-0035-entree-demonstration-assertion.md), sans OTP pour ces seuls comptes, sans rôle d'administration, données synthétiques réinitialisées chaque nuit et bandeau de démonstration. Le profil `production` reprend le royaume isolé de C1 et C17. Le passage de l'un à l'autre se fait par redéploiement sur une base vide, jamais par un réglage dans une instance en marche. Un seul profil tourne à la fois sur ce serveur, faute de mémoire. De vraies données d'audit exigent un hébergement adapté et un nouvel ADR.

Seule la phase 7 pourrait être reportée, puisque l'instance publique ne porte que des données de démonstration. Ce report exigerait un amendement d'ADR-0025. Les phases 6 et 8 ne sont pas négociables.

## Limites connues

- Pas de haute disponibilité. Un reboot interrompt le service.
- Liaison réseau unique et aucune alimentation secourue.
- Un seul disque, d'où l'obligation de sauvegardes externes.
- Les budgets mémoire sont des estimations jusqu'aux mesures de la phase 1. Mesure du cluster complet au lot E de la phase 4.

## Vérifications effectuées

- Déploiement de `1cd3d78`, le 2026-11-02 sur le serveur : cohérence des écrans et pagination (TECH-012), smoke de l'API et du BFF réussis, filtres et pagination contrôlés par le nom public, aucune alerte hors `Watchdog`. Non vérifié : Safari. Détails dans le déploiement de `1cd3d78`.
- Déploiement de `6c7cb8d`, le 2026-11-01 sur le serveur : TECH-011 dans la gateway et le web, smoke de l'API et du BFF réussis, aucune alerte hors `Watchdog`. Non vérifié : la réponse 429 dans le cluster. Détails dans le déploiement de `6c7cb8d`.
- Déploiement de `995eff4`, le 2026-11-01 sur le serveur : interface des actions correctives, parcours F-006 réussi par `test-remediation.py`, écrans de l'audité et du responsable affichés par le nom public, smoke réussi, aucune alerte hors `Watchdog`. Détails dans le déploiement de `995eff4`.
- Déploiement de `6eac4e9`, le 2026-11-01 sur le serveur : correctif TECH-010 dans audit-service, brouillon avec contrôle lié à un risque supprimé en 204 puis 404, smoke réussi, aucune alerte hors `Watchdog`. Détails dans le déploiement de `6eac4e9`.
- HOST-013, le 2026-10-30 sur le serveur : remediation-service branché sur Kafka, Keycloak et Garage. 106 événements de constats synchronisés sans DLT, parcours F-006 réussi par `test-remediation.py` : suivi créé par Kafka, action notifiée à l'audité, justificatif déposé, relu et retiré. Smoke réussi, limite mémoire portée à 384 Mio. Non vérifié : réinitialisation nocturne avec des justificatifs. Détails dans la section HOST-013.
- Déploiement de `20dd6db`, le 2026-10-30 sur le serveur : migrations appliquées après sauvegarde, smoke de l'API et du BFF réussis, consommateur de notification-service rétabli après l'ajout du topic `audit.action.assigned.v1` et de son ACL. Non vérifié : flux F-006 dans k3s, faute de compte Kafka et de bucket pour remediation-service. Détails dans le déploiement de `20dd6db`.
- Veille externe de la supervision, le 2026-10-30 sur le serveur : `Watchdog` envoyé à healthchecks.io toutes les 5 minutes, battement suspendu par un silence, message DOWN publié sur le sujet ntfy 15 minutes après le dernier ping, puis UP au retour, les deux reçus sur le téléphone. Non vérifié : vrai arrêt du nœud. Détails dans la veille externe de la supervision.
- Incident du socket de `tailscaled`, le 2026-10-29 sur le serveur : cause relevée dans les journaux de Caddy et de dpkg, proxy redémarré, sonde de Garage de nouveau en succès. Correctif durable appliqué, montage du dossier et drop-in constatés, sonde en succès. Non vérifié : vrai redémarrage de `tailscaled`. Détails dans le correctif de la phase 8.
- Correctif de la mémoire de Kafka, le 2026-10-27 sur le serveur : table du nettoyeur ramenée de 128 à 16 Mio, mémoire de travail passée de 400 à 240 Mio, test avec pannes réussi. Détails dans le correctif.
- Phase 6 lot 6C, le 2026-10-27 sur le serveur : session d'observation ouverte et fermée, requête tracée de la gateway à audit-service et par Kafka, journaux reliés aux traces. Sortie de la phase 6 atteinte. Détails dans le lot 6C.
- Phase 6 lot 6B, le 2026-10-27 sur le serveur : métriques des quatre services Spring sur un port réservé à la supervision, isolation vérifiée, alertes d'abus, de DLT et d'arrêt par manque de mémoire reçues, tests des notifications et de Temporal réussis. Détails dans le lot 6B.
- Phase 6 lot 6A, le 2026-10-27 sur le serveur : Prometheus, Alertmanager, node-exporter, kube-state-metrics et sondes blackbox. Toutes les cibles actives, alerte réelle et alerte de test publiées sur le sujet ntfy, alerte de test reçue sur le téléphone. Détails dans le lot 6A.
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
- Phase 8 lot D, le 2026-10-26 sur le serveur : Kafka en SASL_SSL, un compte SCRAM et des ACL par service, refus du clair, d'un mauvais mot de passe, d'un nom faux et des topics non autorisés. Pannes Kafka, DLT et smoke réussis. Détails dans le lot D.
- Phase 8 lot C2, le 2026-10-26 sur le serveur : Temporal en mTLS, certificat client par appelant, refus du clair, de l'absence de certificat, d'un nom faux et d'un certificat à usage serveur. Pannes Temporal et smokes réussis, aucun workflow perdu. Détails dans le lot C2.
- Phase 8 lot E1, le 2026-10-26 sur le serveur et le poste : Garage en HTTPS joint depuis le poste et les pods, clair refusé, preuves relues, sauvegarde et restauration réussies, certificat Temporal expiré refusé, rotation des certificats de Kafka, PostgreSQL et Temporal sans redémarrage. Détails dans le lot E1.
- Phase 8 lot C1, le 2026-10-26 sur le serveur : PostgreSQL en TLS vérifié pour tous ses clients, clair refusé, smokes, pannes, sauvegarde et restauration réussis. Détails dans le lot C1.
- Phase 8 lot B, le 2026-10-26 sur le serveur : cert-manager installé, autorité intermédiaire chargée par l'utilisateur, certificat de test émis et vérifié jusqu'à la racine hors ligne, mémoire mesurée. Détails dans le lot B.
