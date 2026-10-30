package com.auditsphere.remediation;

import com.auditsphere.remediation.IssueSnapshotClient.IssueSnapshot;
import com.auditsphere.remediation.IssueSnapshotClient.IssuedFinding;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps mission scopes and follow-ups in line with the issue cycles of audit-service, see ADR-0022.
 * Both topics are applied under one lock per mission, so their relative order does not matter.
 */
@Component
class FindingsIssueConsumer {
  /** Change log actor of synchronised changes. Keycloak subjects are UUIDs, so it never collides. */
  static final String SYNC_ACTOR = "audit-service";

  private static final Set<String> SEVERITIES = Set.of("LOW", "MODERATE", "HIGH", "CRITICAL");

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final IssueSnapshotClient snapshots;
  private final ChangeLog changes;

  FindingsIssueConsumer(
      JdbcTemplate jdbc, ObjectMapper mapper, IssueSnapshotClient snapshots, ChangeLog changes) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.snapshots = snapshots;
    this.changes = changes;
  }

  // RECORD acknowledgment happens after this database transaction has committed.
  @Transactional(timeout = 15)
  @KafkaListener(
      topics = {IssueEvent.ISSUED, IssueEvent.WITHDRAWN},
      groupId = "remediation-service-v1")
  public void receive(String payload) throws Exception {
    if (payload == null || payload.length() > 32768) throw new IllegalArgumentException("Event too large");
    var event = mapper.readValue(payload, IssueEvent.class);
    if (event == null) throw new IllegalArgumentException("Null issue event");
    event.validate();
    // Held until commit. The snapshot is read under it, so a withdrawal applied first makes it a 409.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", event.missionId().toString());
    if (jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id=?)",
        Boolean.class,
        event.eventId())) return;
    var scopes =
        jdbc.queryForList(
            "SELECT issue_cycle, state FROM mission_scopes WHERE mission_id=? FOR UPDATE",
            event.missionId());
    Integer known = scopes.isEmpty() ? null : (Integer) scopes.getFirst().get("issue_cycle");
    String state = scopes.isEmpty() ? null : (String) scopes.getFirst().get("state");
    if (event.issued()) {
      // A withdrawal of the same cycle was applied first: this issue is already over.
      boolean stale =
          known != null
              && (event.issueCycle() < known
                  || (event.issueCycle() == known && !"ACTIVE".equals(state)));
      if (!stale)
        snapshots.read(event.missionId(), event.issueCycle()).ifPresent(s -> issue(event, s));
    } else if (known != null && event.issueCycle() >= known) {
      withdraw(event, state);
    }
    jdbc.update(
        "INSERT INTO processed_events(event_id, event_type) VALUES (?, ?)",
        event.eventId(),
        event.eventType());
  }

  private void issue(IssueEvent event, IssueSnapshot s) {
    validate(event, s);
    UUID mission = s.missionId();
    jdbc.update(
        """
        INSERT INTO mission_scopes(mission_id, title, owner_subject, validator_subject, issue_cycle, state, synced_at)
        VALUES (?, ?, ?, ?, ?, 'ACTIVE', now())
        ON CONFLICT (mission_id) DO UPDATE SET title=EXCLUDED.title, owner_subject=EXCLUDED.owner_subject,
          validator_subject=EXCLUDED.validator_subject, issue_cycle=EXCLUDED.issue_cycle, state='ACTIVE', synced_at=now()
        """,
        mission,
        s.title(),
        s.ownerSubject(),
        s.validatorSubject(),
        s.issueCycle());
    jdbc.update("DELETE FROM mission_scope_auditors WHERE mission_id=?", mission);
    jdbc.batchUpdate(
        "INSERT INTO mission_scope_auditors(mission_id, subject) VALUES (?, ?)",
        s.auditorSubjects().stream().distinct().map(a -> new Object[] {mission, a}).toList());
    var existing = new HashMap<UUID, Map<String, Object>>();
    for (var row :
        jdbc.queryForList(
            "SELECT id, finding_version, status, status_before_suspension, version FROM finding_follow_ups WHERE mission_id=? FOR UPDATE",
            mission)) existing.put((UUID) row.get("id"), row);
    for (var f : s.findings()) {
      var row = existing.remove(f.id());
      if (row == null) {
        jdbc.update(
            """
            INSERT INTO finding_follow_ups(id, mission_id, reference, finding_version, title, severity,
              condition_text, cause, recommendation, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'TO_PLAN')
            """,
            f.id(),
            mission,
            f.reference(),
            f.version(),
            f.title(),
            f.severity(),
            f.conditionText(),
            f.cause(),
            f.recommendation());
        log(mission, f.id(), "ISSUED", 0, null, null, data("TO_PLAN", f.version(), s.issueCycle()));
        continue;
      }
      String status = (String) row.get("status");
      String next =
          switch (status) {
            case "SUSPENDED" -> (String) row.get("status_before_suspension");
            case "WITHDRAWN" -> openActions(f.id()) ? "ACTION_PLAN" : "TO_PLAN";
            default -> status;
          };
      long findingVersion = (Long) row.get("finding_version");
      if (next.equals(status) && findingVersion == f.version()) continue;
      long version = (Long) row.get("version") + 1;
      jdbc.update(
          """
          UPDATE finding_follow_ups SET finding_version=?, title=?, severity=?, condition_text=?, cause=?,
            recommendation=?, status=?, status_before_suspension=NULL, version=?, updated_at=now()
          WHERE id=?
          """,
          f.version(),
          f.title(),
          f.severity(),
          f.conditionText(),
          f.cause(),
          f.recommendation(),
          next,
          version,
          f.id());
      log(
          mission,
          f.id(),
          "ISSUED",
          version,
          null,
          data(status, findingVersion, s.issueCycle()),
          data(next, f.version(), s.issueCycle()));
    }
    // Findings no longer validated in this cycle leave the follow-up, their history stays.
    for (var row : existing.values()) {
      String status = (String) row.get("status");
      if ("WITHDRAWN".equals(status)) continue;
      UUID id = (UUID) row.get("id");
      long findingVersion = (Long) row.get("finding_version");
      long version = (Long) row.get("version") + 1;
      jdbc.update(
          "UPDATE finding_follow_ups SET status='WITHDRAWN', status_before_suspension=NULL, version=?, updated_at=now() WHERE id=?",
          version,
          id);
      log(
          mission,
          id,
          "WITHDRAWN",
          version,
          null,
          data(status, findingVersion, s.issueCycle()),
          data("WITHDRAWN", findingVersion, s.issueCycle()));
    }
  }

  private void withdraw(IssueEvent event, String state) {
    UUID mission = event.missionId();
    boolean deleted = "DELETED".equals(event.reason()) || "WITHDRAWN".equals(state);
    jdbc.update(
        "UPDATE mission_scopes SET state=?, issue_cycle=?, synced_at=now() WHERE mission_id=?",
        deleted ? "WITHDRAWN" : "SUSPENDED",
        event.issueCycle(),
        mission);
    for (var row :
        jdbc.queryForList(
            "SELECT id, finding_version, status, status_before_suspension, version FROM finding_follow_ups WHERE mission_id=? AND status <> 'WITHDRAWN' FOR UPDATE",
            mission)) {
      String status = (String) row.get("status");
      if (!deleted && "SUSPENDED".equals(status)) continue;
      UUID id = (UUID) row.get("id");
      String before =
          "SUSPENDED".equals(status) ? (String) row.get("status_before_suspension") : status;
      long findingVersion = (Long) row.get("finding_version");
      long version = (Long) row.get("version") + 1;
      String next = deleted ? "WITHDRAWN" : "SUSPENDED";
      jdbc.update(
          "UPDATE finding_follow_ups SET status=?, status_before_suspension=?, version=?, updated_at=now() WHERE id=?",
          next,
          deleted ? null : before,
          version,
          id);
      log(
          mission,
          id,
          next,
          version,
          event.reason(),
          data(status, findingVersion, event.issueCycle()),
          data(next, findingVersion, event.issueCycle()));
    }
  }

  private boolean openActions(UUID followUp) {
    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM corrective_actions WHERE follow_up_id=? AND status <> 'CANCELLED')",
        Boolean.class,
        followUp);
  }

  private static Map<String, Object> data(String status, long findingVersion, int issueCycle) {
    return Map.of("status", status, "findingVersion", findingVersion, "issueCycle", issueCycle);
  }

  private void log(
      UUID mission,
      UUID followUp,
      String action,
      long version,
      String reason,
      Map<String, Object> previous,
      Map<String, Object> resulting) {
    changes.write(mission, followUp, null, SYNC_ACTOR, action, version, reason, false, previous, resulting);
  }

  /** A malformed snapshot is a contract error: quarantined, never retried in a loop. */
  private static void validate(IssueEvent event, IssueSnapshot s) {
    boolean valid =
        event.missionId().equals(s.missionId())
            && s.issueCycle() == event.issueCycle()
            && text(s.title(), 200)
            && text(s.ownerSubject(), 255)
            && (s.validatorSubject() == null || text(s.validatorSubject(), 255))
            && s.auditorSubjects() != null
            && s.auditorSubjects().stream().allMatch(a -> text(a, 255))
            && s.findings() != null
            && s.findings().stream().allMatch(FindingsIssueConsumer::valid)
            && s.findings().stream().map(IssuedFinding::id).distinct().count() == s.findings().size()
            && s.findings().stream().map(IssuedFinding::reference).distinct().count()
                == s.findings().size();
    if (!valid) throw new IllegalArgumentException("Invalid issue snapshot");
  }

  private static boolean valid(IssuedFinding f) {
    return f != null
        && f.id() != null
        && f.version() >= 0
        && f.reference() > 0
        && text(f.title(), 200)
        && SEVERITIES.contains(f.severity())
        && text(f.conditionText(), 4000)
        && text(f.cause(), 4000)
        && text(f.recommendation(), 4000);
  }

  private static boolean text(String value, int max) {
    return value != null && !value.isBlank() && value.length() <= max;
  }
}
