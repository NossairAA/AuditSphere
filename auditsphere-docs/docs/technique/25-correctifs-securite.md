# Correctifs issus de la revue de sécurité du 22 octobre 2026

Les constats applicatifs C1 à C6 et C8 à C18 sont implémentés et commités séparément. Les vérifications ont été réalisées localement les 23 et 24 octobre 2026. C7 reste une proposition de déploiement. Aucun push ni déploiement de production n'a été effectué.

## Identité et séparation des fonctions, C1, C2, C6 et C17

Le royaume de production ne contient aucun utilisateur de démonstration. Le projet Compose distinct isole ses volumes. Un royaume existant contenant un des comptes de démonstration fait échouer le provisionnement. La politique de mot de passe impose 14 caractères et l'OTP est obligatoire pour tous les utilisateurs du royaume de production. Les grants mot de passe sont désactivés, y compris sur admin-cli. Les tests de configuration et l'import dans un Keycloak jetable ont été exécutés. Le premier compte privilégié et le parcours OTP réels de production restent à vérifier selon [ADR-0017](../decisions/ADR-0017-identite-production.md).

La création et la réinitialisation d'un compte demandent une action email Keycloak, sans retourner de mot de passe. L'adresse existante est immuable dans l'interface. Le repli local explicite ajoute l'action requise au compte sans divulguer de secret. Les tests API et navigateur passent. L'envoi par SMTP de production n'a pas été testé.

Le validateur ne peut être un auditeur affecté. L'API et la migration V16 empêchent ce cumul, y compris lors de transactions concurrentes. Un superadministrateur propriétaire, validateur ou affecté ne peut approuver par dérogation. Le test navigateur vérifie le refus API, l'option désactivée et les opérations autorisées sur une autre mission. Voir [ADR-0018](../decisions/ADR-0018-separation-validateur-auditeur.md).

## Dépôts et limites de débit, C3 et C4

Le BFF admet au plus deux dépôts simultanés par sujet authentifié et huit par processus. Il vérifie la session, le rôle et le CSRF avant l'admission et avant lecture. La lecture totale est bornée à trente secondes et onze Mio, même sans Content-Length. Une saturation retourne 429, un dépassement de taille 413 et un délai dépassé 408. Les places sont libérées après succès, erreur et annulation.

Le proxy CSP exclut les routes API et auth pour que Next.js ne copie pas leurs corps avant ces contrôles. Un test HTTP réel laisse un corps multipart inachevé et confirme une réponse 401 immédiate à un dépôt anonyme. Les corps des pages qui passent par le proxy sont limités à 32768 octets.

La gateway garde le contrôle des octets effectivement reçus et son délai de cinq secondes. La mémoire tampon des preuves est protégée par les mêmes plafonds de concurrence. Les limites sont locales à chaque instance, une coordination distribuée est requise avant plusieurs réplicas. Le paramètre gateway.rate.ip-limit reste à 600 en dehors de Compose. Compose le relève à 1200, configurable par GATEWAY_RATE_IP_LIMIT, au niveau du plafond global. La limite individuelle reste à 240 par minute.

Les connexions OAuth sont séparées par IP uniquement avec un proxy de confiance qui écrase l'en-tête configuré et interdit l'accès direct au BFF. En HTTPS, une configuration manquante ou une adresse invalide provoque 503. L'API Next.js ne fournit pas la socket aux routes, le profil HTTP local conserve donc un compteur anonyme partagé. Le compteur de session utilise une session résolue et son jeton CSRF généré par le serveur, jamais un cookie arbitraire.

## Messages Kafka invalides, C5

Les erreurs de format JSON, de désérialisation et de validation sont transférées vers le topic source suffixé .DLT, à la même partition. Les topics sont créés explicitement avec trois partitions. Le producteur attend un acquittement avec acks=all. Une erreur de publication DLT laisse le message non récupéré, il sera retenté. Une erreur de base de données conserve les tentatives illimitées espacées de deux secondes.

La rétention DLT est de sept jours, comme les topics métier. Une alerte et un outil de rejeu supervisé restent nécessaires pour l'exploitation. Ne pas rejouer un message sans avoir corrigé sa cause. Le script test-notifications.py --faults a confirmé la mise en DLT d'un message invalide, la livraison du message valide suivant avec la même clé, ainsi que la reprise après les pannes de Kafka, de la base et du service. Les messages synthétiques en DLT restent jusqu'à expiration.

## Données, révocation et preuves, C8 à C11, C13 et C14

Le téléchargement recalcule l'empreinte SHA-256 et la taille, puis journalise et interrompt un flux altéré. Des octets peuvent avoir été envoyés avant la détection. Le test d'altération passe. Une clé S3 distincte limitée à la lecture reste à étudier.

