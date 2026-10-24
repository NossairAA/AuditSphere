# Plan métier : audit interne et audit continu

**Mise à jour :** 2026-10-24
**Statut :** risques, contrôles, résultats et preuves vérifiés localement, constats vérifiés et publiés, actions correctives cadrées, tranches suivantes à réaliser

## Objectif

Construire une application professionnelle interne aux entreprises pour gérer les missions d’audit, maîtriser les risques, centraliser les preuves et suivre les actions correctives. Un moteur d’audit continu étendra ce parcours avec des contrôles automatisés sur des sources autorisées, dans l’architecture événementielle existante.

Le socle local comprend les missions, leurs permissions, la validation humaine suivie par Temporal et les notifications Kafka. Il ne constitue pas encore un produit prêt pour la production. Les exigences de sécurité, sauvegarde, exploitation et conservation restent des conditions de mise en service.

## Ordre de livraison

| Ordre | Tranche | Résultat attendu | État |
| --- | --- | --- | --- |
| 1 | Risques et contrôles d’une mission | Le responsable prépare un programme consultable par les auditeurs affectés | Vérifié localement |
| 2 | Exécution et preuves | L’auditeur consigne un résultat et ses justificatifs, avec accès contrôlé et versions conservées | Résultats et ajout de preuves vérifiés localement, conservation à compléter |
| 3 | Constats | Une anomalie documentée devient un constat avec gravité, cause et recommandation | Vérifié localement et par la CI distante, publié dans `main` |
| 4 | Actions correctives | Un responsable suit une action et son échéance, puis un acteur habilité vérifie la correction | Cadré le 2026-10-24, à réaliser |
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

**ID :** F-005, US-005 · **Priorité :** P0 · **Statut :** cadré, implémenté et vérifié localement le 2026-10-24, CI distante réussie sur `92dd3ef`, publié dans `main` par la PR #3
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

- [x] Créer, consulter, modifier et supprimer un brouillon de constat depuis la mission.
- [x] Lier un constat à plusieurs contrôles de la même mission et refuser un contrôle d’une autre mission.
- [x] Refuser la soumission sans cause, sans recommandation ou sans contrôle lié Non conforme.
- [x] Valider, renvoyer avec commentaire, rejeter avec motif et rouvrir avec motif, selon les permissions.
- [x] Refuser toute décision à la personne qui a soumis le constat, y compris par dérogation.
- [x] Figer les constats hors statut En cours et refuser la soumission de mission avec un constat Brouillon ou Soumis.
- [x] Détecter les conflits de version et journaliser chaque changement en ajout seul.
- [x] Vérifier les permissions côté API et dans les parcours navigateur, y compris pour un utilisateur extérieur.
- [x] Conserver les missions et données existantes lors de la migration.
- [x] Mettre à jour les contrats, la documentation et les tests ciblés.

### Hors périmètre

Actions correctives, échéances et relances, export du rapport, notifications des décisions, création automatique d’un constat depuis un résultat, référentiel global de constats, pièces jointes propres au constat et cotation calculée. Les notifications de soumission et de décision sont les premiers candidats après la tranche.

### Implémentation

Le découpage technique, le modèle de données, les étapes et leurs vérifications sont décrits dans le [guide technique](../technique/26-constats.md). Le choix du service hébergeant les constats fait l’objet d’[ADR-0021](../decisions/ADR-0021-constats-audit-service.md), accepté le 2026-10-24.

### Questions métier ouvertes

- Libellés et définitions des quatre niveaux de gravité, à confirmer avec les utilisateurs métier.
- Validation des constats par le responsable propriétaire ou par le validateur désigné. Le responsable est retenu par défaut, le validateur conserve la revue de la mission entière.
- Format définitif de la référence lisible et conservation de la numérotation après suppression d’un brouillon.
- Droit de l’audité à consulter ou commenter un constat avant validation, prévu avec le rôle audité.

## Quatrième tranche : actions correctives

**ID :** F-006, US-006 et US-007 · **Priorité :** P0 · **Statut :** cadré le 2026-10-24, découpage accepté par ADR-0022, lot 1 du socle technique vérifié localement, fonctionnalités non implémentées
**Responsable produit :** à définir

