# ADR-0020 : sécurisation du déploiement interne

- Date : 2026-10-23
- Statut : proposé le 2026-10-23, implémenté dans k3s par la phase 8 du [guide d'hébergement](../technique/28-hebergement-k3s.md). Critères de sortie revus le 2026-10-27 : trois remplis, un partiel, un non rempli
- Constat : C7 de la revue de sécurité du 22 octobre 2026

## Contexte

Le Compose local utilise Kafka sans authentification, Temporal en mode développement et des liaisons internes sans TLS. Garage est joint par le réseau privé Tailscale. Ces choix ne constituent pas un déploiement de production validé. Les correctifs applicatifs ne suffisent pas à qualifier cette infrastructure pour des données réelles.

## Proposition

Conserver Compose pour le développement. Préparer une configuration de production distincte avec une autorité de certification interne et des identités propres à chaque service. Les secrets et clés privés restent dans un gestionnaire de secrets, jamais dans Git.

Kafka utilise SASL_SSL, une identité par producteur et consommateur, des ACL limitées aux topics, groupes et DLT nécessaires. Désactiver l'accès anonyme et la création automatique. Dimensionner la réplication et la rétention selon les objectifs de reprise documentés avant déploiement.

Temporal utilise un serveur avec persistance PostgreSQL, des migrations gérées et mTLS entre clients, workers et serveur. Le mode start-dev est interdit en production. Limiter les autorisations au namespace de l'application.

PostgreSQL exige TLS avec vérification du nom du serveur, des comptes runtime distincts des migrateurs et aucun port public. Garage est accessible en HTTPS avec vérification du certificat. Les volumes de preuves et les sauvegardes sont chiffrés, avec clés conservées séparément. Le chiffrement de volume ne protège pas contre une identité S3 autorisée compromise.

## Déploiement et retour arrière

Avant changement, inventorier les clients, préparer les certificats et droits minimaux puis tester sur un environnement isolé. Sauvegarder les bases, les données et métadonnées Garage, et vérifier une restauration. Planifier une fenêtre de maintenance pour basculer les endpoints. Conserver les anciens volumes intacts pendant la validation. Un échec entraîne un retour aux versions sauvegardées dans le réseau privé, sans ouvrir d'accès public ni désactiver la vérification TLS sur la cible.

Aucun certificat, compte d'infrastructure, volume ou déploiement réel n'est modifié par cet ADR. Le choix de l'hébergement, des responsables de clés et des objectifs de reprise doit être validé avant l'implémentation de C7.

## Critères de sortie

- Accès Kafka anonyme et accès à un topic non autorisé refusés, publication métier et quarantaine DLT réussies.
- Clients Temporal sans certificat ou avec certificat expiré refusés, reprise du worker vérifiée après panne.
- Connexions PostgreSQL et Garage sans TLS ou avec mauvaise autorité refusées.
- Rotation de certificats testée sans perte d'événement et restauration des preuves vérifiée avec contrôle d'empreinte.
- Journaux, métriques d'échec et alertes de saturation consultables sans divulguer de secrets.
