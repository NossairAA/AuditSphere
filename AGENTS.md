# AuditSphere : règles de travail

## Orientation du projet
Construire une plateforme d’audit interne pour pratiquer Spring Boot, les microservices, Kafka, la sécurité, les workflows distribués, CI/CD, Kubernetes et l’observabilité. Cette orientation est explicitement demandée par l’utilisateur. La stack de référence est dans `auditsphere-docs/docs/technique/03-stack.md`.

## Documentation à chaque intervention
- Lire les documents concernés avant de modifier le projet.
- Enrichir le document de référence avec le comportement, les choix, les limites et les vérifications effectuées.
- Mettre à jour le backlog, la roadmap si elle change et `auditsphere-docs/CHANGELOG.md`.
- Créer un ADR pour tout arbitrage architectural significatif, ne pas réécrire une décision acceptée sans documenter son remplacement.
- Distinguer proposé, implémenté et vérifié. Ne jamais déclarer un test réussi ou un service déployé sans preuve.
- Conserver la documentation en français et ne jamais y enregistrer de secrets ou de données d’audit réelles.
- Ne pas remplacer l’orientation Spring/microservices par un monolithe ou un backend TypeScript sans demande explicite.

## Style documentaire
- Ne jamais utiliser de tiret cadratin ni de point-virgule dans la documentation. Employer des phrases courtes, des virgules ou des deux-points selon le contexte.