### Problème utilisateur et résultat attendu

Un constat validé décrit un écart et une recommandation. Il ne garantit pas que l’écart sera corrigé. Le responsable de mission doit traduire chaque constat émis en plan d’action. Chaque action est confiée à un responsable métier avec une échéance. La correction doit ensuite être justifiée, vérifiée par l’équipe d’audit puis clôturée par le validateur. Ce suivi continue après la clôture de la mission, souvent pendant plusieurs mois.

### Point de départ du suivi

- Le suivi d’un constat commence à l’approbation de la mission, par décision du validateur ou par la dérogation APPROVE du superadministrateur. Seuls les constats Validé sont émis. Un constat Rejeté ne donne lieu à aucun suivi.
- Avant l’approbation, un constat validé peut encore être rouvert. Démarrer le suivi plus tôt créerait des actions sur un texte susceptible de changer.
- Le suivi conserve une copie du constat à la date d’émission : référence, titre, gravité, condition observée, cause et recommandation. Le constat d’origine reste la référence dans la mission.
- La clôture de la mission ne fige pas les actions correctives.
- Une réouverture par dérogation d’une mission approuvée ou clôturée suspend les suivis de cette mission. Ils redeviennent actifs à la nouvelle approbation, avec la copie mise à jour. Un constat qui n’est plus Validé à ce moment passe au statut Retiré, ses actions restent consultables en lecture seule. La suppression d’une telle mission retire tous ses suivis.

### Contenu d’une action corrective

- Une action appartient au suivi d’un constat. Elle porte une référence attribuée par le serveur, unique dans le constat, affichée sous la forme C-01.A1.
- Champs obligatoires : description de l’action attendue, responsable de l’action et échéance.
- Bornes : description de 2000 caractères, avancement et commentaires de 2000 caractères, 20 actions par constat. L’échéance est comprise entre la date du jour et cinq ans.
- Le responsable de l’action est un compte actif de l’annuaire disposant du nouveau rôle AUDITEE, l’audité. Il ne fait pas partie de l’équipe d’audit de la mission : ni propriétaire, ni auditeur affecté, ni validateur.
- Justificatifs : PDF, JPEG ou PNG, 10 Mio par fichier, 10 fichiers par action et 100 Mio par mission, comme les preuves de contrôle. Ils restent distincts des preuves de contrôle.
- Une action est en retard lorsque son échéance est dépassée et qu’elle est À faire ou En cours. Le retard est calculé à la lecture, ce n’est pas un statut. L’échéance est une date sans heure, évaluée dans le fuseau Europe/Paris par défaut.

### Cycle de vie d’une action

`À faire → En cours → Réalisée → Vérifiée`, avec le renvoi `Réalisée → En cours` et l’issue `Annulée`.

- Le responsable de l’action renseigne l’avancement par des commentaires datés. Le premier avancement fait passer l’action En cours.
- Déclarer l’action Réalisée exige un commentaire de réalisation et au moins un justificatif.
- Un auditeur affecté à la mission ou le responsable de mission vérifie l’action Réalisée. Il la passe Vérifiée, ou la renvoie En cours avec un commentaire obligatoire.
- Le responsable de mission peut modifier la description et changer le responsable d’une action À faire ou En cours. Le changement de responsable retire immédiatement l’accès au précédent.
- Le responsable de mission peut reporter l’échéance avec un motif. Chaque report est conservé et compté. Le responsable de l’action ne modifie ni la description ni l’échéance.
- Le responsable de mission peut annuler une action non vérifiée avec un motif. Une action annulée reste consultable.
- Le responsable de mission peut rouvrir une action Vérifiée avec un motif, tant que le suivi n’est ni en Vérification ni Clôturé. Elle revient En cours.

### Cycle de vie du suivi d’un constat

`À planifier → Plan d’action en cours → Vérification → Clôturé`, avec le renvoi `Vérification → Plan d’action en cours`.

