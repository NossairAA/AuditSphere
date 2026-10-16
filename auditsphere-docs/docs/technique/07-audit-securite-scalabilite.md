# Audit de sécurité et de scalabilité

**Date :** 2026-10-16
**Périmètre :** copie locale avec jalon 3 non commité, base Git ae1398b.
**Verdict :** socle de développement local fonctionnel, exposition publique et capacité de production non validées.

## Méthode et limites

Revue des sources Spring et Next.js, configuration Docker, schéma PostgreSQL réellement installé, paramètres actifs du realm Keycloak, réponses HTTP, npm audit et scan des fichiers destinés à Git. Tests Maven relancés. Sonde de lecture limitée à cinq requêtes simultanées sur une session.

Aucun test destructif, aucune saturation volontaire et aucune modification applicative pendant cet audit. Les données métier existantes sont conservées. Seule la documentation est enrichie. Les appels d’authentification créent normalement une session et les lectures actualisent son activité.

Cet audit ne couvre pas Windows, le serveur SSH, la sécurité du Mac, un cluster Kubernetes ou un déploiement public. Aucun scan complet des CVE Maven et des couches des images Docker n’a été effectué. Les outils Trivy et Grype ne sont pas installés. Une absence de vulnérabilité npm ou de secret détecté ne constitue pas une garantie de sécurité.

P1 désigne un point à traiter avant exposition publique ou montée en charge. P2 désigne une amélioration nécessaire avant exploitation durable. Ces priorités ne sont pas des scores CVSS. Aucun contournement critique d’autorisation n’a été démontré dans le périmètre testé.

## Constats prioritaires

### SEC-01, P1 : comptes applicatifs PostgreSQL superutilisateurs

**Confirmé en exécution.** Les requêtes sur pg_roles renvoient rolsuper=true, rolcreatedb=true et rolcreaterole=true pour audit et web. Ces identifiants sont utilisés par Spring et le BFF. Ils proviennent de POSTGRES_USER dans [Compose](../../../compose.yaml).

Une compromission applicative ou une injection SQL éventuelle donnerait davantage de droits que les opérations métier nécessaires sur l’instance PostgreSQL concernée. Ce constat ne démontre pas une injection SQL actuelle. Les bases sont dans des conteneurs distincts.

**Correction :** séparer propriétaire de schéma, compte de migration et compte runtime non superutilisateur. Accorder au runtime seulement les opérations nécessaires sur ses tables et séquences. Vérifier que CREATE ROLE, CREATE DATABASE et les opérations de superutilisateur sont refusées. Éditer seulement POSTGRES_USER ne modifie pas un volume existant, une migration des rôles est nécessaire.

### SEC-02, P1 : configuration Keycloak réservée au développement

**Confirmé en exécution.** Le realm auditsphere utilise sslRequired=none et bruteForceProtected=false. Le client de démonstration accepte le grant mot de passe. Compose lance Keycloak en start-dev. Les ports publiés sont limités à 127.0.0.1, ce qui réduit l’exposition actuelle.

**Correction avant publication :** profil de production distinct, TLS, protection contre les tentatives répétées, suppression du client de démonstration et politique MFA adaptée aux comptes privilégiés. Vérifier aussi le realm master séparément. Ne pas publier le Compose local tel quel.

### SEC-03, P1 : protections contre l’épuisement de ressources insuffisantes

**Confirmé dans le code.** Aucun limiteur applicatif n’est configuré sur la gateway ou le BFF. Chaque appel anonyme à /auth/login insère une transaction OAuth et tente de purger les sessions expirées. Dans [api.ts](../../../apps/web/src/lib/api.ts), la limite de 32 Ko est vérifiée après request.text(), donc après lecture intégrale du corps en mémoire. Le chemin direct gateway vers Spring n’utilise pas cette limite BFF.

**Impact :** multiplication de sessions temporaires, de requêtes SQL et de mémoire allouée. L’impact sous attaque n’a pas été mesuré.

**Correction :** quotas par IP et identité, limites de taille dès la lecture ou à l’entrée réseau sur chaque chemin, budget global de requête et limitation des files d’attente. Tester des réponses 413 et 429 et contrôler que la mémoire reste bornée. [Référence OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Denial_of_Service_Cheat_Sheet.html).

### SEC-04, P1 avant données sensibles : jetons récupérables depuis la base de sessions

**Confirmé dans le code.** Le hash de l’identifiant de session protège sa valeur opaque. En revanche, accessToken et refreshToken restent lisibles dans le JSONB de web_sessions. La configuration active indique revokeRefreshToken=false. Le compte technique annuaire dispose aussi de droits de lecture plus larges que les informations retournées au navigateur.

