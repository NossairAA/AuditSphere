# 03 : Parcours et workflows métier

**Statut :** Proposé

## Mission d'audit

`Brouillon → Planifiée → En cours → En revue → Rapport émis → Clôturée`

Transitions soumises à habilitation, chaque changement significatif est historisé. Les retours `En revue → En cours` et la réouverture doivent être définis par règle métier.

## Constat

`Brouillon → Soumis → Validé → Plan d'action en cours → Vérification → Clôturé`

Branches possibles : `Rejeté`, `Réouvert`, exige une raison documentée.

## Exemple de parcours achats

1. Création de mission par le responsable d'audit.
2. Définition d'un contrôle de validation des achats.
3. Demande de pièces et téléversement de preuves par l'audité.
4. Exécution manuelle du contrôle, exceptions consignées.
5. Création et validation d'un constat.
6. Affectation d'un plan d'action avec échéance.
7. Dépôt des preuves correctives et vérification par l'auditeur.
8. Clôture tracée et intégration au rapport.

## Invariants métier à protéger

- Une preuve n'est jamais modifiée silencieusement : nouvelle version si modification.
- Le constat validé possède un historique non réécrit.
- La clôture d'un constat nécessite les autorisations et justifications prévues.
- Une mission ne doit pas exposer ses preuves à une personne non autorisée.
