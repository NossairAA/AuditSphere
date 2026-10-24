# ADR-0021 : constats hébergés par audit-service

**Statut :** Accepté le 2026-10-24
**Date :** 2026-10-24
**Auteur :** équipe AuditSphere

## Contexte

L’architecture cible prévoit un contexte Findings & Actions avec sa propre base. La tranche F-005 ajoute les constats. Leurs règles dépendent fortement de la mission : statut En cours, affectations, responsable propriétaire, verrou de mission et blocage de la soumission au validateur tant qu’un constat est en attente. Programme, résultats et preuves sont déjà dans audit-service.

## Options évaluées

1. Constats dans audit-service, tables et contrôleur dédiés. Avantages : cohérence transactionnelle avec la mission, réutilisation des permissions et du verrou, livraison rapide. Inconvénients : écart avec la vue cible, audit-service grossit.
2. Nouveau findings-service avec projection Kafka des missions et affectations. Avantages : frontière cible, pratique des projections et de la cohérence éventuelle. Inconvénients : un retard de projection autorise une écriture après la soumission de mission ou après un retrait d’affectation, le blocage de soumission devient une coordination entre services.
3. Nouveau findings-service interrogeant audit-service à chaque requête. Avantages : frontière cible, droits à jour. Inconvénients : couplage synchrone fort, pas de verrou commun, disponibilité dépendante des deux services.

## Décision

Retenir l’option 1 pour F-005. Les constats vivent pendant la mission et sont revus dans son cycle, leur cohérence avec la mission prime. Les tables, le contrôleur et le journal restent séparés du code des missions. La seule lecture croisée est le comptage des constats en attente lors de la soumission de mission.

Réexaminer la frontière à la tranche F-006. Les actions correctives survivent à la clôture de mission, ont leurs propres responsables, échéances et relances. Elles constituent le meilleur candidat pour un service dédié, alimenté par un événement de constat validé publié via outbox. Ce futur découpage fera l’objet d’un ADR distinct.

## Conséquences et risques

- Livraison de F-005 sans nouvelle infrastructure ni nouveau déploiement.
- La vue logique cible de l’architecture reste valable mais n’est pas atteinte pour les constats.
- Une extraction ultérieure demandera une migration des données et le remplacement du comptage par une projection ou un appel synchrone.
- Le risque de couplage est limité par l’interdiction de jointures entre le code des missions et celui des constats, hors comptage documenté.

## Références

- [Plan métier, tranche constats](../produit/04-plan-metier.md)
- [Implémentation proposée](../technique/26-constats.md)
- [Architecture cible](../architecture/01-architecture.md)
- [ADR-0002](ADR-0002-stack-spring-distribuee.md), [ADR-0014](ADR-0014-superadmin.md)
