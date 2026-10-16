# AuditSphere

Plateforme d’audit interne et projet d’apprentissage des systèmes distribués.

## État actuel

Backend Spring Boot avec gateway, PostgreSQL et Keycloak. Frontend Next.js, React, TypeScript et Tailwind avec connexion, liste filtrée, création et détail des missions. La session web est conservée côté BFF, les jetons ne sont pas exposés au JavaScript du navigateur.

## Démarrage local

Prérequis : Docker Desktop actif, Python 3 et Node.js 24. Pour compiler le backend hors Docker, utiliser JDK 21.

Sur une nouvelle copie uniquement :

```bash
python3 scripts/init-local.py
```

Préparer puis démarrer les services :

```bash
python3 scripts/setup-web.py --configure-only
docker compose up -d --build
python3 scripts/setup-web.py
npm --prefix apps/web ci
npm --prefix apps/web run dev
```

Ouvrir **http://localhost:3000** (utiliser localhost, pas 127.0.0.1 pour les redirections OIDC).

Le script de configuration conserve les secrets existants, génère les nouveaux secrets privés du BFF et crée ou actualise uniquement le client Keycloak `auditsphere-web`. Il ne supprime ni les missions ni les utilisateurs. Ne pas changer les mots de passe des volumes existants en éditant simplement `.env`.

## Comptes de démonstration

| Identifiant | Rôle | Mot de passe local |
| --- | --- | --- |
| manager | Responsable, création autorisée | Valeur MANAGER_PASSWORD dans .env |
| auditor | Auditeur, lecture des missions affectées | Valeur AUDITOR_PASSWORD dans .env |
| outsider | Auditeur sans affectation initiale | Valeur OUTSIDER_PASSWORD dans .env |

Les mots de passe sont aléatoires. Ne pas les publier ni les copier dans la documentation. Le compte administrateur Keycloak est distinct : `admin`, avec KEYCLOAK_ADMIN_PASSWORD dans `.env`.

## Tester

```bash
./mvnw -B verify
python3 scripts/smoke.py
cd apps/web
npx playwright install chromium
npm run test:e2e
```

Le frontend doit tourner pendant les tests Playwright. Ils utilisent de vrais comptes et créent des missions synthétiques. Les traces et vidéos Playwright sont désactivées afin de ne pas enregistrer de secrets de connexion. Les captures locales de l’interface restent ignorées par Git.

```bash
npm --prefix apps/web run typecheck
npm --prefix apps/web run build
```

La CI GitHub reprend les tests backend, le smoke API, le build frontend et les parcours Chromium. Le fichier de workflow est fourni, son exécution distante pour ce jalon reste à vérifier après publication.

## Services et arrêt

- Web : http://localhost:3000
- API gateway : http://localhost:8080
- Keycloak : http://localhost:8180
- Base du BFF : port 5544, boucle locale uniquement
- Bases métier et identité : aucun port publié sur l’hôte

Arrêter le frontend avec Ctrl+C dans son terminal. Arrêter l’infrastructure en conservant les données :

```bash
docker compose down
```

Ne pas utiliser `down -v` sur des données à conserver.

## Sécurité et périmètre

Le client web utilise Authorization Code, PKCE, state et nonce avec un secret côté serveur. La gateway et le service métier valident les JWT. Seul le créateur ou un utilisateur affecté peut lire une mission. Une mission absente ou inaccessible renvoie 404.

Les cookies de session sont HttpOnly et SameSite=Lax. Leur valeur est opaque, seul son hash est stocké en base. Les jetons sont stockés côté serveur dans PostgreSQL. Les mutations web exigent une origine exacte et un jeton CSRF. L’exception HTTP est explicitement limitée à localhost, avec cookies Secure en HTTPS. Cette configuration Keycloak start-dev reste réservée au développement.

L’interface crée des missions dont l’utilisateur est responsable. L’affectation d’autres auditeurs reste accessible par l’API et sera ajoutée à l’interface ultérieurement. Le client `auditsphere-demo` conserve le grant mot de passe uniquement pour le smoke local. Aucune donnée réelle ni déploiement de production à ce stade.

## Documentation

- [Jalon frontend et BFF](auditsphere-docs/docs/technique/05-frontend-bff.md)
- [Premier jalon backend](auditsphere-docs/docs/technique/04-premier-jalon.md)
- [Contrat API](contracts/audit-api.yaml)
- [Documentation produit](auditsphere-docs/README.md)
- [Stack](auditsphere-docs/docs/technique/03-stack.md)
