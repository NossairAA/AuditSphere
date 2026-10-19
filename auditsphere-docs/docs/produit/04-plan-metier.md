# Plan métier : audit interne et audit continu

**Mise à jour :** 2026-10-19
**Statut :** orientation retenue, fonctionnalités ci-dessous à implémenter

## Objectif

Construire une application professionnelle interne aux entreprises pour gérer les missions d’audit, maîtriser les risques, centraliser les preuves et suivre les actions correctives. Un moteur d’audit continu étendra ce parcours avec des contrôles automatisés sur des sources autorisées, dans l’architecture événementielle existante.

Le socle local comprend les missions, leurs permissions, la validation humaine suivie par Temporal et les notifications Kafka. Il ne constitue pas encore un produit prêt pour la production. Les exigences de sécurité, sauvegarde, exploitation et conservation restent des conditions de mise en service.

## Ordre de livraison

| Ordre | Tranche | Résultat attendu | État |
| --- | --- | --- | --- |
| 1 | Risques et contrôles d’une mission | Le responsable prépare un programme consultable par les auditeurs affectés | Prochaine tranche |
| 2 | Exécution et preuves | L’auditeur consigne un résultat et ses justificatifs, avec accès contrôlé et versions conservées | À réaliser |
| 3 | Constats | Une anomalie documentée devient un constat avec gravité, cause et recommandation | À réaliser |
| 4 | Actions correctives | Un responsable suit une action et son échéance, puis un acteur habilité vérifie la correction | À réaliser |
| 5 | Audit continu | Une règle versionnée s’exécute sur une source autorisée et produit des anomalies reproductibles | À réaliser après le parcours manuel |

Parcours cible : Mission → Risques → Contrôles → Preuves → Constats → Actions correctives → Vérification.

Les échéances et relances Temporal ne sont plus la prochaine priorité de développement. Elles restent au backlog. La publication des notifications locales est une tâche de livraison distincte. Kubernetes et les optimisations de plateforme ne remplacent pas cette priorité métier, les protections nécessaires aux nouvelles données restent incluses dans chaque tranche.

## Première tranche : risques et contrôles

**ID :** F-003 · **Priorité :** P0 · **Statut :** à implémenter
**Responsable produit :** à définir

### Problème utilisateur et résultat attendu

Le responsable doit expliquer ce qui doit être contrôlé et pourquoi. L’écran Risques et contrôles d’une mission permettra de préparer ce programme et de le partager avec les auditeurs affectés.

### Règles métier proposées pour la première livraison

- Un risque appartient à une mission et possède un intitulé, une description, une probabilité et un impact.
- Un contrôle appartient à cette même mission et possède un intitulé, une procédure et un résultat attendu.
- Un contrôle couvre un ou plusieurs risques de sa mission. Un lien vers le risque d’une autre mission est refusé côté serveur.
- Le responsable propriétaire prépare et modifie le programme. Les auditeurs affectés le consultent. Le validateur désigné dispose de la lecture nécessaire à la revue, sans droit de responsable.
- Les changements sont autorisés en Brouillon et En cours. Le programme est figé pendant la revue, après approbation et après clôture, en cohérence avec le cycle existant.
- Une modification concurrente est détectée par version. Les modifications sensibles conservent acteur, date et cible.
- La première tranche ne comprend ni exécution de contrôle, ni fichier de preuve, ni référentiel global réutilisable, ni calcul automatique d’un score de risque.

### Parcours nominal et erreurs

1. Le responsable ouvre une mission puis Risques et contrôles.
2. Il ajoute un risque avec sa probabilité et son impact.
3. Il crée un contrôle et le relie à ce risque.
4. Un auditeur affecté consulte le programme.
5. L’API refuse un accès hors mission, un lien entre missions, une modification interdite par le statut ou une version périmée. L’interface explique l’erreur sans annoncer une sauvegarde réussie.

### Critères d’acceptation

- [ ] Créer, consulter et modifier un risque et un contrôle depuis la mission.
- [ ] Relier un contrôle à plusieurs risques de la même mission.
- [ ] Vérifier les permissions côté API et dans les parcours navigateur.
- [ ] Refuser les références entre missions et les changements pendant une revue.
- [ ] Détecter les conflits de version et tracer les modifications sensibles.
- [ ] Conserver les missions et données existantes lors de la migration.
- [ ] Mettre à jour les contrats, la documentation et les tests ciblés.

### Impact technique prévu

Implémenter cette tranche dans audit-service, sa base PostgreSQL et l’interface Next.js existante. Les ressources API seront rattachées à une mission, leurs contrats exacts seront définis lors de l’implémentation. Réutiliser les contrôles d’accès et de version existants. Aucun nouveau microservice n’est nécessaire pour chaque objet métier.

La trace des modifications appartient au domaine métier. Elle ne doit pas dépendre uniquement des logs techniques ou de Kafka. Les métriques et logs doivent permettre de diagnostiquer les échecs sans exposer les descriptions sensibles.

### Questions métier ouvertes

L’échelle de probabilité et d’impact, les libellés, la politique de suppression ou d’archivage et les règles de complétude avant soumission restent à préciser. Une éventuelle cotation calculée devra expliciter sa formule et ses seuils avant implémentation.

## Extension événementielle

Conserver Spring Boot, les microservices existants, Kafka et l’outbox transactionnelle. Ajouter un événement lorsqu’un consommateur ou un traitement asynchrone a un besoin concret, sans transformer chaque modification d’écran en message.

Événements candidats, non encore implémentés : contrôle terminé, anomalie détectée, constat ouvert et action en retard. Chaque contrat devra préciser identifiant, version, source et corrélation. La publication suivra le commit métier via outbox, les consommateurs supporteront les doublons. Ne pas publier le contenu des preuves ou des secrets dans Kafka.

Temporal pourra orchestrer les traitements longs et les échéances. Les services resteront propriétaires de leurs données. Tout nouveau découpage de service sera justifié dans un ADR avant réalisation.

## Première expérimentation d’audit continu

Après les constats et actions, choisir une source et une règle avec un utilisateur métier. Hypothèse de départ : importer un jeu de factures de test et détecter des doublons potentiels. Ce choix reste à confirmer, il ne suppose aucun accès à un ERP réel.

Conserver la version de règle, la référence du jeu de données, l’identifiant d’exécution, les dates, le statut, les résultats et les références justificatives. Distinguer un échec d’exécution d’un contrôle sans anomalie. Vérifier qu’une reprise ne crée pas plusieurs fois la même anomalie pour une exécution donnée.

Commencer par une exécution à la demande, puis ajouter une planification. Une anomalie doit être examinée avant de devenir un constat officiel. Aucun constat ni action n’est clôturé automatiquement. Audit Replay et la comparaison de versions restent une extension ultérieure.

## Modalité de travail

Livrer une tranche à la fois avec migration, API, interface, permissions, tests ciblés et documentation. Définir les données et transitions de la tranche suivante après validation du parcours précédent. Les listes ci-dessus décrivent le plan, pas des fonctionnalités déjà livrées.
