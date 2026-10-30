package com.auditsphere.notification;

import java.time.Instant;
import java.util.UUID;

record AssignmentEvent(
    UUID eventId,
    String eventType,
    int eventVersion,
    UUID missionId,
    UUID actionId,
    String recipientSubject,
    long aggregateVersion,
    Instant occurredAt) {
  void validate() {
    if (eventId == null
        || missionId == null
        || occurredAt == null
        || !("audit.mission.assigned.v1".equals(eventType)
            || "audit.mission.review-requested.v1".equals(eventType)
            || "audit.action.assigned.v1".equals(eventType))
        // Only an action assignment names an action, and it always does.
        || ("audit.action.assigned.v1".equals(eventType)) != (actionId != null)
        || eventVersion != 1
        || recipientSubject == null
        || recipientSubject.isBlank()
        || recipientSubject.length() > 255
        || aggregateVersion < 0) throw new IllegalArgumentException("Invalid assignment event");
  }
}