- À planifier : constat émis sans action active. Annuler toutes les actions ramène le suivi à cet état.
- Plan d’action en cours : au moins une action non annulée.
- Le responsable de mission demande la clôture lorsque toutes les actions non annulées sont Vérifiée, avec au moins une action Vérifiée. Le suivi passe en Vérification et ses actions sont figées.
- Le validateur désigné de la mission clôture le suivi, ou le renvoie avec un commentaire obligatoire. Le renvoi rétablit les modifications du responsable de mission. Clôturé est définitif dans cette tranche.
- Clore un constat sans action, par acceptation du risque, n’est pas possible dans cette tranche.
- Suspendu et Retiré sont réservés aux réouvertures et suppressions de mission décrites plus haut.

### Permissions

| Acteur | Droits |
| --- | --- |
| Responsable propriétaire de la mission, rôle AUDIT_MANAGER | Consulter les suivis, créer, modifier, réaffecter, reporter, annuler et rouvrir les actions, vérifier, demander la clôture |
| Auditeur affecté à la mission, rôle AUDITOR | Consulter les suivis de la mission, vérifier ou renvoyer une action Réalisée |
| Validateur désigné, rôle AUDIT_VALIDATOR | Consulter les suivis de la mission, clôturer ou renvoyer un suivi en Vérification |
| Responsable de l’action, rôle AUDITEE | Consulter ses actions et la copie du constat associé, renseigner l’avancement, joindre ou retirer ses justificatifs avant réalisation, déclarer l’action Réalisée |
| Superadministrateur | Actions du responsable de mission, de l’auditeur et du validateur par dérogation tracée, selon ADR-0014 |
| Autres utilisateurs | Aucun accès, le suivi reste introuvable |

- Le responsable d’une action ne peut ni la vérifier ni clôturer le suivi, quel que soit son rôle.
- La personne qui a demandé la clôture d’un suivi ne peut pas le clôturer, même par dérogation.
- L’audité ne voit ni les autres actions, ni le reste de la mission, ni les preuves de contrôle.
- L’équipe prise en compte est celle de la mission à l’approbation. Les affectations sont déjà figées après approbation.
- Un compte révoqué perd l’accès immédiatement. Ses actions restent affichées avec un responsable inactif, le responsable de mission doit les réaffecter.
- Les droits sont vérifiés côté API à chaque requête. L’interface masque les actions interdites mais ne constitue pas une protection.

### Traçabilité

- Chaque création, modification, réaffectation, report, avancement, ajout ou retrait de justificatif, transition d’action et transition de suivi est journalisé avec acteur, date, version, motif, valeurs avant et après et indicateur de dérogation.
- Ce journal est en ajout seul pour le compte applicatif. L’historique est consultable sur la fiche de l’action et du suivi.
- Chaque justificatif conserve son empreinte SHA-256, son auteur et sa date. Un justificatif n’est plus retirable après la déclaration Réalisée.
- Une modification concurrente est détectée par version et retourne un conflit.
- Les logs techniques, les métriques et les messages Kafka ne contiennent ni les textes des constats, ni ceux des actions.

### Parcours nominal et erreurs

1. Le validateur approuve une mission dont le constat C-01 est Validé. Le suivi de C-01 apparaît À planifier dans l’onglet Suivi des actions.
2. Le responsable de mission crée l’action C-01.A1, choisit un audité et une échéance. L’audité reçoit une notification.
3. L’audité ouvre Mes actions, renseigne l’avancement, joint un justificatif et déclare l’action Réalisée.
4. Un auditeur affecté renvoie l’action avec un commentaire. L’audité complète puis la déclare à nouveau Réalisée. L’auditeur la vérifie.
5. Le responsable de mission demande la clôture. Le validateur clôture le suivi.
6. L’API refuse un accès hors périmètre, un responsable sans rôle AUDITEE ou membre de l’équipe, une réalisation sans justificatif, une vérification par le responsable de l’action, une clôture par la personne qui l’a demandée, une écriture sur un suivi suspendu, clôturé ou retiré et une version périmée. L’interface explique l’erreur sans annoncer une sauvegarde réussie.

### Critères d’acceptation

