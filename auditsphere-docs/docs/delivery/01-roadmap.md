# 08 : Roadmap évolutive

**Statut :** priorité métier révisée le 2026-10-19, durée à ajuster.

## Ordre actif

Administration globale des missions et dérogations tracées ajoutées et vérifiées localement le 2026-10-20 à la demande de l’utilisateur.

Préalable terminé en local le 2026-10-20 : retrait de soumission et suppression contrôlée des brouillons, avec tests et nettoyage des données synthétiques.

1. Risques et contrôles d’une mission, première tranche de F-003 vérifiée localement le 2026-10-20.
2. Résultats des contrôles implémentés et vérifiés localement. Ajout et téléchargement des preuves vérifiés localement avec Garage, cycle de conservation à compléter.
3. Constats et recommandations vérifiés le 2026-10-24 dans audit-service, CI distante réussie et publiés dans `main`.
4. Actions correctives et vérification de leur résolution, cadrées le 2026-10-24. Service dédié remediation-service retenu par ADR-0022, accepté.
5. Premier moteur d’audit continu sur une source et une règle versionnée, puis planification.

Le [plan métier détaillé](../produit/04-plan-metier.md) fait référence pour les prochains développements. Les échéances et relances Temporal sont reportées au backlog. L’architecture événementielle accompagne chaque besoin concret avec l’outbox et Kafka existants. Les sections datées ci-dessous conservent l’historique des jalons, elles ne remplacent pas cet ordre actif.

## Trajectoire générale

| Phase | Livrable concret | Sortie de phase |
| --- | --- | --- |
| 0 : Discovery | Entretiens, cartographie des parcours, périmètre MVP | Hypothèses métier validées |
| 1 : Fondations | Monorepo, CI, IAM, modèle de données, environnement local | Un utilisateur peut ouvrir une session autorisée |
| 2 : Cycle d'audit | Missions, contrôles, preuves, constats, actions | Un audit bout-en-bout démontrable |
| 3 : Fiabilisation | Outbox, événements, notifications, tests de contrats | Pas de perte silencieuse d'événements critiques |
| 4 : Production interne | Kubernetes, GitOps, observabilité, sauvegarde | Déploiement reproductible et restauration testée |
| 5 : Intelligence métier | Tableaux de bord, automatisation de contrôles | Exceptions interprétables, traçables |
| 6 : Différenciation | Audit Replay, règles versionnées | Rejeu sans effets de bord, résultats comparables |

## Première tranche technique

1. Initialiser Git, parent Maven, wrappers et CI de compilation/tests.
2. Livrer `audit-service`, sa base Flyway, Keycloak et la gateway sous Compose.
3. Vérifier création/lecture d’une mission avec autorisations positives et négatives.
4. Ajouter un deuxième service, outbox Kafka et test de redélivrance.
5. Introduire Temporal avec validation humaine, timeout et reprise du worker.
6. Déployer la tranche sur kind/Helm via GitOps et diagnostiquer un appel avec métriques, logs et traces.

Chaque étape enrichit la documentation et distingue implémentation et vérification.

## Definition of Done globale

- Fonctionnalité répond aux critères d'acceptation, avec autorisations testées.
- Tests unitaires et d'intégration pertinents, contrats compatibles.
- Traces/logs/mesures présents sur les flux critiques.
- Documentation technique et métier mise à jour.
- Gestion des erreurs, migrations et procédure de rollback considérées.

## Risques projet

| Risque | Réponse |
| --- | --- |
| Surarchitecture prématurée | Bounded contexts clairs, extraction incrémentale |
| Fuite de preuves sensibles | ABAC, stockage privé, journal d'accès |
| Incohérence des statuts | Machine à états explicite et opérations idempotentes |
| Retard lié aux outils | Livrer le parcours métier avant la complexité plateforme |
| Règles d'audit mal définies | Ateliers avec utilisateurs et critères d'acceptation |

## Avancement au 2026-10-16

Phase 1 en cours : monorepo local, API missions, migrations, Keycloak, gateway et tests du parcours livrés. Frontend, CI distante et besoins métier détaillés restent ouverts. [Preuves et limites du premier jalon](../technique/04-premier-jalon.md).

## Avancement du jalon 2

Interface Next.js et parcours de mission livrés localement avec Keycloak et BFF. La prochaine tranche distribuée peut introduire un deuxième service et Kafka. Les affectations web, les dates métier, la CI distante et le durcissement de production restent suivis au backlog.

## Avancement du jalon 3

Affectations et participants livrés localement, tests sur deux sessions réussis. Prochaines tranches : valider la CI distante, puis définir le service de notifications et la publication fiable des événements avec outbox.

## Durcissement avant extension distribuée

