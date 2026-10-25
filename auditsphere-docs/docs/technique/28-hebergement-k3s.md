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

Statut : exécutée et vérifiée le 2026-10-25, sauf l'opérateur Tailscale. Les commandes `sudo` sont lancées par l'utilisateur, qui saisit lui-même son mot de passe. Les valeurs entre chevrons restent hors du dépôt.

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

L'opérateur Tailscale est installé ensuite par Helm, une fois le nœud validé.

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
- L'opérateur Tailscale demande un client OAuth créé dans la console Tailscale par l'utilisateur. Il n'est pas installé. Le critère « entrée injoignable hors du tailnet » reste donc à vérifier.
- Liaison réseau toujours en Wi-Fi.

### Phase 3 : couche de données

- Un StatefulSet PostgreSQL avec une base et des rôles runtime et migrateur par service.
- Kafka à un broker sur l'image officielle `apache/kafka`. Rétention de sept jours conservée, plafond de taille ajouté.
- Temporal en processus unique regroupant ses services, persistant dans PostgreSQL. Le mode start-dev reste interdit selon ADR-0020.
- Garage reste dans Docker hors du cluster, joint par l'adresse Tailscale.
- Volumes local-path sur la partition ext4.

Sortie : reprise des données après redémarrage du nœud, isolation des comptes SQL vérifiée.

### Phase 4 : application avec Helm

- Chart générique `infra/helm/spring-service` et un fichier de valeurs par service, plus Keycloak avec le royaume de production et le frontend.
- Migrations Flyway dans un Job avec le compte migrateur.
- Sondes, demandes basses, limites explicites et une réplique par déploiement.
- Traefik en ClusterIP derrière l'entrée Tailscale, pour les en-têtes de sécurité, la limitation de débit et le routage.
- Smoke et scénarios de pannes Kafka et Temporal exécutés par le tailnet, avec des données synthétiques uniquement.

Sortie : smoke réussi par l'URL HTTPS du tailnet, scénarios de pannes réussis sur le cluster.

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

### Phase 8 : sécurité C7

- NetworkPolicies, intégrées à k3s sans coût supplémentaire.
- cert-manager avec une autorité interne, Kafka en SASL_SSL avec ACL, Temporal en mTLS, PostgreSQL en TLS vérifié, Garage en HTTPS.
- Critères de sortie d'ADR-0020 appliqués au cluster.

Sortie : tous les critères d'ADR-0020 vérifiés. Aucune donnée réelle avant les phases 7 et 8.

### Phase 9 : extension

- Second nœud agent k3s joint par Tailscale, puis HPA si une mesure le justifie.
- Essai ponctuel d'AKS ou EKS sur crédits gratuits, piloté par Terraform et détruit le jour même, avec alerte de budget.
- Images natives GraalVM envisageables plus tard, en tenant compte des contraintes de Hibernate, Kafka et Temporal.
- Aucun accès public avant la vérification des phases 6, 7 et 8.

## Limites connues

- Pas de haute disponibilité. Un reboot interrompt le service.
- Liaison réseau unique et aucune alimentation secourue.
- Un seul disque, d'où l'obligation de sauvegardes externes.
- Les budgets mémoire sont des estimations jusqu'aux mesures de la phase 1.

## Vérifications effectuées

- Relevé matériel et logiciel du serveur par SSH le 2026-10-24, sans sudo ni modification.
- Tarifs cloud relevés le 2026-10-24 pour l'arbitrage d'ADR-0023. Ils ne sont pas vérifiés sur les pages officielles d'AWS et d'Azure.
- Aucune installation, aucun déploiement et aucun test sur le serveur à la date du relevé.
- Phase 0 : port d'administration local refermé sur 127.0.0.1 le 2026-10-24, vérifié depuis le tailnet. Commande de configuration exécutée par l'utilisateur avec sudo.
- Phase 1, le 2026-10-24 en local : smoke, scénarios de pannes Kafka et Temporal réussis sous limites, aucun arrêt OOM. Build Next.js et typecheck réussis avec la sortie standalone. Conteneur frontend démarré en utilisateur 10001, pages servies et HSTS présent. Analyse Trivy locale des cinq images. Tests Python d'épinglage étendus et réussis.
- Non vérifié : parcours Playwright locaux non relancés, car un serveur de développement occupait le port 3000. Ils sont exécutés par la CI. Publication GHCR non vérifiée avant la fusion dans `main`, vérifiée ensuite.
- Publication GHCR, le 2026-10-25 sur `main` après la fusion de la phase 1 : premier run, quatre images sur cinq refusées par GHCR avec `denied` au push du manifeste, alors que les couches étaient déjà poussées. Seule audit-service était publiée. Les jobs échoués, relancés sans changement de code, ont réussi. Les cinq images du commit `cb91e28` répondent en HTTP 200 sans authentification. Cause probable : création simultanée de paquets neufs par la matrice. Non confirmée, faute d'accès aux réglages des paquets.
- TECH-009, le 2026-10-25 en local : `./mvnw -B verify` réussi avec 108 tests Java. Pile relancée par `start-local.sh`, puis smoke, pannes Kafka et Temporal réussis. Aucun arrêt OOM ni redémarrage sous les limites existantes. Mémoire relevée après les scénarios : audit-service 369 Mio sur 448, gateway 188 Mio sur 384, notification-service 206 Mio sur 320, remediation-service 172 Mio sur 320, worker 109 Mio sur 256. Analyse Trivy bloquante réussie sur les quatre images Java. `test-browser-isolated.sh` réussi : 13 suites, 30 parcours Playwright.
- Phase 2, le 2026-10-25 sur le serveur : installation de k3s, contrôle des ports depuis le réseau local, pod de test et redémarrage complet. Détails dans l'exécution du 2026-10-25 ci-dessus. Opérateur Tailscale non installé.
- TECH-009, le 2026-10-25 sur `main` : CI distante réussie sur `2743acb` après la fusion. Job `verify` et cinq jobs `images` réussis, analyse Trivy bloquante comprise. Aucun job relancé.
