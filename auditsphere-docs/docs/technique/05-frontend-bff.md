# Frontend et session BFF

**Date :** 2026-10-16

## Parcours livré

Interface Next.js dans `apps/web`, React, TypeScript et Tailwind. Pages : accueil, liste des missions, formulaire de création et fiche de mission. Palette vert profond et ivoire, navigation latérale sur ordinateur et mise en page adaptée au mobile. Aucune donnée de tableau de bord fictive.

La liste est paginée côté backend et filtrée en SQL par créateur ou affectation. La recherche textuelle affichée filtre seulement la page courante. Un responsable ne voit pas toutes les missions du fait de son rôle. La création est réservée à AUDIT_MANAGER et la propriété est déduite du sub OIDC. L’interface ne gère pas encore l’affectation d’autres utilisateurs.

## Authentification

1. `/auth/login` crée une transaction de dix minutes en PostgreSQL avec state, nonce et vérificateur PKCE.
2. Un cookie de transaction HttpOnly lie la réponse à ce navigateur. Keycloak reçoit un challenge S256.
3. `/auth/callback` consomme la transaction une seule fois et valide le code avec openid-client.
4. Le BFF vérifie le JWT d’accès avec jose, notamment signature, issuer, audience et expiration.
5. Il conserve access token et refresh token côté serveur et remet un identifiant de session opaque au navigateur.

Le client confidentiel `auditsphere-web` autorise uniquement la redirection locale `/auth/callback` et impose PKCE. Le grant mot de passe est désactivé pour ce client. Il reste activé sur le client de test séparé `auditsphere-demo`.

## Sessions, renouvellement et déconnexion

La base `web` appartient au BFF. Une migration SQL versionnée est appliquée à son premier démarrage. Les identifiants aléatoires de session sont hachés SHA-256 en base. Les jetons eux-mêmes sont conservés en JSONB côté serveur, sans chiffrement applicatif dans cet environnement de développement.

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

Pas de déploiement de production. Restent à traiter : TLS de bout en bout, politique CSP complète, limitation de débit, sauvegarde et chiffrement du stockage de session, supervision, gestion des affectations dans l’interface et modèle de révocation en exploitation. L’expiration du jeton et la session BFF sont deux durées distinctes. Le client web local n’altère pas la politique globale du realm.

## Démarrage

Suivre le [README racine](../../../README.md). La base du BFF est publiée uniquement sur 127.0.0.1:5544 pour le processus Next.js exécuté sur l’hôte. Tous les exemples utilisent des données synthétiques.

## Évolution du jalon 3

La limitation concernant les affectations web est levée par le [jalon suivant](06-affectations.md). Le PUT utilise la même protection CSRF que la création.