Comptes SQL, quotas, annuaire et conflits d’édition traités localement. Avant publication : valider HTTPS réel et la CI, puis la charge et les sauvegardes. La limite distribuée reste nécessaire avant plusieurs réplicas.

## Jalon notifications : 2026-10-17

La CI du durcissement est verte sur 69de451. La première tranche de phase 3 est livrée localement : Kafka, outbox, notification-service, interface et reprises vérifiées. La CI de cette nouvelle tranche reste à lancer. La haute disponibilité, la quarantaine supervisée et l’observabilité sont ouvertes. Voir le [guide notifications](../technique/09-notifications-kafka.md).

## Cycle manuel des missions : 2026-10-17

Dates prévues et transitions Brouillon → En cours → Clôturée livrées localement. La validation humaine et Temporal constituent une tranche suivante distincte.

## Préparation de Temporal

Le rôle AUDIT_VALIDATOR et son compte de démonstration sont préparés et vérifiés localement. La désignation du validateur et ses droits sur une mission sont implémentés localement. La soumission et la décision sont maintenant implémentées dans PostgreSQL, avec approbation obligatoire avant clôture. Temporal suit désormais les cycles de validation avec un worker séparé et une outbox transactionnelle. Les échéances et relances restent à ajouter.

## Notification du validateur : 2026-10-19

La soumission est reliée au service de notifications existant via Kafka. Une nouvelle soumission après refus crée une nouvelle notification. Les échéances et relances Temporal restent au backlog. La priorité actuelle est le programme de risques et contrôles d’une mission.

## Refonte ergonomique du 21 octobre 2026

La navigation par onglets, les filtres de missions, le détail des contrôles et l’administration des comptes sont implémentés localement. Voir le [guide de navigation](../technique/24-navigation-administration.md). La prochaine tranche métier reste les constats.

## Hébergement privé : 2026-10-24

La phase 4, Production interne, cible désormais un cluster k3s à un nœud sur un serveur privé de l'utilisateur, selon [ADR-0023](../decisions/ADR-0023-hebergement-prive-k3s.md). Le relevé matériel est vérifié. Aucune installation n'est faite. Les dix phases, de la préparation de l'hôte à l'extension, et leurs critères de sortie sont décrits dans le [guide d'hébergement](../technique/28-hebergement-k3s.md). Cette trajectoire avance en parallèle de l'ordre métier actif et ne le remplace pas.

Le 2026-10-24, la phase 0 a démarré avec la fermeture d'un port d'administration joignable par le tailnet. La phase 1 est implémentée et mesurée localement. Elle révèle que Spring Boot 3.5 n'a plus de correctifs libres : la migration vers Spring Boot 4 devient un prérequis avant d'activer une analyse de vulnérabilités bloquante.

Le 2026-10-25, la migration vers Spring Boot 4 est publiée avec une analyse bloquante. La phase 2 est exécutée : k3s tourne sur le serveur, joignable par Tailscale seulement, et survit à un redémarrage. L'opérateur Tailscale fournit l'entrée HTTPS par le tailnet. La phase 3, couche de données, est faite : PostgreSQL, Kafka et Temporal tournent dans le cluster et survivent à un redémarrage. La phase 4, déploiement de l'application avec Helm, est la suivante. L'ouverture publique sous un domaine personnel est décidée par ADR-0025, par Cloudflare Tunnel. Le 2026-10-26, l'ordre est arrêté : fin de la phase 4 avec les lots D et E, puis sécurité C7, supervision avec alertes, sauvegardes, et enfin ouverture. Le détail figure dans la condition d'ouverture du [guide d'hébergement](../technique/28-hebergement-k3s.md). Le même jour, le lot E clôt la phase 4 : smoke par l'URL HTTPS du tailnet et pannes Kafka et Temporal réussis dans le cluster, mémoire mesurée sans OOM. La sécurité C7, phase 8, est la suivante. Ses lots A, NetworkPolicy en entrée, et B, autorité interne à racine hors ligne selon ADR-0026, sont vérifiés le même jour. Le même jour, l'utilisateur avance la phase 7, sauvegardes, avant le lot C : une restauration vérifiée doit précéder toute bascule en TLS. Les sauvegardes sont tirées par le poste selon ADR-0027. Une restauration isolée est réussie le même jour, avec contrôle d'empreinte des preuves. Le lot C1, PostgreSQL en TLS vérifié, est fait le même jour. Le lot C2, Temporal en mTLS, est fait le même jour après une modification du client Java. Le lot D, Kafka en SASL_SSL avec ACL, est fait le même jour. Le lot E1, Garage en HTTPS selon ADR-0028 et rotation des certificats serveur sans redémarrage, est fait le même jour. Le durcissement de Keycloak est reporté à la phase 10. Le lot E2, rechargement du certificat client Temporal en Java, et la revue des critères de sortie sont les suivants.
