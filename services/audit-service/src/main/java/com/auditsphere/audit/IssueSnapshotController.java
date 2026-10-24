package com.auditsphere.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Frozen snapshot read by remediation-service after an issue event, see ADR-0022. Never routed by
 * the gateway. Reserved to the REMEDIATION_SYNC service role in SecurityConfig.
 */
@RestController
@RequestMapping("/internal/missions/{id}/issues/{cycle}")
class IssueSnapshotController {
  record IssuedFinding(
      UUID id,
      long version,
      int reference,
      String title,
      String severity,
      String conditionText,
      String cause,
      String recommendation) {}

  record IssueSnapshot(
      UUID missionId,
      int issueCycle,
      String title,
      String ownerSubject,
      String validatorSubject,
      List<String> auditorSubjects,
      List<IssuedFinding> findings) {}

  private final JdbcTemplate jdbc;

  IssueSnapshotController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping
  // Not read-only: PostgreSQL refuses FOR SHARE in a read-only transaction.
  @Transactional(timeout = 5)
  IssueSnapshot snapshot(@PathVariable UUID id, @PathVariable int cycle) {
    // FOR SHARE blocks a concurrent reopen or deletion until the snapshot is read.
    var missions =
        jdbc.queryForList(
            "SELECT title, owner_subject, validator_subject, status, issue_cycle, deleted_at FROM missions WHERE id=? FOR SHARE",
            id);
    if (missions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var mission = missions.getFirst();
    if (mission.get("deleted_at") != null
        || !FindingsIssueOutbox.issued((String) mission.get("status"))
        || ((Number) mission.get("issue_cycle")).intValue() != cycle)
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    var auditors =
        jdbc.queryForList(
            "SELECT subject FROM mission_assignments WHERE mission_id=? ORDER BY subject",
            String.class,
            id);
    var findings =
        jdbc.query(
            """
            SELECT id, version, reference, title, severity, condition_text, cause, recommendation
              FROM mission_findings
             WHERE mission_id=? AND status='VALIDATED'
             ORDER BY reference
            """,
            (rs, i) ->
                new IssuedFinding(
                    rs.getObject("id", UUID.class),
                    rs.getLong("version"),
                    rs.getInt("reference"),
                    rs.getString("title"),
                    rs.getString("severity"),
                    rs.getString("condition_text"),
                    rs.getString("cause"),
                    rs.getString("recommendation")),
            id);
    return new IssueSnapshot(
        id,
        cycle,
        (String) mission.get("title"),
        (String) mission.get("owner_subject"),
        (String) mission.get("validator_subject"),
        auditors,
        findings);
  }
}
