# Plan métier : audit interne et audit continu

**Mise à jour :** 2026-10-24
**Statut :** risques, contrôles, résultats et preuves vérifiés localement, tranche constats cadrée, tranches suivantes à réaliser

## Objectif

Construire une application professionnelle interne aux entreprises pour gérer les missions d’audit, maîtriser les risques, centraliser les preuves et suivre les actions correctives. Un moteur d’audit continu étendra ce parcours avec des contrôles automatisés sur des sources autorisées, dans l’architecture événementielle existante.

Le socle local comprend les missions, leurs permissions, la validation humaine suivie par Temporal et les notifications Kafka. Il ne constitue pas encore un produit prêt pour la production. Les exigences de sécurité, sauvegarde, exploitation et conservation restent des conditions de mise en service.

## Ordre de livraison

| Ordre | Tranche | Résultat attendu | État |
| --- | --- | --- | --- |
| 1 | Risques et contrôles d’une mission | Le responsable prépare un programme consultable par les auditeurs affectés | Vérifié localement |
| 2 | Exécution et preuves | L’auditeur consigne un résultat et ses justificatifs, avec accès contrôlé et versions conservées | Résultats et ajout de preuves vérifiés localement, conservation à compléter |
| 3 | Constats | Une anomalie documentée devient un constat avec gravité, cause et recommandation | API, blocage de soumission et BFF livrés, interface à réaliser |
| 4 | Actions correctives | Un responsable suit une action et son échéance, puis un acteur habilité vérifie la correction | À réaliser |
| 5 | Audit continu | Une règle versionnée s’exécute sur une source autorisée et produit des anomalies reproductibles | À réaliser après le parcours manuel |

Parcours cible : Mission → Risques → Contrôles → Preuves → Constats → Actions correctives → Vérification.

Les échéances et relances Temporal ne sont plus la prochaine priorité de développement. Elles restent au backlog. La publication des notifications locales est une tâche de livraison distincte. Kubernetes et les optimisations de plateforme ne remplacent pas cette priorité métier, les protections nécessaires aux nouvelles données restent incluses dans chaque tranche.

## Première tranche : risques et contrôles

**ID :** F-003 · **Priorité :** P0 · **Statut :** implémenté et vérifié localement
**Responsable produit :** à définir

### Problème utilisateur et résultat attendu

Le responsable doit expliquer ce qui doit être contrôlé et pourquoi. La section Programme d’audit de la fiche de mission permet de préparer ce programme et de le partager avec les auditeurs affectés.

### Règles métier de la première livraison

- Un risque appartient à une mission et possède un intitulé, une description, une probabilité et un impact.
- Un contrôle appartient à cette même mission et possède un objectif et une procédure de vérification.
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

- [x] Créer, consulter et modifier un risque et un contrôle depuis la mission.
- [x] Relier un contrôle à plusieurs risques de la même mission.
- [x] Vérifier les permissions côté API et dans les parcours navigateur.
- [x] Refuser les références entre missions et les changements pendant une revue.
- [x] Détecter les conflits de version et tracer les modifications sensibles.
- [x] Conserver les missions et données existantes lors de la migration.
- [x] Mettre à jour les contrats, la documentation et les tests ciblés.

### Implémentation technique

Cette tranche utilise audit-service, sa base PostgreSQL et l’interface Next.js existante. Les ressources API sont rattachées à une mission et réutilisent sa version. Voir le [guide technique](../technique/21-programme-audit.md) pour les limites et les vérifications. Aucun nouveau microservice n’est nécessaire pour chaque objet métier.

La trace des modifications appartient au domaine métier. Elle ne doit pas dépendre uniquement des logs techniques ou de Kafka. Les métriques et logs doivent permettre de diagnostiquer les échecs sans exposer les descriptions sensibles.

### Questions métier ouvertes

Les échelles ordinales de 1 à 5 sont implémentées, leurs libellés restent à confirmer avec les utilisateurs métier. La politique de suppression ou d’archivage et les règles de complétude avant soumission restent à préciser. Une éventuelle cotation calculée devra expliciter sa formule et ses seuils avant implémentation.

## Troisième tranche : constats

**ID :** F-005, US-005 · **Priorité :** P0 · **Statut :** cadré le 2026-10-24, API et blocage de la soumission de mission vérifiés par tests Java, BFF et contrat livrés, interface à réaliser
**Responsable produit :** à définir

### Problème utilisateur et résultat attendu

