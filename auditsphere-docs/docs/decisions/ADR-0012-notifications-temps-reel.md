# ADR-0012 : Notifications en direct via SSE

**Date :** 2026-10-19
**Statut :** accepté pour le déploiement local à une instance de notification-service

## Contexte

L’utilisateur veut voir arriver une notification et son compteur sans recharger la page. La relecture toutes les trente secondes ne répond pas à ce besoin. Kafka et PostgreSQL assurent déjà la livraison et la conservation des notifications.

## Décision

Ajouter un flux SSE de notification-service vers le BFF Next.js, puis vers le navigateur. Le navigateur utilise sa session HttpOnly et ne reçoit aucun jeton. Le signal contient uniquement refresh, le BFF et les API existantes continuent de contrôler la lecture des données.

Le consommateur déclenche le signal après commit PostgreSQL, seulement pour une insertion nouvelle. Le marquage comme lu déclenche aussi le signal après écriture. Les abonnements sont sélectionnés exclusivement par sujet JWT. Une file bornée et un thread distinct évitent que la livraison SSE bloque le traitement Kafka ou une transaction SQL.

Le flux est borné à vingt-cinq secondes ou à l’expiration du JWT. Chaque reconnexion vérifie à nouveau la session BFF et le JWT. Le service accepte au plus huit flux par sujet et deux cents par instance. Les onglets masqués ferment leur flux. Une reconnexion après trois secondes et une relecture périodique compensent les interruptions. À chaque ouverture, un signal initial recharge l’état persistant.

## Conséquences et limites

Les signaux sont éphémères, les notifications restent persistées. Aucun historique SSE ni promesse de livraison exactement une fois n’est introduit. Si la file est pleine ou un navigateur lent, un signal peut être perdu et la reconnexion ou la relecture périodique récupère l’état. Le délai inclut la publication outbox, Kafka et les requêtes de lecture, il n’est pas nul.

Le registre d’abonnements est en mémoire dans une instance. Plusieurs instances de notification-service avec un même groupe Kafka ne diffuseraient pas chaque signal à tous les abonnements. Une diffusion partagée devra être ajoutée avant cette extension. La fiabilité des données ne dépend pas de ce canal de présentation. Un proxy de production devra autoriser le streaming sans mise en tampon, avec des délais adaptés.

## Vérification

Les tests Java vérifient l’absence de signal après rollback ou rejeu, l’authentification et l’isolation par sujet. Les tests navigateur vérifient l’arrivée sur une liste ouverte, le compteur et le marquage depuis un autre onglet. Les contrôles CSRF et l’accès aux titres restent appliqués.
