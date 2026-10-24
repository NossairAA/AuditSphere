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

## Événement livré : affectation de mission

Le topic audit.mission.assigned.v1 est implémenté. audit-service enregistre un événement par nouvel auditeur dans son outbox, puis notification-service crée une notification avec event_id unique. Les deux services possèdent des bases distinctes. Le message contient missionId, recipientSubject, aggregateVersion, occurredAt, eventId, eventType et eventVersion. Les autres événements du catalogue restent proposés. Voir [ADR-0007](../decisions/ADR-0007-notifications-outbox.md) et le [guide de reprise](../technique/09-notifications-kafka.md).

## Événements proposés pour les actions correctives

[ADR-0022](../decisions/ADR-0022-actions-correctives-service-dedie.md), accepté, prévoit trois topics. Les deux topics d’audit-service sont produits depuis le lot 2 de F-006, sans consommateur à ce stade. `audit.action.assigned.v1` reste proposé.

| Topic logique | Producteur | Consommateur | Contenu |
| --- | --- | --- | --- |
| `audit.mission.findings-issued.v1` | Audit | Remediation | Mission et cycle d’émission, sans texte de constat |
| `audit.mission.findings-withdrawn.v1` | Audit | Remediation | Mission, cycle et motif REOPENED ou DELETED |
| `audit.action.assigned.v1` | Remediation | Notifications | Action, suivi, mission et destinataire, sans texte d’action |

remediation-service lit le contenu des constats émis par un appel interne à audit-service, puis le copie dans sa base. `audit.finding.validated.v1` reste proposé pour le reporting, il n’alimente pas le suivi des actions. Détails dans le [guide des actions correctives](../technique/27-actions-correctives.md).
