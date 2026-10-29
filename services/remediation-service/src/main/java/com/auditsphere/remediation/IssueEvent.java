package com.auditsphere.remediation;

import java.time.Instant;
import java.util.UUID;

/** Issue and withdrawal events published by audit-service, see the contracts directory. */
record IssueEvent(
    UUID eventId,
    String eventType,
    int eventVersion,
    UUID missionId,
    int issueCycle,
    long aggregateVersion,
    Instant occurredAt,
    String reason) {
  static final String ISSUED = "audit.mission.findings-issued.v1";
  static final String WITHDRAWN = "audit.mission.findings-withdrawn.v1";

  boolean issued() {
    return ISSUED.equals(eventType);
  }

  void validate() {
    boolean typed =
        issued()
            ? reason == null
            : WITHDRAWN.equals(eventType) && ("REOPENED".equals(reason) || "DELETED".equals(reason));
    if (eventId == null
        || missionId == null
        || occurredAt == null
        || eventVersion != 1
        || issueCycle < 1
        || aggregateVersion < 0
        || !typed) throw new IllegalArgumentException("Invalid issue event");
  }
}