**Impact :** une lecture non autorisée de la base ou de ses sauvegardes expose des jetons réutilisables pendant leur validité. Cela suppose une compromission préalable du stockage ou de son compte.

**Correction :** compte SQL minimal, sauvegardes protégées, chiffrement applicatif avec clé hors base ou mesure équivalente documentée, rotation des secrets et politique de renouvellement avec détection de réutilisation testée avec les appels concurrents.

### SCALE-01, P1 : appels réseau sous transaction et verrou SQL

**Confirmé dans le code.** Dans [MissionController.java](../../../services/audit-service/src/main/java/com/auditsphere/audit/MissionController.java), assign acquiert findForUpdate avant de consulter Keycloak. participants conserve sa transaction de lecture pendant la résolution des noms. La création est également transactionnelle pendant la validation annuaire.

**Impact :** un Keycloak lent retient des connexions SQL et, pour la modification, un verrou sur la mission. Les requêtes concurrentes peuvent attendre même si PostgreSQL est disponible. Le timeout BFF de dix secondes ne garantit pas l’arrêt de l’opération serveur, une modification pourrait se terminer après que l’interface a reçu une erreur.

**Correction :** limiter les appels externes avant une transaction métier courte, puis revérifier propriété et version dans cette transaction. Ajouter un délai global, des limites de concurrence et un mécanisme de coupure temporaire vers l’annuaire. Tester une panne Keycloak avec des lectures métier concurrentes.

### SCALE-02, P1 : amplification des appels Keycloak

**Confirmé dans [AuditorDirectory.java](../../../services/audit-service/src/main/java/com/auditsphere/audit/AuditorDirectory.java).** Un jeton technique est demandé à chaque opération. La validation d’un seul auditeur relit tout l’annuaire par pages de 100. Les noms des participants sont résolus séquentiellement, un appel par personne.

Une mission avec 100 auditeurs distincts du propriétaire peut donc provoquer 102 échanges HTTP pour les noms, dont la demande de jeton. Avec 100 ms par échange, cela représente environ 10,2 secondes par simple addition, sans mesure réelle à cette taille. Les timeouts de trois et cinq secondes s’appliquent à chaque appel, pas à l’opération complète. Exactement 5000 membres remplissent toutes les pages et déclenchent également l’erreur de limite.

**Correction :** réutilisation du jeton technique jusqu’à son échéance, validation ciblée des identifiants, annuaire paginé dans l’interface et cache ou projection des noms. Définir la fraîcheur requise et préserver la validation des comptes désactivés.

### SCALE-03, P2 : verrou et écriture de session à chaque lecture

**Confirmé dans [auth.ts](../../../apps/web/src/lib/auth.ts).** Chaque appel authentifié fait SELECT FOR UPDATE puis UPDATE du JSONB complet. Le pool BFF est limité à huit connexions par processus. Le renouvellement distant se produit sous le même verrou.

**Impact :** les onglets d’une même session sont sérialisés et chaque lecture produit une écriture. Les réplications horizontales multiplient le nombre de connexions vers PostgreSQL. Le verrou protège correctement les renouvellements concurrents, il ne faut pas le supprimer sans mécanisme de remplacement.

**Correction :** lecture sans verrou hors renouvellement, actualisation d’activité espacée, coordination atomique du refresh, délais SQL et budget de connexions partagé. Une purge périodique par lots doit remplacer la purge globale déclenchée par les connexions.

### SCALE-04, P2 : lecture des missions à optimiser avec un volume réaliste

**Confirmé partiellement.** Les index installés couvrent id, owner_subject et la paire mission_id, subject. Il n’existe pas d’index commençant par subject pour parcourir les affectations d’un auditeur. La liste utilise un OR entre propriétaire et appartenance, une pagination par offset et un total. La conversion de chaque résultat accède à la collection lazy des affectations, donc un risque de requêtes supplémentaires par mission existe.

Le plan coûteux et le nombre exact de requêtes par page ne sont pas mesurés dans cet audit. L’index actuel sur la paire reste utile pour les recherches par mission, il ne faut pas le considérer comme inutile.

