# 02 : Exigences et périmètre

**Statut :** Proposé

## Fonctionnalités MVP (P0)

- **F-001** : authentification d'entreprise, rôles et autorisations par mission.
- **F-002** : création, affectation, planification et statut des missions.
- **F-003** : référentiel de risques, contrôles et programmes de test.
- **F-004** : téléversement et versionnement des preuves avec liens vers les contrôles.
- **F-005** : création des constats, gravité, causes et recommandations.
- **F-006** : plan d'action, responsables, délais, revues et clôture contrôlée.
- **F-007** : historique des événements métier sensibles et export d'un rapport simple.

## V1 (P1)

- Notifications et escalades, workflows de validation.
- Tableaux de bord des échéances et des risques.
- Recherche documentaire avec filtrage des accès.
- Modèles de missions et questionnaires réutilisables.
- API et connecteurs en lecture seule vers systèmes autorisés.

## Vision ultérieure (P2)

- Contrôles récurrents automatisés, moteur de règles versionnées, exécutions planifiées.
- Audit Replay, comparaison des règles, résultats reproductibles.
- Multi-entités avancé, analytics à forte volumétrie, extraction de documents assistée.

## Hors MVP

- ERP complet, comptabilité, paiement, GRC universelle et certification automatique.
- Prise de décision autonome qui clôture un constat sans revue humaine.
- IA générative traitant des preuves sensibles sans politique de confidentialité validée.

## Exigences non fonctionnelles initiales (à valider)

| Catégorie | Attente |
| --- | --- |
| Sécurité | SSO, accès au moindre privilège, chiffrement TLS, stockage des secrets sécurisé |
| Traçabilité | Acteur, date, action, cible, raison et version des objets sensibles |
| Fiabilité | Opérations critiques idempotentes, sauvegardes et restaurations testées |
| Performance | Mesurer p95/p99 sur les principaux parcours avant de fixer des objectifs |
| Scalabilité | Traitements lourds asynchrones, montée en charge horizontale si mesurée |
| Exploitation | Logs corrélés, traces distribuées, métriques et alertes actionnables |
| Conservation | Durées, suppression et accès définis selon politique interne applicable |
| Accessibilité | Interfaces utilisables au clavier et formulaires accessibles |

## Exemple de critères d'acceptation : F-006

- Seul un rôle autorisé peut affecter ou modifier le plan d'action.
- Un changement d'échéance laisse une trace avec auteur, date et justification.
- Une action « terminée » n'est « clôturée » qu'après validation autorisée.
- Un événement dupliqué ne produit ni double notification ni double clôture.
