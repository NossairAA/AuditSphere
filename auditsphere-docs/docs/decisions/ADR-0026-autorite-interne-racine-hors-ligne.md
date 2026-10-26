# ADR-0026 : autorité interne à racine hors ligne

**Statut :** Accepté le 2026-10-26, implémenté et vérifié au lot B de la phase 8
**Date :** 2026-10-26
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0020](ADR-0020-deploiement-interne-securise.md) demande une autorité de certification interne pour PostgreSQL, Temporal, Kafka et Garage. Il réserve le choix des responsables de clés à une validation préalable. Le cluster est un nœud unique sur un serveur domestique, sans sauvegarde hors machine tant que la phase 7 n'est pas faite.

Deux voies étaient possibles :

- Racine auto-signée générée par cert-manager dans le cluster. Aucune étape manuelle, mais la clé racine n'existe que dans un secret du cluster. Une compromission du cluster l'expose, une perte du serveur la fait disparaître.
- Racine générée hors du serveur, sur le poste de l'utilisateur. Seule une autorité intermédiaire est chargée dans le cluster.

## Décision

Choix de l'utilisateur, le 2026-10-26 : racine hors ligne.

- La racine est une clé ECDSA P-384, valable dix ans, chiffrée par une phrase secrète. Elle est créée par `scripts/k3s-internal-ca.sh` sur le poste, dans `.local/ca`, ignoré par Git. La phrase secrète est rangée dans le gestionnaire de mots de passe de l'utilisateur. Une copie de la clé chiffrée est conservée sur un support hors ligne.
- L'intermédiaire est une clé ECDSA P-256 valable deux ans, avec `pathlen:0`. Sa clé n'existe que dans le secret `cert-manager/internal-ca`. La copie locale est supprimée dès le chargement.
- cert-manager v1.21.3 émet les certificats des services par la ClusterIssuer `auditsphere-internal-ca`. Les certificats émis portent la racine dans `ca.crt`, qui sert de point de confiance aux clients.
- Le script n'est lancé que par l'utilisateur, dans son terminal : la phrase secrète n'est jamais vue par un agent ni écrite dans un journal.

## Conséquences

- Une compromission du cluster expose l'intermédiaire, pas la racine. L'intermédiaire peut être révoquée par remplacement, sans redistribuer de nouveau point de confiance.
- Chaque signature d'intermédiaire demande la racine et sa phrase secrète : une intervention manuelle tous les deux ans au plus, et à chaque rotation anticipée.
- La perte de la phrase secrète oblige à recréer une racine et à redistribuer la confiance à tous les clients.
- L'intermédiaire n'a pas de contrainte de noms. Les noms du tailnet ne sont pas versionnés et Garage en aura besoin. Une contrainte pourra être ajoutée quand la liste des noms sera fixée.
- Le secret de l'intermédiaire est un secret Kubernetes ordinaire jusqu'à la phase 5, Sealed Secrets, et la phase 7, sauvegardes.
