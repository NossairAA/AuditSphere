# Frontend et session BFF

**Date :** 2026-10-16

## Parcours livré

Interface Next.js dans `apps/web`, React, TypeScript et Tailwind. Pages : accueil, liste des missions, formulaire de création et fiche de mission. Palette vert profond et ivoire, navigation latérale sur ordinateur et mise en page adaptée au mobile. Aucune donnée de tableau de bord fictive. Dans la liste des missions, chaque ligne n'affiche que le titre de la mission : le périmètre reste consultable sur la fiche de mission.

La liste est paginée côté backend et filtrée en SQL par créateur ou affectation. La recherche textuelle affichée filtre seulement la page courante. Un responsable ne voit pas toutes les missions du fait de son rôle. La création est réservée à AUDIT_MANAGER et la propriété est déduite du sub OIDC. L’interface ne gère pas encore l’affectation d’autres utilisateurs.

## Authentification

1. `/auth/login` crée une transaction de dix minutes en PostgreSQL avec state, nonce et vérificateur PKCE.
2. Un cookie de transaction HttpOnly lie la réponse à ce navigateur. Keycloak reçoit un challenge S256.
3. `/auth/callback` consomme la transaction une seule fois et valide le code avec openid-client.
4. Le BFF vérifie le JWT d’accès avec jose, notamment signature, issuer, audience et expiration.
5. Il conserve access token et refresh token côté serveur et remet un identifiant de session opaque au navigateur.

Le client confidentiel `auditsphere-web` autorise uniquement la redirection locale `/auth/callback` et impose PKCE. Le grant mot de passe est désactivé pour ce client. Il reste activé sur le client de test séparé `auditsphere-demo`.

## Sessions, renouvellement et déconnexion

La base `web` appartient au BFF. Une migration SQL versionnée est appliquée à son premier démarrage. Les identifiants aléatoires de session sont hachés SHA-256 en base. Les jetons sont chiffrés en AES-256-GCM avant leur écriture JSONB avec une clé dédiée du BFF. Les sessions anciennes en clair sont invalidées à la lecture. Une rotation de clé demande une reconnexion.

- Session inactive : expiration après 30 minutes.
- Durée absolue maximale : 8 heures, même avec activité.
- Access token : renouvellement à moins de 30 secondes de son expiration.
- Un verrou PostgreSQL sur la session sérialise les renouvellements concurrents.
- Échec du renouvellement : session supprimée, reconnexion requise.
- Déconnexion : POST protégé, session locale supprimée, révocation du refresh token puis passage par la déconnexion Keycloak. Une confirmation Keycloak peut être demandée.
- Les sessions expirées sont nettoyées lors des nouvelles connexions. Une tâche de purge périodique sera nécessaire avant exploitation prolongée.

## Protection des requêtes

Les mutations exigent une origine exactement égale à APP_ORIGIN et un jeton CSRF associé à la session, comparé en temps constant. Un GET ne crée pas de mission et ne déconnecte pas. Les réponses BFF sont non mises en cache. La politique Referrer-Policy vaut same-origin pour conserver l’origine des formulaires locaux et ne pas transmettre le référent aux autres origines. Aucun access token ou refresh token n’est renvoyé par `/api/session`, ni stocké dans localStorage.

Cookies HttpOnly, SameSite=Lax et chemin racine. En HTTPS, Secure et préfixe __Host-. HTTP est autorisé uniquement pour les origines localhost lorsque ALLOW_INSECURE_LOCAL vaut true. Les secrets sont dans `.env` et `apps/web/.env.local`, ignorés par Git et privés sur disque. Les secrets ne portent pas de préfixe NEXT_PUBLIC.

La session expose au navigateur le nom, le sub, les rôles et un jeton CSRF, uniquement pour l’affichage et les actions web. Les droits métier sont toujours appliqués côté Spring. Les erreurs de l’API sont affichées sans détails techniques sensibles.

## Vérification locale

