# 06 : Sécurité et conformité

**Statut :** Proposé

## Principes

- Authentification via fournisseur d'identité OIDC/SSO (Keycloak possible au début).
- RBAC pour les rôles, ABAC pour la mission, l'entité et la classification des données.
- Autorisations imposées **côté serveur**, pas seulement dans l'interface.
- Isolation entre périmètres d'audit et séparation des tâches (auditeur / validateur).
- Chiffrement en transit et au repos selon infrastructure, secrets via gestionnaire dédié.

## Preuves et pièces jointes

- Upload contrôlé, quotas, scan antimalware et validation des formats.
- Stockage objet privé, URLs signées de courte durée et contrôle d'accès préalable.
- SHA-256 ou équivalent pour intégrité, versionnement sans modification silencieuse.
- Politiques de conservation, archivage, suppression autorisée et gel légal si applicable.
- Sauvegardes chiffrées, exercices de restauration documentés.

## Piste d'audit

Inclure les actions critiques : accès à preuve sensible, création et validation d'un constat, changement d'échéance, modifications d'autorisations. Un journal *tamper-evident* peut être ajouté après validation du besoin (chaînage des empreintes, stockage à rétention protégée).

## Points à arbitrer

- Classification des preuves et niveau d'isolement par entité.
- Politique d'anonymisation, RGPD et durées de conservation applicables.
- SSO corporate, audit des accès privilégiés et exigences de souveraineté.
- Modèle de menace et tests de sécurité avant exposition production.

## Contrôle avant publication GitHub : 2026-10-16

Les secrets de développement restent dans `.env` et `.local/realm.json`, exclus de Git et du contexte Docker, avec permissions locales `0600`. Les valeurs de ces secrets sont recherchées dans tous les fichiers candidats avant le premier commit, sans être affichées. Les fichiers de clés privées et variantes `.env.*` sont également ignorés.

Gitleaks 8.24.2 contrôle les fichiers publiables et l’historique Git. Une exception locale documentée concerne uniquement l’URL JDBC sans identifiant ni mot de passe de Keycloak dans `compose.yaml`. La CI exécute le scanner sur l’historique avant de générer les comptes de démonstration. Aucun secret GitHub n’est requis par le parcours CI local.

Ce contrôle porte sur la publication du code, il ne transforme pas l’environnement HTTP de développement en déploiement de production sécurisé. Les secrets locaux sont des fichiers privés non chiffrés et doivent rester protégés sur la machine. Ne pas publier les exports Keycloak, volumes PostgreSQL ou rapports contenant des jetons.

## Session web

Le [guide BFF](05-frontend-bff.md) décrit le stockage des jetons côté serveur, PKCE, state, nonce, les cookies, le CSRF, le renouvellement et les limites de production. La navigation ne donne aucun privilège supplémentaire par rapport aux permissions du service métier.

## Durcissement du 23 octobre 2026

La passerelle et le service de notifications interrogent /account/security du service d'audit avec le jeton présenté avant de traiter une requête authentifiée. Un jeton révoqué reçoit 401. Une panne de cette vérification reçoit 503. Le délai de deux secondes de la passerelle porte seulement sur cette vérification et ne coupe pas la réponse métier ou le flux de notifications. Le service d'audit reste la source du registre account_revocations. Les limites de débit de la passerelle précèdent cette consultation, pour protéger aussi le registre contre les requêtes répétées avec un jeton révoqué.

Le script de durcissement PostgreSQL limite UPDATE sur user_admin_log à outcome, completed_at, target_subject et requested_changes. Il conserve UPDATE sur account_revocations. Les champs de l'acteur, de l'action, de la raison et de la création du journal ne sont plus modifiables par le compte applicatif. Les anciens droits de table sont retirés avant ces nouveaux GRANT. Ces privilèges ont été appliqués localement après sauvegarde. Le test PostgreSQL confirme le refus de modifier l'acteur, l'action, la raison et la date de création, tout en autorisant les colonnes de finalisation.

Les résultats de contrôle et les preuves résolvent maintenant le nom d'auteur par le sujet dans l'annuaire. Le nom d'un jeton ne décide plus de la valeur conservée. L'annuaire doit rester disponible au moment de l'écriture, avec le cache actuel de 60 secondes pour les noms déjà résolus.