Le compte SQL runtime ne peut modifier que outcome, completed_at, target_subject et requested_changes dans user_admin_log. Les droits complets UPDATE restent sur account_revocations. Les droits ont été appliqués localement après sauvegarde. Un test PostgreSQL vérifie que l'acteur, l'action, la raison et la date de création restent immuables.

La gateway et notification-service vérifient le registre de révocation avant de transmettre une requête authentifiée. Les erreurs entraînent un refus fermé. Les limites gateway s'appliquent avant l'appel au registre, afin qu'un jeton révoqué ne puisse saturer sa base. Un test composé vérifie ce comportement au-delà de 240 requêtes. Le délai du contrôle de révocation ne coupe pas les réponses métier ni les flux SSE.

Le quota cumulé des preuves est de 100 Mio par mission par défaut, configurable par EVIDENCE_MISSION_QUOTA_BYTES. Il est contrôlé sous verrou de mission et un objet dont la transaction est refusée est supprimé. Les tests de dépassement et de nettoyage passent. Les noms des auteurs sont résolus depuis le sujet dans l'annuaire. L'équipe ne peut être modifiée qu'en DRAFT ou IN_PROGRESS. Ces règles disposent de tests d'intégration.

## Protection web et flux, C12, C15 et C16

La CSP utilise une nonce par requête pour les pages, impose les sources locales par défaut et interdit cadres et objets. La redirection de déconnexion n'autorise que l'origine OIDC configurée en plus de la même origine. Le parcours navigateur vérifie le passage par Keycloak puis l'obligation de s'identifier à nouveau. HSTS couvre toutes les routes lorsque le build utilise APP_ORIGIN en HTTPS.

Les jetons des sessions PostgreSQL sont chiffrés en AES-256-GCM. Le hachage de session est authentifié avec le chiffré. Les tests vérifient le retour, l'altération et l'échange de lignes. Les anciennes sessions en clair ont été supprimées localement après sauvegarde. La clé dédiée reste obligatoire en production. Le profil local utilise temporairement une dérivation depuis le secret OIDC, sans modification des fichiers privés pendant cette intervention. Voir [ADR-0019](../decisions/ADR-0019-sessions-et-acces-web.md).

Trois flux SSE sont autorisés simultanément par utilisateur. Le plafond global par instance vaut 200 et se configure avec notifications.stream.max-connections. Les fermetures, expirations et erreurs libèrent leur place. Les requêtes supplémentaires retournent 429. Les tests de saturation et libération passent. Le rafraîchissement périodique reste disponible.

## Références immuables et infrastructure, C18 et C7

Les images externes sont épinglées par digest SHA-256, les actions GitHub par SHA de commit. Les références ont été vérifiées auprès des registres et dépôts. Les tests de configuration refusent les références mutables. La CI distante n'a pas été exécutée pour ces commits.

Le chantier de déploiement est décrit dans [ADR-0020](../decisions/ADR-0020-deploiement-interne-securise.md). Son statut reste proposé. Kafka SASL_SSL avec ACL, Temporal avec mTLS, TLS PostgreSQL et Garage, chiffrement des volumes et restauration vérifiée restent à implémenter dans l'environnement cible.

## Vérifications et limite externe

| Vérification | Résultat constaté |
| --- | --- |
| Maven verify complet | 88 tests réussis |
| Concurrence SQL C6 ajoutée ensuite | 1 test ciblé réussi |
| Ordre des filtres gateway ajouté ensuite | 3 tests ciblés réussis, dont 1 nouveau |
| Python, y compris droits PostgreSQL locaux | 15 tests réussis |
| Admission et lecture des dépôts avec Node | 4 tests réussis |
| TypeScript et build Next.js final | Réussis |
| Playwright, suites isolées | 27 tests réussis, 1 scénario bloqué par Garage |
| Smoke API avec vrais JWT | Réussi, rejoué après mise à jour des images gateway et notifications |
| Kafka avec injection de pannes et message invalide | Réussi |
| Temporal avec arrêts et rejeu | Réussi |
| Gitleaks sur les commits de correctifs | Aucune fuite détectée |

Le scénario navigateur de programme envoie désormais un fichier de 10 Mio, à la limite autorisée. Le dépôt franchit le BFF et la gateway, puis audit-service échoue à joindre Garage sur le réseau Tailscale. La connexion TCP au stockage distant expire. Ce scénario n'est donc pas déclaré réussi et doit être rejoué lorsque Garage est disponible. L'endpoint, les secrets et l'infrastructure distante n'ont pas été changés pour contourner cette panne.

Les tests navigateur retirent leurs missions synthétiques avec sauvegarde. Les quatre missions du test Kafka et les deux missions smoke identifiées précisément ont également été nettoyées. Les missions préexistantes sont conservées. Une validation locale ne vaut pas validation de production.