- Tests backend PostgreSQL : neuf tests, incluant filtrage de liste, affectations, pagination et refus sans authentification.
- Cinq tests Chromium : connexion réelle Keycloak, création, détail, liste, déconnexion, isolation des auditeurs, CSRF, renouvellement concurrent, expirations et affichage mobile.
- TypeScript strict et build Next.js vérifiés. Les cinq tests Chromium passent aussi avec le serveur compilé démarré par npm start.
- Gitleaks ne détecte aucun secret dans les fichiers destinés à Git. Une comparaison avec les valeurs privées locales ne trouve aucune correspondance.
- Captures d’écran ordinateur et mobile inspectées. Débordement mobile corrigé au niveau du tableau.
- Les tests de renouvellement avancent l’échéance stockée de la session pour déclencher immédiatement un vrai échange de refresh token avec Keycloak.
- L’état indisponible de l’interface est vérifié en simulant une réponse 503 dans le navigateur.
- Les tests de cette version sont locaux. L’exécution CI distante n’est pas revendiquée.

## Limites

Pas de déploiement de production. Restent à traiter : TLS de bout en bout, sauvegarde du stockage de session, supervision et vérification du modèle de révocation en exploitation. L’expiration du jeton et la session BFF sont deux durées distinctes. La CSP à nonce impose un rendu dynamique. La limite de connexion distingue les IP seulement avec un proxy de confiance configuré. Sans ce proxy, elle partage une clé `direct` car Next.js ne fournit pas l’adresse de socket aux routes.

## Identité visuelle

**Date :** 2026-10-28

Le logo AuditSphere associe une tuile carrée arrondie vert pâle `#D9E7C1` à une icône de trois couches empilées en vert profond `#2B4736`. Le mot-symbole « AuditSphere » est en sans-serif gras, suivi d’un point vert pâle `#BBD5A1`. Couleurs relevées sur l’image de référence fournie par l’utilisateur.

- Composant `src/components/brand.tsx` : `BrandMark` dessine la tuile en SVG en ligne, `BrandLockup` y ajoute le mot-symbole en texte HTML. Utilisé par la page de connexion et par la barre latérale de l’espace de travail. Le mot-symbole reste du texte : il garde la police du site et reste lisible par les lecteurs d’écran.
- Fichiers SVG autonomes dans `public/brand/` : `auditsphere-mark.svg` pour l’icône seule, `auditsphere-logo.svg` pour un fond clair avec mot-symbole encre et point vert, `auditsphere-logo-inverse.svg` pour un fond sombre avec mot-symbole blanc. Le texte de ces deux fichiers dépend des polices Arial ou Helvetica de la machine qui les affiche.
- Icônes de l’application par les conventions de Next.js : `src/app/icon.svg`, `src/app/favicon.ico` en 16, 32 et 48 pixels, `src/app/apple-icon.png` en 180 pixels sur fond plein, car iOS arrondit lui-même les angles. Les deux images matricielles sont produites à partir du SVG par Chromium de Playwright.
- Le point ne dépend plus de l’espacement de la tuile. Il chevauchait la dernière lettre à la largeur d’un téléphone.
- Signature de l’auteur dans le bandeau de démonstration : « Conçu et développé par Nossair AIT AHMED », avec un lien vers son profil GitHub. Elle n’apparaît qu’avec `DEMO_ENTRY` à `true`, comme le bandeau. Sur ordinateur, elle suit l’avertissement sur la même ligne. Sur téléphone, elle passe sur sa propre ligne et la marge basse de la page monte à 72 pixels pour ne rien masquer.

Vérifié localement le 2026-10-28 : TypeScript strict et build Next.js réussis, page de connexion et barre latérale inspectées à la largeur d’un ordinateur et à 375 pixels, sans défilement horizontal. Les cinq fichiers d’icône et de logo répondent 200 avec le bon type de contenu, et les balises `icon` et `apple-touch-icon` sont présentes dans la page. La signature est vérifiée avec `DEMO_ENTRY` à `true` : une ligne de 32 pixels à 800 et 1024 pixels de large, trois lignes de 70 pixels à 375 pixels, sans chevauchement de la barre latérale ni défilement horizontal. Non déployé : l’image du BFF reste à reconstruire et à épingler après la fusion.

## Démarrage

Suivre le [README racine](../../../README.md). La base du BFF est publiée uniquement sur 127.0.0.1:5544 pour le processus Next.js exécuté sur l’hôte. Tous les exemples utilisent des données synthétiques.

## Évolution du jalon 3

La limitation concernant les affectations web est levée par le [jalon suivant](06-affectations.md). Le PUT utilise la même protection CSRF que la création.