Un résultat Non conforme signale un écart, mais ne dit ni pourquoi il existe, ni ce qu’il faut changer. L’auditeur doit transformer cet écart en constat argumenté. Le responsable de mission doit le relire avant qu’il entre dans la revue de mission puis dans le futur rapport. La section Constats de la fiche de mission rassemble ce travail.

### Contenu d’un constat

- Un constat appartient à une mission. Il porte une référence lisible attribuée par le serveur, par exemple C-01, unique dans la mission.
- Champs obligatoires dès le brouillon : titre, condition observée, gravité et au moins un contrôle lié.
- Champs obligatoires à la soumission : cause et recommandation. Le critère attendu et la conséquence restent facultatifs.
- Bornes : titre de 200 caractères, autres textes de 4000 caractères, 100 constats et 20 contrôles liés par constat au maximum.
- La gravité suit quatre niveaux : Faible, Modérée, Élevée et Critique. Elle est choisie par l’auteur et revue par le responsable. Aucun calcul automatique à partir de la probabilité et de l’impact des risques.
- Les contrôles liés appartiennent à la même mission. Les risques associés sont déduits de ces contrôles et affichés en lecture seule. Les preuves restent attachées aux contrôles, le constat ne duplique aucun fichier.

### Cycle de vie de la tranche

`Brouillon → Soumis → Validé`, avec deux issues depuis Soumis : `Renvoyé en brouillon` et `Rejeté`.

- Soumettre exige les champs obligatoires et au moins un contrôle lié dont le résultat courant est Non conforme. Un contrôle sans résultat ou conforme est signalé comme incohérent.
- Le renvoi en brouillon et le rejet exigent un commentaire. Un constat rejeté reste consultable avec son motif, il n’est ni modifiable ni supprimé.
- L’auteur ou un autre auditeur affecté peut retirer une soumission tant qu’aucune décision n’est prise.
- Le responsable peut rouvrir un constat validé avec un motif, uniquement lorsque la mission est En cours. Le constat revient en brouillon, sa validation précédente reste dans l’historique.
- Seul un brouillon jamais soumis peut être supprimé. Les autres constats sont conservés.
- Les états Plan d’action en cours, Vérification et Clôturé du parcours cible appartiennent à la tranche actions correctives.

### Permissions

| Acteur | Droits |
| --- | --- |
| Auditeur affecté, rôle AUDITOR | Créer, modifier un brouillon, soumettre, retirer une soumission, supprimer un brouillon jamais soumis |
| Responsable propriétaire, rôle AUDIT_MANAGER | Consulter, valider, renvoyer, rejeter, rouvrir un constat validé |
| Validateur désigné | Consulter tous les constats pendant la revue de mission, sans décision sur un constat isolé |
| Superadministrateur | Actions de l’auditeur et du responsable par dérogation tracée, selon ADR-0014 |
| Autres utilisateurs | Aucun accès, la mission reste introuvable |

- La personne qui a soumis un constat ne peut pas le valider, le renvoyer ni le rejeter, même avec un rôle de responsable ou de superadministrateur.
- Les auditeurs affectés travaillent collectivement : chacun peut modifier le brouillon d’un autre. L’auteur de chaque version est conservé.
- Les droits sont vérifiés côté API à chaque requête. L’interface masque les actions interdites mais ne constitue pas une protection.

### Interaction avec la mission

- Toutes les écritures sur les constats sont autorisées uniquement lorsque la mission est En cours. Les constats sont figés pendant la validation de la mission, après approbation et après clôture.
- La soumission de la mission au validateur est refusée tant qu’un constat est Brouillon ou Soumis. Chaque constat doit être validé ou rejeté avant la revue de mission. Les dérogations d’approbation et de clôture du superadministrateur suivent la même règle.
- Une demande de corrections ou un retrait de soumission de la mission rétablit les écritures sur les constats.
- Modifier un contrôle efface déjà son résultat courant. Un constat validé lié à ce contrôle reste validé, l’interface signale le contrôle à réexécuter au responsable.
- Un retrait d’affectation ou une suppression de mission retire l’accès aux constats.

### Traçabilité

- Chaque création, modification, transition et suppression de brouillon est journalisée avec acteur, date, version, motif et valeurs avant et après.
- Ce journal est en ajout seul pour le compte applicatif. L’historique des décisions est consultable sur la fiche du constat.
- Une modification concurrente est détectée par version et retourne un conflit, sans écraser la saisie d’un autre auditeur.
- Les logs techniques et métriques ne contiennent pas les textes des constats.

### Parcours nominal et erreurs

