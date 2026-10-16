# AuditSphere

Plateforme d’audit interne et projet d’apprentissage des systèmes distribués.

## État du premier jalon

Monorepo Maven : `audit-service` (Spring Boot) et `api-gateway` (Spring Cloud Gateway). PostgreSQL et Keycloak démarrent avec Docker Compose. Le frontend **Next.js / React / TypeScript / Tailwind** reste à créer, la démonstration actuelle utilise les API.

## Démarrage local

Prérequis : Docker avec Compose et Python 3. Pour compiler hors Docker : JDK 21 et accès à Maven Central (Maven Wrapper fourni).

```bash
python3 scripts/init-local.py
docker compose up -d --build
python3 scripts/smoke.py
```

La première commande génère des mots de passe aléatoires dans `.env` et un realm privé dans `.local/realm.json`, exclus de Git. Elle refuse d’écraser une configuration existante. Ne pas modifier ces mots de passe après initialisation des volumes sans procédure de rotation, l’import Keycloak ne remplace pas un realm déjà existant.

- API : http://localhost:8080
- Keycloak : http://localhost:8180, utilisateur administrateur `admin`, mot de passe dans `.env`.
- PostgreSQL et `audit-service` n’exposent aucun port sur l’hôte.
- Accès limités à la boucle locale. Cette configuration HTTP `start-dev` est réservée au développement.

```bash
./mvnw -B verify                 # tests d’intégration, Docker requis
python3 scripts/smoke.py         # démonstration réelle via Keycloak et gateway
docker compose logs -f          # journaux (Ctrl+C quitte le suivi)
docker compose down             # arrêt, données conservées
```

Chaque exécution du smoke test crée une mission synthétique persistante. Elle ne réinitialise aucune donnée. Le client `auditsphere-demo` utilise le grant mot de passe uniquement pour ce test local, l’interface utilisera Authorization Code + PKCE ou un BFF avec session sécurisée, à décider avant son implémentation. Ne pas réutiliser ce realm en production.

## Contrôle des permissions

- Création : rôle Keycloak `AUDIT_MANAGER` obligatoire.
- Consultation : créateur ou sujet affecté à la mission, même pour un responsable.
- Identité : claim OIDC `sub`, jamais le nom d’utilisateur fourni par le navigateur.
- Jeton : signature, émetteur, dates de validité et audience `auditsphere-api` validés par Spring Security.
- Mission absente ou non autorisée : `404` pour ne pas révéler son existence.
- Sans authentification : `401`. Création sans rôle : `403`. Entrée invalide : `400`.

Le premier endpoint prend des identifiants d’affectation OIDC, leur existence dans l’annuaire n’est pas encore contrôlée. Seul un responsable autorisé peut les fournir. La gestion complète des affectations, les dates métier et la piste d’audit détaillée feront l’objet des étapes suivantes.

## Documentation

- [Guide du premier jalon](auditsphere-docs/docs/technique/04-premier-jalon.md)
- [Contrat API](contracts/audit-api.yaml)
- [Stack](auditsphere-docs/docs/technique/03-stack.md)
- [Documentation produit](auditsphere-docs/README.md)
- [Règles de contribution](AGENTS.md)
