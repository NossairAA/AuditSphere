package com.auditsphere.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records when the validated findings of a mission are issued to or withdrawn from remediation. */
@Component
class FindingsIssueOutbox {
  static final String ISSUED = "audit.mission.findings-issued.v1";
  static final String WITHDRAWN = "audit.mission.findings-withdrawn.v1";
  private final JdbcTemplate jdbc;

  FindingsIssueOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  static boolean issued(String status) {
    return "APPROVED".equals(status) || "CLOSED".equals(status);
  }

  /** Called after the mission is flushed, so aggregate_version is the committed version. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void afterTransition(Mission mission, String before, boolean deleted) {
    boolean wasIssued = issued(before);
    boolean isIssued = !deleted && issued(mission.status);
    if (!wasIssued && isIssued) {
      Integer cycle =
          jdbc.queryForObject(
              "UPDATE missions SET issue_cycle = issue_cycle + 1 WHERE id = ? RETURNING issue_cycle",
              Integer.class,
              mission.id);
      append(ISSUED, mission, cycle, null);
    } else if (wasIssued && !isIssued) {
      Integer cycle =
          jdbc.queryForObject(
              "SELECT issue_cycle FROM missions WHERE id = ?", Integer.class, mission.id);
      // A mission approved before V18 was never issued, so there is nothing to withdraw.
      if (cycle != null && cycle > 0)
        append(WITHDRAWN, mission, cycle, deleted ? "DELETED" : "REOPENED");
    }
  }

  private void append(String type, Mission mission, Integer cycle, String reason) {
    jdbc.update(
        "INSERT INTO findings_issue_outbox(event_id,event_type,mission_id,issue_cycle,reason,aggregate_version) VALUES (?,?,?,?,?,?)",
        UUID.randomUUID(),
        type,
        mission.id,
        cycle,
        reason,
        mission.version);
  }
}
