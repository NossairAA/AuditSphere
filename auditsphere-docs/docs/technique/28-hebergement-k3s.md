# Hébergement privé avec k3s

**Date :** 2026-10-24
**Statut :** proposé. Relevé matériel vérifié, aucune installation ni modification du serveur.
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

Estimations à mesurer en phase 1. Une réplique par composant.

| Composant | Limite visée |
| --- | --- |
| k3s, SQLite intégré, sans Traefik ni servicelb | environ 500 Mio |
| PostgreSQL unique, une base par service | 256 Mio |
| Kafka, un broker KRaft, tas de 256 Mio | 512 Mio |
| Temporal, processus unique persistant dans PostgreSQL | 384 Mio |
| Keycloak, tas réglé | 640 Mio |
| audit-service, api-gateway, notification-service, remediation-service, workflow-worker | 5 × 320 Mio |
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

### Phase 1 : images légères

- Créer un Dockerfile pour `apps/web` en sortie standalone, utilisateur non root, image de base épinglée par digest.
- Régler les JVM : `-XX:+UseSerialGC -XX:MaxRAMPercentage=70 -Xss512k`, pool HikariCP de 5 connexions, 50 threads Tomcat au plus. Limiter le tas de Keycloak.
- Publier les images dans GHCR avec un tag par SHA de commit, analyse Trivy et SBOM.
- Mesurer la mémoire réelle de chaque service sous limites avec Compose avant tout déploiement sur le serveur.

Sortie : tableau des mémoires mesurées, images publiées et analysées par la CI.

### Phase 2 : installation de k3s

- k3s à un nœud avec SQLite intégré et `--disable traefik --disable servicelb`.
- Réservations `--system-reserved` et `--kube-reserved` avec seuils d'éviction, pour protéger l'hôte.
- API Kubernetes sur le port 6443 joignable uniquement par Tailscale. `kubectl` utilisé depuis le Mac par le tailnet.
- Opérateur Kubernetes Tailscale pour l'entrée et le certificat HTTPS sur `*.ts.net`.
- Les certificats HTTPS de Tailscale sont inscrits dans les journaux publics Certificate Transparency. Les noms de machine, d'entrée et de tailnet deviennent publics. Les choisir neutres avant la première émission.
- Docker reste installé pour Garage. k3s utilise son propre containerd.

Sortie : API et entrée injoignables hors du tailnet, nœud stable après redémarrage.

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
- Aucune installation, aucun déploiement et aucun test sur le serveur.