- [ ] Créer un suivi pour chaque constat Validé à l’approbation de la mission, aucun pour un constat Rejeté, sans doublon après redélivrance.
- [ ] Créer, modifier, réaffecter, reporter, annuler et rouvrir une action selon les permissions.
- [ ] Refuser un responsable d’action sans rôle AUDITEE ou membre de l’équipe d’audit.
- [ ] Renseigner l’avancement, joindre des justificatifs bornés et refuser une réalisation sans justificatif.
- [ ] Vérifier ou renvoyer une action, refuser la vérification au responsable de l’action.
- [ ] Demander la clôture, clôturer ou renvoyer, refuser la clôture à la personne qui l’a demandée.
- [ ] Signaler les actions en retard sans modifier leur statut.
- [ ] Limiter l’audité à ses propres actions et refuser l’accès à un utilisateur extérieur.
- [ ] Suspendre les suivis à la réouverture de mission et les réconcilier à la nouvelle approbation.
- [ ] Notifier l’audité de l’action qui lui est confiée.
- [ ] Détecter les conflits de version et journaliser chaque changement en ajout seul.
- [ ] Conserver les missions et constats existants lors des migrations.
- [ ] Mettre à jour les contrats, la documentation et les tests ciblés.

### Hors périmètre

Relances et escalades automatiques des retards (US-009, Temporal), proposition du plan d’action par l’audité, demande de report par l’audité, acceptation formelle du risque, actions communes à plusieurs constats, export du rapport (US-008), indicateurs consolidés (US-010), notifications autres que l’affectation d’une action et rattachement des audités à une entité.

### Implémentation proposée

Le découpage technique, le modèle de données et les lots sont décrits dans le [guide technique](../technique/27-actions-correctives.md). Le service dédié et la synchronisation avec audit-service font l’objet d’[ADR-0022](../decisions/ADR-0022-actions-correctives-service-dedie.md), accepté le 2026-10-24.

### Questions métier ouvertes

- Clôture du suivi par le validateur désigné, retenue d’après US-007, ou par le responsable de mission.
- Plan d’action proposé par l’audité puis accepté par le responsable, plutôt que saisi par le responsable.
- Justificatif fichier obligatoire pour toute action, ou commentaire suffisant pour certaines actions organisationnelles.
- Acceptation formelle du risque comme issue alternative à une action.
- Libellé du rôle AUDITEE et rattachement des audités à une direction ou une filiale.
- Durée de conservation des suivis et des justificatifs après clôture.
- Fuseau horaire de référence de l’échéance, Europe/Paris retenu par défaut.

## Extension événementielle

Conserver Spring Boot, les microservices existants, Kafka et l’outbox transactionnelle. Ajouter un événement lorsqu’un consommateur ou un traitement asynchrone a un besoin concret, sans transformer chaque modification d’écran en message.

Événements candidats, non encore implémentés : contrôle terminé, anomalie détectée, constat ouvert et action en retard. Chaque contrat devra préciser identifiant, version, source et corrélation. La publication suivra le commit métier via outbox, les consommateurs supporteront les doublons. Ne pas publier le contenu des preuves ou des secrets dans Kafka. La tranche actions correctives propose l’émission des constats à l’approbation et l’affectation d’une action, sans texte de constat dans les messages, voir ADR-0022.

Temporal pourra orchestrer les traitements longs et les échéances. Les services resteront propriétaires de leurs données. Tout nouveau découpage de service sera justifié dans un ADR avant réalisation.

## Première expérimentation d’audit continu

Après les constats et actions, choisir une source et une règle avec un utilisateur métier. Hypothèse de départ : importer un jeu de factures de test et détecter des doublons potentiels. Ce choix reste à confirmer, il ne suppose aucun accès à un ERP réel.

Conserver la version de règle, la référence du jeu de données, l’identifiant d’exécution, les dates, le statut, les résultats et les références justificatives. Distinguer un échec d’exécution d’un contrôle sans anomalie. Vérifier qu’une reprise ne crée pas plusieurs fois la même anomalie pour une exécution donnée.

Commencer par une exécution à la demande, puis ajouter une planification. Une anomalie doit être examinée avant de devenir un constat officiel. Aucun constat ni action n’est clôturé automatiquement. Audit Replay et la comparaison de versions restent une extension ultérieure.

## Modalité de travail

Livrer une tranche à la fois avec migration, API, interface, permissions, tests ciblés et documentation. Définir les données et transitions de la tranche suivante après validation du parcours précédent. La première tranche est livrée localement. Les étapes suivantes décrivent le plan, pas des fonctionnalités déjà livrées.