**Correction :** mesurer EXPLAIN ANALYZE et les requêtes Hibernate sur 100 000 missions, évaluer un index subject, mission_id, un index adapté au tri, une projection de liste allégée et une pagination par curseur. [Index multicolonnes PostgreSQL 17](https://www.postgresql.org/docs/17/indexes-multicolumn.html).

### SEC-05, P2 : traçabilité et durcissement navigateur incomplets

**Confirmé.** eventsEnabled et adminEventsEnabled valent false dans le realm auditsphere. Aucun journal métier dédié ne conserve qui ajoute ou retire un auditeur. Les réponses web ont nosniff, DENY et no-store, mais pas de Content-Security-Policy.

**Correction :** journal métier transactionnel avec acteur, date et changement, sans jetons ni mots de passe. Activer les événements IAM utiles avec rétention adaptée. Ajouter une CSP compatible Next.js, d’abord en observation. Aucun XSS n’a été démontré.

### DATA-01, P2 : dernière écriture gagnante sur les affectations

**Confirmé dans le code.** Le verrou SQL rend chaque remplacement atomique, mais aucune version attendue ou condition If-Match ne détecte un formulaire périmé. Deux onglets autorisés peuvent réintroduire une affectation retirée par une modification précédente.

**Correction :** version métier ou ETag et réponse de conflit. Tester deux formulaires chargés avant la première modification. Ce risque n’est pas un contournement du contrôle de propriété.

### OPS-01, P2 : exploitation et chaîne de livraison non validées

Le Compose fourni n’établit pas de haute disponibilité, de sauvegarde restaurée, de limites CPU et mémoire ou de supervision de saturation. Les tags d’images et les actions GitHub ne sont pas verrouillés par digest ou SHA. Le dernier run connu du jalon 2 a échoué au smoke API, et le jalon 3 n’est pas publié.

**Correction :** rendre la CI verte, ajouter scans Maven et images, inventaire des composants, sauvegarde avec exercice de restauration, métriques de latence et de pools, puis dimensionnement. Ajouter Kubernetes ou Kafka ne résout pas à lui seul les goulots ci-dessus.

## Contrôles satisfaisants

- Autorisation métier appliquée dans Spring, propriétaire et affectations vérifiés à chaque lecture.
- Modification réservée au propriétaire disposant du rôle responsable.
- Retrait d’affectation effectif à la prochaine requête, indépendamment de la session existante.
- BFF avec cookie opaque HttpOnly, SameSite=Lax, PKCE, state, nonce, CSRF et origine exacte.
- Jetons absents du navigateur, requêtes SQL paramétrées et routes BFF prédéfinies.
- Validation JWT issuer, audience, signature et expiration testée côté backend.
- Secrets locaux ignorés par Git et fichiers privés, scan sans détection.
- Ports Docker publiés seulement sur boucle locale, processus Java applicatifs non root.

## Résultats vérifiés pendant cet audit

| Contrôle | Résultat | Portée |
| --- | --- | --- |
| Maven verify | 15 tests réussis | Permissions, JWT, annuaire et persistance |
| npm audit | 0 vulnérabilité signalée, 143 dépendances comptées | Registre npm au moment du contrôle |
| Gitleaks et comparaison des secrets privés | Aucune détection | Fichiers suivis et nouveaux destinés à Git, pas un nouveau scan de tout l’historique |
| Lecture BFF missions | 30 réponses 200, p50 16 ms, p95 54 ms | Une session, concurrence 5 |
| Lecture BFF annuaire | 30 réponses 200, p50 31 ms, p95 43 ms | Petit annuaire local |
| Lecture BFF participants | 30 réponses 200, p50 25 ms, p95 31 ms | Une mission locale |

La sonde porte sur 21 missions visibles du responsable. Elle exclut le temps initial de connexion et ne mesure ni la montée progressive, ni une charge durable, ni le multi-utilisateur, ni un grand annuaire, ni les pannes. Elle ne permet pas d’annoncer un nombre maximal d’utilisateurs. Les six tests navigateur du jalon 3 avaient réussi lors de l’intervention précédente, ils n’ont pas été relancés pendant cet audit.

## Ordre de traitement recommandé

1. Comptes SQL minimaux et limites de requêtes, puis profil IAM et TLS avant exposition.
2. Sortir Keycloak des transactions longues et réduire les appels annuaire.
3. Réduire les écritures de session, optimiser les lectures SQL et ajouter la détection des conflits.
4. Journaliser les opérations, sécuriser les sauvegardes, rendre la CI verte et compléter les scans.
5. Tester 10, 50 puis 100 sessions distinctes sur un jeu de données représentatif, avec seuils d’arrêt et panne Keycloak contrôlée. Mesurer p95, erreurs, CPU, mémoire, connexions et attentes de verrous.

Aucun correctif applicatif ni push n’a été effectué dans cette intervention.

## Suite de l’audit

Ce rapport décrit les constats au moment du contrôle. Les [remédiations ultérieures](08-durcissement.md) précisent les corrections vérifiées et les risques encore ouverts.