1. Un auditeur affecté ouvre une mission En cours puis Constats.
2. Il crée un brouillon, choisit la gravité et lie un contrôle Non conforme.
3. Il complète cause et recommandation puis soumet.
4. Le responsable relit le constat et le valide, ou le renvoie avec un commentaire.
5. Quand tous les constats sont validés ou rejetés, la mission peut être soumise au validateur, qui les consulte pendant sa revue.
6. L’API refuse un accès hors mission, un contrôle d’une autre mission, une soumission incomplète, une décision par l’auteur de la soumission, une écriture interdite par le statut ou une version périmée. L’interface explique l’erreur sans annoncer une sauvegarde réussie.

### Critères d’acceptation

- [ ] Créer, consulter, modifier et supprimer un brouillon de constat depuis la mission.
- [x] Lier un constat à plusieurs contrôles de la même mission et refuser un contrôle d’une autre mission.
- [x] Refuser la soumission sans cause, sans recommandation ou sans contrôle lié Non conforme.
- [ ] Valider, renvoyer avec commentaire, rejeter avec motif et rouvrir avec motif, selon les permissions.
- [x] Refuser toute décision à la personne qui a soumis le constat, y compris par dérogation.
- [x] Figer les constats hors statut En cours et refuser la soumission de mission avec un constat Brouillon ou Soumis.
- [x] Détecter les conflits de version et journaliser chaque changement en ajout seul.
- [ ] Vérifier les permissions côté API et dans les parcours navigateur, y compris pour un utilisateur extérieur.
- [ ] Conserver les missions et données existantes lors de la migration.
- [ ] Mettre à jour les contrats, la documentation et les tests ciblés.

### Hors périmètre

Actions correctives, échéances et relances, export du rapport, notifications des décisions, création automatique d’un constat depuis un résultat, référentiel global de constats, pièces jointes propres au constat et cotation calculée. Les notifications de soumission et de décision sont les premiers candidats après la tranche.

### Implémentation proposée

Le découpage technique, le modèle de données et les étapes sont décrits dans le [guide technique](../technique/26-constats.md). Le choix du service hébergeant les constats fait l’objet d’[ADR-0021](../decisions/ADR-0021-constats-audit-service.md), accepté le 2026-10-24.

### Questions métier ouvertes

- Libellés et définitions des quatre niveaux de gravité, à confirmer avec les utilisateurs métier.
- Validation des constats par le responsable propriétaire ou par le validateur désigné. Le responsable est retenu par défaut, le validateur conserve la revue de la mission entière.
- Format définitif de la référence lisible et conservation de la numérotation après suppression d’un brouillon.
- Droit de l’audité à consulter ou commenter un constat avant validation, prévu avec le rôle audité.

## Extension événementielle

Conserver Spring Boot, les microservices existants, Kafka et l’outbox transactionnelle. Ajouter un événement lorsqu’un consommateur ou un traitement asynchrone a un besoin concret, sans transformer chaque modification d’écran en message.

Événements candidats, non encore implémentés : contrôle terminé, anomalie détectée, constat ouvert et action en retard. Chaque contrat devra préciser identifiant, version, source et corrélation. La publication suivra le commit métier via outbox, les consommateurs supporteront les doublons. Ne pas publier le contenu des preuves ou des secrets dans Kafka.

Temporal pourra orchestrer les traitements longs et les échéances. Les services resteront propriétaires de leurs données. Tout nouveau découpage de service sera justifié dans un ADR avant réalisation.

## Première expérimentation d’audit continu

Après les constats et actions, choisir une source et une règle avec un utilisateur métier. Hypothèse de départ : importer un jeu de factures de test et détecter des doublons potentiels. Ce choix reste à confirmer, il ne suppose aucun accès à un ERP réel.

Conserver la version de règle, la référence du jeu de données, l’identifiant d’exécution, les dates, le statut, les résultats et les références justificatives. Distinguer un échec d’exécution d’un contrôle sans anomalie. Vérifier qu’une reprise ne crée pas plusieurs fois la même anomalie pour une exécution donnée.

Commencer par une exécution à la demande, puis ajouter une planification. Une anomalie doit être examinée avant de devenir un constat officiel. Aucun constat ni action n’est clôturé automatiquement. Audit Replay et la comparaison de versions restent une extension ultérieure.

## Modalité de travail

Livrer une tranche à la fois avec migration, API, interface, permissions, tests ciblés et documentation. Définir les données et transitions de la tranche suivante après validation du parcours précédent. La première tranche est livrée localement. Les étapes suivantes décrivent le plan, pas des fonctionnalités déjà livrées.
