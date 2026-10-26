# ADR-0027 : sauvegardes tirées par le poste

**Statut :** Accepté le 2026-10-26
**Date :** 2026-10-26
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0023](ADR-0023-hebergement-prive-k3s.md) rend obligatoires des sauvegardes hors machine et une restauration vérifiée avant toute donnée réelle. [ADR-0020](ADR-0020-deploiement-interne-securise.md) exige des sauvegardes chiffrées avec des clés conservées séparément, et une sauvegarde avec restauration vérifiée avant de basculer les connexions en TLS. Le serveur n'a ni alimentation secourue ni second disque.

La phase 7 du guide d'hébergement laissait deux cibles ouvertes : le poste macOS de l'utilisateur par le tailnet, ou un stockage objet externe. Elle demandait aussi que la clé de chiffrement reste hors du serveur.

Restic chiffre avec une clé symétrique. Pousser vers une cible depuis le serveur placerait cette clé sur le serveur, ainsi qu'un droit d'écriture sur la cible. Un serveur compromis pourrait alors lire ou détruire les copies.

Pour Garage, deux voies existaient : le script `deploy/garage/backup.sh`, à froid avec `sudo` et arrêt de Garage, ou une copie à chaud des objets par l'API S3.

## Décision

Choix de l'utilisateur, le 2026-10-26 : le poste tire les sauvegardes, Garage est copié à chaud par S3. La phase 7 passe avant le lot C de la phase 8.

- Sur le serveur, un CronJob quotidien du namespace `backup` écrit dans un volume local : un `pg_dump` de chaque base par le rôle `backup`, membre de `pg_read_all_data`, puis une copie des objets du bucket des preuves par rclone, avec une clé Garage en lecture seule. Aucune clé de chiffrement ni aucun accès au poste n'est sur le serveur.
- Le poste lance `scripts/k3s-backup.sh pull` : il lit le dernier jeu par un pod éphémère en lecture seule, vérifie les empreintes des dumps et l'ajoute à un dépôt restic local. Le mot de passe restic est dans le trousseau macOS. L'utilisateur en garde une copie hors du poste.
- La copie Garage ne supprime jamais et refuse de remplacer un objet modifié. Les preuves sont immuables, et leur SHA-256 est dans la base audit.
- L'exercice de restauration, `scripts/k3s-restore-test.sh`, restaure un instantané restic dans le namespace isolé `restore-test` et compare chaque preuve à son empreinte enregistrée.

## Conséquences

- Un serveur compromis ne peut ni lire ni effacer les copies hors machine. Il peut seulement cesser d'en produire, ce que la supervision de la phase 6 devra signaler.
- Les copies hors machine n'avancent que lorsque le poste est allumé. Un agent launchd lance le tirage chaque jour, une exécution manquée est rattrapée au réveil.
- Le volume local du serveur garde sept jours de dumps en clair, sur le même disque que les données. Il protège contre une erreur logique, pas contre la perte du disque. C'est un écart à ADR-0020, accepté : ces dumps n'exposent rien de plus que les volumes de PostgreSQL et de Garage, eux aussi en clair sur ce disque. Seules les copies hors machine sont chiffrées.
- La copie S3 ne contient ni les métadonnées de Garage ni ses clés. Elles se recréent par `scripts/setup-garage.py`. Le script `backup.sh` reste disponible pour une copie à froid complète, à faire avant le passage de Garage en HTTPS au lot E de la phase 8.
- Kafka n'est pas sauvegardé : ses messages sont transitoires et la source de vérité reste dans PostgreSQL, outbox comprise.
- Les secrets Kubernetes ne sont pas dans ces copies. Après la perte du serveur, ils sont régénérés par les scripts d'installation, jusqu'à la phase 5, Sealed Secrets.
