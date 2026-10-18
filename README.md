# AuditSphere

Plateforme d’audit interne et projet d’apprentissage des systèmes distribués.

## État actuel

Backend Spring Boot avec gateway, PostgreSQL et Keycloak. Frontend Next.js, React, TypeScript et Tailwind avec connexion, liste filtrée, création et détail des missions. Un service de notifications consomme les affectations via Kafka et une outbox transactionnelle. La session web est conservée côté BFF, les jetons ne sont pas exposés au JavaScript du navigateur.

## Démarrage local

Prérequis : Docker Desktop actif, Python 3 et Node.js 24. Pour compiler le backend hors Docker, utiliser JDK 21.

Sur une nouvelle copie uniquement :

```bash
python3 scripts/init-local.py
```

Préparer puis démarrer les services :

```bash
sh scripts/start-local.sh
npm --prefix apps/web ci
npm --prefix apps/web run dev
```

Ouvrir **http://localhost:3000** (utiliser localhost, pas 127.0.0.1 pour les redirections OIDC).

Le lanceur sauvegarde les bases dans .local/backups, prépare les comptes SQL runtime, exécute les migrations avec un compte distinct et configure les clients Keycloak ainsi que la protection des connexions. Il conserve les missions et les utilisateurs. Les sauvegardes contiennent des données sensibles et restent privées et ignorées par Git. Ne pas changer les mots de passe des volumes existants en éditant simplement `.env`.

## Comptes de démonstration

| Identifiant | Rôle | Mot de passe local |
| --- | --- | --- |
| manager | Responsable, création autorisée | Valeur MANAGER_PASSWORD dans .env |
| auditor | Auditeur, lecture des missions affectées | Valeur AUDITOR_PASSWORD dans .env |
| outsider | Auditeur sans affectation initiale | Valeur OUTSIDER_PASSWORD dans .env |
| validator | Lecture des missions où il est désigné, approbation à venir | Valeur VALIDATOR_PASSWORD dans .env |

Le rôle AUDIT_VALIDATOR prépare la future validation humaine. Il ne donne aucun accès global aux missions ni droit de création ou de modification. Le workflow d’approbation reste à implémenter. Sur une installation existante, exécuter `python3 scripts/setup-web.py` pour préparer le rôle et le compte local, sans réinitialiser les mots de passe existants. Voir le [guide du rôle validateur](auditsphere-docs/docs/technique/11-role-validateur.md).

Les mots de passe sont aléatoires. Ne pas les publier ni les copier dans la documentation. Le compte administrateur Keycloak est distinct : `admin`, avec KEYCLOAK_ADMIN_PASSWORD dans `.env`.

## Tester

```bash
./mvnw -B verify
python3 scripts/smoke.py
python3 scripts/test-notifications.py --faults
cd apps/web
npx playwright install chromium
npm run test:e2e
```

Le test --faults arrête brièvement Kafka, notification-service et sa base, puis les redémarre. Le frontend doit tourner pendant les tests Playwright. Ils utilisent de vrais comptes et créent des missions synthétiques. Les traces et vidéos Playwright sont désactivées afin de ne pas enregistrer de secrets de connexion. Les captures locales de l’interface restent ignorées par Git.

```bash
npm --prefix apps/web run typecheck
npm --prefix apps/web run build
```

La CI GitHub reprend les tests backend, le smoke API, le build frontend et les parcours Chromium. Le fichier de workflow est fourni, son exécution distante pour ce jalon reste à vérifier après publication.

## Services et arrêt

- Web : http://localhost:3000
- API gateway : http://localhost:8080
- Keycloak : http://localhost:8180
- Bases PostgreSQL accessibles avec pgAdmin sur 127.0.0.1 : sessions 5544, métier 5545, identité 5546, notifications 5547.
- Kafka : réseau Docker uniquement, aucun port hôte publié.
- Paramètres et mots de passe : voir [Accès avec pgAdmin](auditsphere-docs/docs/technique/08-durcissement.md#acces-local-avec-pgadmin).

Arrêter le frontend avec Ctrl+C dans son terminal. Arrêter l’infrastructure en conservant les données :

```bash
docker compose down
```

Ne pas utiliser `down -v` sur des données à conserver.

## Sécurité et périmètre

Le client web utilise Authorization Code, PKCE, state et nonce avec un secret côté serveur. La gateway et le service métier valident les JWT. Seul le créateur ou un utilisateur affecté peut lire une mission. Une mission absente ou inaccessible renvoie 404.

Les cookies de session sont HttpOnly et SameSite=Lax. Leur valeur est opaque, seul son hash est stocké en base. Les jetons sont stockés côté serveur dans PostgreSQL. Les mutations web exigent une origine exacte et un jeton CSRF. L’exception HTTP est explicitement limitée à localhost, avec cookies Secure en HTTPS. Cette configuration Keycloak start-dev reste réservée au développement.

L’interface permet de choisir les auditeurs lors de la création et de modifier l’équipe depuis la fiche. Seul le responsable propriétaire peut modifier les affectations. Un auditeur retiré perd l’accès à la prochaine requête API. Le client `auditsphere-demo` conserve le grant mot de passe uniquement pour le smoke local. Aucune donnée réelle ni déploiement de production à ce stade.

## Notifications

Connecté comme manager, affecter auditor à une nouvelle mission. Connecté comme auditor, ouvrir Notifications puis Consulter la mission. La livraison est asynchrone et la première page s’actualise toutes les 30 secondes. Les anciennes affectations ne sont pas reprises automatiquement. Voir le [guide Kafka et notifications](auditsphere-docs/docs/technique/09-notifications-kafka.md).

## Cycle de vie des missions

Le responsable propriétaire peut enregistrer les dates prévues, démarrer la mission, la soumettre au validateur, puis la clôturer après approbation et confirmation. Les auditeurs consultent le statut et les dates. Voir le [guide du cycle de vie](auditsphere-docs/docs/technique/10-cycle-missions.md).

## Documentation

- [Notifications Kafka et reprise après panne](auditsphere-docs/docs/technique/09-notifications-kafka.md)

- [Durcissement et limites de production](auditsphere-docs/docs/technique/08-durcissement.md)
- [Affectation des auditeurs](auditsphere-docs/docs/technique/06-affectations.md)
- [Jalon frontend et BFF](auditsphere-docs/docs/technique/05-frontend-bff.md)
- [Premier jalon backend](auditsphere-docs/docs/technique/04-premier-jalon.md)
- [Contrat API](contracts/audit-api.yaml)
- [Documentation produit](auditsphere-docs/README.md)
- [Stack](auditsphere-docs/docs/technique/03-stack.md)

## Si localhost:3000 ne répond pas

Le frontend tourne sur l’hôte, séparément des conteneurs Docker. Keycloak peut donc fonctionner alors que le frontend est arrêté. Depuis la racine du projet, lancer npm --prefix apps/web run dev et garder ce terminal ouvert. Pour utiliser une compilation existante, lancer npm --prefix apps/web start. Ne pas démarrer deux processus sur le port 3000.

Vérification : curl -I http://localhost:3000 doit recevoir une réponse HTTP. Un processus lancé manuellement doit être relancé après son arrêt ou un redémarrage du Mac.

Le responsable peut maintenant désigner un validateur distinct depuis la fiche mission. Voir le [guide de désignation](auditsphere-docs/docs/technique/12-designation-validateur.md).

La clôture exige désormais une approbation du validateur désigné. Voir le [parcours de validation](auditsphere-docs/docs/technique/13-approbation-missions.md). Les missions déjà clôturées conservent leur état.
