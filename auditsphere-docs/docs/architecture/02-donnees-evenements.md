# 05 : Données, événements et cohérence

**Statut :** Proposé

## Modèle conceptuel initial

- **MissionAudit** : id, titre, périmètre, dates, statut, propriétaire.
- **Affectation** : mission_id, utilisateur_id, rôle, période.
- **Risque** : id, catégorie, description, niveau, propriétaire.
- **Contrôle** : id, risque_id, méthode, fréquence, version.
- **TestContrôle** : mission_id, controle_id, résultat, observations.
- **Preuve** : id, propriétaire métier, classification, métadonnées.
- **VersionPreuve** : preuve_id, version, checksum, emplacement, auteur.
- **Constat** : id, mission_id, gravité, description, statut, version.
- **ActionCorrective** : constat_id, responsable, échéance, statut, validation.
- **AuditLog** : actor_id, action, object_ref, horodatage, correlation_id.

Les liaisons interservices passent par IDs et API/événements, ne pas créer de clés étrangères entre bases de services.

## Catalogue initial d'événements

| Topic logique | Producteur | Consommateurs possibles |
| --- | --- | --- |
| `audit.mission.created.v1` | Audit | Reporting, notifications |
| `audit.evidence.submitted.v1` | Evidence | Audit, notifications |
| `audit.finding.validated.v1` | Findings | Reporting, workflow |
| `audit.action.assigned.v1` | Findings | Notifications |
| `audit.action.closed.v1` | Findings | Reporting, audit |

Exemple de contrat :

```json
{
  "eventId": "uuid",
  "eventType": "audit.finding.validated",
  "eventVersion": 1,
  "occurredAt": "2026-10-16T10:00:00Z",
  "aggregateId": "finding-123",
  "aggregateVersion": 7,
  "correlationId": "corr-abc",
  "tenantId": "org-001",
  "payload": {"missionId": "audit-456", "severity": "HIGH"}
}
```

## Garanties

- **Transactional outbox** pour stocker l'événement dans la transaction métier.
- Livraison **at least once** : consommateurs idempotents par `eventId`.
- Ordre garanti uniquement dans une partition et avec une clé appropriée (ex. `aggregateId`).
- Événements versionnés, tests de compatibilité de schéma.
- Aucune prétention d'`exactly once` de bout en bout sur les actions externes.
- Retrait ou masquage de données sensibles des événements si non indispensables.

## Audit Replay (hors MVP)

Conserver les versions des règles, les jeux de données autorisés et les paramètres d'exécution. Exécuter la simulation en mode sans effet de bord, avec identifiant de campagne, provenance et résultat reproductible.
