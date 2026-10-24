# ADR-0023 : hébergement privé avec k3s

**Statut :** Accepté le 2026-10-24, non implémenté
**Date :** 2026-10-24
**Auteur :** équipe AuditSphere

## Contexte

L'utilisateur veut héberger AuditSphere sur un serveur privé qu'il administre et apprendre Kubernetes avec les outils d'une plateforme professionnelle. L'application est conçue pour de nombreux utilisateurs, mais elle ne servira qu'à une ou deux personnes dans un premier temps. Les ressources doivent donc rester proportionnées à l'usage réel.

Le relevé du serveur du 2026-10-24, en lecture seule par SSH, donne un processeur x86 à 4 cœurs et 8 threads, 6,7 Gio de mémoire visible, 4 Gio de swap et 168 Go libres sur ext4, sous Ubuntu 24.04 LTS avec Tailscale. La mémoire n'est probablement pas extensible. Le serveur n'a ni alimentation secourue ni liaison réseau redondante. Le détail figure dans le [guide d'hébergement](../technique/28-hebergement-k3s.md).

La consommation mémoire dépend surtout du coût fixe des composants : cinq JVM, Keycloak, Kafka, Temporal et PostgreSQL. Une ou deux personnes n'ajoutent que quelques dizaines de Mio. La pile complète d'outils professionnels permanents demanderait environ 8,7 Gio, hôte compris.

## Options évaluées

1. Docker Compose sur le serveur privé. Avantages : déjà utilisé en local, léger. Inconvénients : ne répond pas à l'objectif Kubernetes, GitOps et Helm.
2. k3s sur le serveur privé, un seul nœud, outils répartis entre permanents et à la demande. Avantages : aucun coût récurrent, données privées sur le réseau Tailscale, vraie distribution Kubernetes. Inconvénients : mémoire serrée, pas de haute disponibilité, aucune alimentation secourue, un seul disque.
3. k3s sur un serveur Hetzner CX43, 8 vCPU et 16 Go. Avantages : pile complète permanente pour environ 20 € HT par mois. Inconvénients : coût récurrent, adresse publique dès le premier jour, données hors de l'infrastructure privée.
4. Kubernetes managé AKS ou EKS. Avantages : compétences recherchées, plan de contrôle géré. Inconvénients : environ 125 à 195 dollars par mois pour 16 Go selon les relevés du 2026-10-24, moins d'apprentissage du plan de contrôle.
5. Oracle Always Free. Avantages : gratuit. Inconvénients : allocation réduite à 2 OCPU et 12 Go Arm d'après la documentation officielle, images Arm à produire, risque de capacité indisponible ou de récupération du compte.

Les tarifs des options 3 à 5 proviennent de la page officielle Hetzner du 15 juin 2026, de la documentation Oracle et d'agrégateurs pour AWS et Azure. Ils sont à revérifier avant tout achat.

## Décision

Retenir l'option 2. Déployer AuditSphere sur un cluster k3s à un nœud sur le serveur privé, avec une réplique par composant. Les outils professionnels sont conservés mais réglés pour un faible volume. Une partie fonctionne en permanence, l'autre est démarrée à la demande.

- k3s utilise SQLite intégré, sans etcd. Traefik et servicelb intégrés sont désactivés à l'installation, car servicelb publierait les ports 80 et 443 sur toutes les interfaces du serveur.
- L'opérateur Kubernetes Tailscale fournit l'entrée et le certificat HTTPS. Aucun port entrant n'est ouvert vers Internet. Traefik est ajouté ensuite en ClusterIP, derrière l'entrée Tailscale, pour les règles HTTP.
- Un seul serveur PostgreSQL héberge les bases, avec une base et des rôles SQL propres à chaque service, comme le prévoit la stack de référence.
- Garage reste dans Docker hors du cluster, joint par l'adresse Tailscale.
- Argo CD en installation minimale assure le GitOps. Flux reste une solution de repli si la mesure montre un coût mémoire trop élevé, avec un ADR dédié.
- La documentation publique ne mentionne ni le lieu d'hébergement, ni le modèle de la machine, ni les comptes, ni les adresses privées, ni les outils de protection de l'hôte.

kind reste l'outil d'apprentissage local décrit dans la [stack de référence](../technique/03-stack.md). k3s devient la cible de l'environnement hébergé. Cet ADR complète ADR-0020 sans le remplacer : ses exigences TLS, identités et critères de sortie s'appliquent au cluster.

## Conséquences et risques

- Un reboot, une mise à jour du noyau ou une coupure de courant interrompt le service. C'est acceptable pour une ou deux personnes, pas pour un usage critique.
- Les sauvegardes hors machine et une restauration vérifiée deviennent obligatoires avant toute donnée réelle. Une autre partition du même disque ne compte pas comme sauvegarde.
- L'absence de second nœud empêche de pratiquer drain, affinité et PodDisruptionBudget. Un nœud agent supplémentaire sur Tailscale pourra rejoindre le cluster sans changer cette décision.
- Les limites mémoire doivent être mesurées avant d'ajouter un outil permanent. Un dépassement provoque des arrêts OOM et un swap continu.
- Les certificats HTTPS émis pour le tailnet sont publiés dans les journaux Certificate Transparency. Les noms de machine et de tailnet doivent donc rester neutres.
- Aucun accès public n'est ouvert avant la vérification des sauvegardes, de l'observabilité et de C7.

## Références

- [Guide d'hébergement k3s](../technique/28-hebergement-k3s.md)
- [ADR-0020 : sécurisation du déploiement interne](ADR-0020-deploiement-interne-securise.md)
- [Hetzner : ajustement des prix du 15 juin 2026](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/)
- [Oracle : ressources Always Free](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm)
- [AWS : tarifs EC2 à la demande](https://aws.amazon.com/ec2/pricing/on-demand/)
- [Tailscale : certificats HTTPS](https://tailscale.com/kb/1153/enabling-https)
