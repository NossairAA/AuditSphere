# ADR-0002 : Spring Boot et architecture distribuée

**Date :** 2026-10-16  
**Statut :** Accepté pour l’orientation explicitement demandée, choix complémentaires proposés.

## Contexte

AuditSphere doit permettre de pratiquer Spring Boot, microservices, Kafka, sécurité, workflows distribués, CI/CD, Kubernetes et observabilité, en livrant un cas métier concret.

## Décision

Construire des microservices Spring Boot indépendamment déployables, avec données propriétaires, Kafka pour les événements, sécurité OIDC et workflow durable. La [stack de référence](../technique/03-stack.md) recommande Java 21, PostgreSQL, Keycloak, Temporal, Maven, Next.js, GitHub Actions, Helm, Argo CD et OpenTelemetry. Les versions exactes et intégrations sont à valider lors du premier build.

## Options considérées

- Monolithe modulaire TypeScript : plus simple à exploiter initialement, mais ne répond pas à l’objectif technique demandé.
- Microservices Spring Boot : retenus pour travailler les frontières, la cohérence et les pannes distribuées.

## Conséquences

Coût local et opérationnel supérieur, tests d’intégration indispensables, gestion explicite des retries et de l’idempotence. Construire une tranche verticale avant de généraliser tous les services. Kafka et Kubernetes font partie de la trajectoire engagée, leur introduction est progressive. Aucun code ni déploiement n’est livré par cette décision documentaire.
