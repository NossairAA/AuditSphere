# ADR-0011 : Première intégration Temporal

**Date :** 2026-10-18
**Statut :** accepté

## Décision

Chaque soumission crée un identifiant de cycle et un message START dans review_workflow_outbox, dans la transaction de la mission. Chaque décision autorisée crée un message DECISION dans la même transaction que la décision métier. Le workflow Temporal mission-review-{cycleId} attend un signal et termine avec APPROVED ou REJECTED. Une nouvelle soumission après refus crée un autre cycle.

PostgreSQL reste la source de vérité des permissions et des transitions. Temporal suit durablement l’attente et la décision, mais ne réalise pas encore les transitions SQL via des Activities. Une réponse HTTP réussie signifie que la transaction métier est enregistrée, pas que Temporal l’a déjà traitée. Cette séparation permet de conserver le parcours pendant une interruption de Temporal.

Le publieur réserve un message avec bail puis libère la transaction avant les appels réseau. Une décision ne part qu’après l’accusé de réception du démarrage. Les démarrages rejoués utilisent un identifiant stable et REJECT_DUPLICATE. Les signaux doublés ne changent pas la première décision. Si le workflow est terminé, le publieur ne confirme le doublon qu’après comparaison du résultat conservé.

Le worker réutilise l’image audit-service avec WORKFLOW_WORKER_ONLY. Il ne démarre ni serveur HTTP ni connexion PostgreSQL et ne reçoit pas les secrets SQL ou Keycloak. Il utilise la file audit-review-v1. Seuls les identifiants de mission et de cycle et la décision sont transmis à Temporal, sans commentaire, nom ni jeton.

## Développement local

Temporal CLI 1.9.1 sert un environnement start-dev avec SQLite sur volume persistant, SDK Java 1.40.0. Le port gRPC reste dans Docker et l’interface Web est liée à 127.0.0.1:8233. Ce mode sans authentification ni TLS n’est pas une configuration de production. Le profil HTTPS existant retire aussi le port de cette interface, mais ne transforme pas Temporal en serveur de production.

## Limites

Pas de délai, relance, notification, annulation ou historique métier complet dans cette tranche. Les messages déjà publiés ne sont pas purgés automatiquement. Le namespace local conserve les exécutions terminées 24 heures. Les exécutions ouvertes restent en attente. Une récupération tardive après suppression d’un historique terminé peut nécessiter une intervention, la décision métier restant dans PostgreSQL. La sauvegarde et la restauration cohérentes de Temporal avec PostgreSQL, les politiques de rétention, TLS et les autorisations sont à traiter avant production.

Sources : [guide Java Temporal](https://docs.temporal.io/develop/java), [messages et signaux](https://docs.temporal.io/encyclopedia/workflow-message-passing), [serveur de développement](https://github.com/temporalio/cli/blob/main/README.md).
