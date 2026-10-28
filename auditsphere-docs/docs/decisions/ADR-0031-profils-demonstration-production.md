# ADR-0031 : profils de démonstration et de production

**Statut :** Accepté le 2026-10-28, profil `demo` implémenté selon [ADR-0035](ADR-0035-entree-demonstration-assertion.md)  
**Date :** 2026-10-27  
**Auteur :** équipe AuditSphere

## Contexte

[ADR-0025](ADR-0025-exposition-publique-cloudflare-tunnel.md) ouvre l'application à Internet par Cloudflare Tunnel et laisse une question ouverte : ce qu'un visiteur anonyme peut faire.

L'utilisateur envisage deux usages. D'abord un portfolio : des recruteurs doivent pouvoir essayer l'application sans créer de compte. Ensuite, peut-être, un produit utilisé avec de vraies données d'audit. Il veut pouvoir passer de l'un à l'autre.

Ces deux usages ont des exigences opposées. Un recruteur abandonne si l'entrée demande un compte ou un OTP. Un produit exige l'OTP, l'invitation des comptes et l'absence de tout compte partagé.

Le plan de phase 10 prévoit de désactiver le client `auditsphere-demo` et son mot de passe direct, selon C17. Un accès de démonstration ne peut donc pas reposer sur ce client.

Le serveur privé actuel n'a qu'un nœud, sans alimentation secourue ni redondance réseau. Le nœud atteint 96 % de sa mémoire allouable pendant une session d'observation.

## Options évaluées

1. Une seule instance avec un interrupteur démonstration ou production. Avantages : un seul déploiement. Inconvénients : comptes ouverts au public et données réelles dans la même base et le même royaume, erreur de bascule irréversible.
2. Compte de démonstration en lecture seule. Avantages : risque faible. Inconvénients : ne montre ni la soumission, ni la validation par Temporal, ni les notifications par Kafka, qui font l'intérêt du projet.
3. Deux profils de déploiement distincts. Le profil `demo` offre une entrée en un clic par rôle sur des données synthétiques réinitialisées chaque nuit. Le profil `production` reprend le royaume isolé de C1 et C17. Avantages : chaque profil garde ses propres règles, passage de l'un à l'autre par redéploiement. Inconvénients : deux configurations à maintenir et à tester.
4. Aucun accès anonyme. Avantages : aucune surface ajoutée. Inconvénients : un recruteur ne verra que la page de connexion.

## Décision proposée

Retenir l'option 3.

Profil `demo` :

- Boutons d'entrée en un clic sur la page de connexion : responsable, auditeur, validateur, audité. Le responsable est ajouté le 2026-10-28, voir ADR-0035. Comptes synthétiques dédiés, sans aucun rôle d'administration.
- Connexion par le BFF, qui garde la session comme pour une connexion normale. Aucun mot de passe direct, le client `auditsphere-demo` reste désactivé comme prévu en phase 10. Mécanisme retenu par ADR-0035 : octroi par assertion JWT, sans mot de passe ni session Keycloak dans le navigateur.
- OTP non exigé pour ces seuls comptes. Le `superadmin` garde l'OTP obligatoire et reste hors du nom public.
- Données synthétiques remises à zéro chaque nuit par un CronJob, à partir de `scripts/seed-demo-missions.py` et `scripts/cleanup-test-data.py`. Le même CronJob rétablit les mots de passe et le profil des comptes de démonstration et retire leurs OTP et clés WebAuthn.
- Bandeau permanent : environnement de démonstration, réinitialisé chaque nuit.
- Garde-fous : limitation de débit Cloudflare, quotas existants, preuves bornées en taille. Le dépôt de preuves pourra être désactivé dans ce profil.

Profil `production` :

- Royaume de production isolé selon C1 et C17 : aucun compte de démonstration, provisionnement refusé si l'un subsiste, OTP pour tous, mots de passe directs désactivés.
- Comptes créés par invitation par e-mail selon C2.

Passage d'un profil à l'autre :

- Il se fait par redéploiement avec l'autre profil, jamais par un réglage dans une instance en marche.
- De `demo` vers `production` : base et royaume repartent à zéro. Rien de la démonstration ne survit.
- De `production` vers `demo` : jamais sur les mêmes données. Des données d'audit réelles ne cohabitent pas avec des comptes ouverts au public.
- Le serveur actuel ne fait tourner qu'un profil à la fois, faute de mémoire.

Hébergement :

- Le serveur actuel sert le profil `demo`, le seul compatible avec ADR-0025, qui limite l'instance publique aux données de démonstration.
- Le profil `production` reste prêt et testé dans le code. Son déploiement avec de vraies données exige un hébergement adapté et un nouvel ADR : disponibilité, chiffrement de bout en bout ou acceptation explicite du déchiffrement par Cloudflare, RGPD et engagements contractuels.
- Si les deux coexistent un jour, ils utilisent deux noms publics distincts, par exemple `demo` et `app`.

Cet ADR complète ADR-0025 sans le remplacer. Il répond à sa question sur les visiteurs anonymes.

## Conséquences et risques

- Les comptes de démonstration sans OTP sont une exception assumée. Elle n'existe que dans le profil `demo` et ne concerne aucun rôle d'administration.
- Un visiteur peut écrire des contenus visibles par d'autres visiteurs jusqu'à la réinitialisation suivante. La réinitialisation nocturne et la limitation de débit bornent ce risque, sans le supprimer.
- La disponibilité du serveur privé n'est pas garantie. Pour le portfolio, une vidéo du parcours complet et des captures, notamment de Grafana, accompagnent le lien vers la démonstration et ne le remplacent pas. Grafana n'est pas publié.
- Passer au produit demande plus qu'un changement de profil : multi-organisation pour plusieurs clients, SMTP de production vérifié, conformité RGPD, hébergement avec engagement de disponibilité. Les charts Helm rendent l'hébergement portable vers un Kubernetes géré.
- Deux profils doublent les vérifications de configuration de Keycloak et du frontend.

## Références

- [ADR-0025 : exposition publique par Cloudflare Tunnel](ADR-0025-exposition-publique-cloudflare-tunnel.md)
- [ADR-0035 : entrée de démonstration par assertion signée](ADR-0035-entree-demonstration-assertion.md)
- [ADR-0017 : identité de production](ADR-0017-identite-production.md)
- [ADR-0023 : hébergement privé avec k3s](ADR-0023-hebergement-prive-k3s.md)
- [Guide d'hébergement k3s](../technique/28-hebergement-k3s.md)
- [Backlog](../delivery/02-backlog.md)
