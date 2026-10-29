package com.auditsphere.remediation;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared loading for the remediation API. Writes lock in the order used by the sync consumer:
 * mission scope, then follow-up, then action, so the two never deadlock.
 */
@Component
class Remediation {
  record Scope(UUID missionId, String title, int issueCycle, String state, Instant syncedAt, Actor.Team team) {}

  /** A locked write context. The action is null for follow-up writes. */
  record Context(Scope scope, Map<String, Object> followUp, Map<String, Object> action) {
    UUID missionId() {
      return scope.missionId();
    }

    UUID followUpId() {
      return (UUID) followUp.get("id");
    }

    String followUpStatus() {
      return (String) followUp.get("status");
    }

    UUID actionId() {
      return (UUID) action.get("id");
    }

    String actionStatus() {
      return (String) action.get("status");
    }

    String assignee() {
      return (String) action.get("owner_subject");
    }
  }

  record ActionView(
      UUID id,
      UUID followUpId,
      UUID missionId,
      int findingReference,
      int reference,
      String description,
      String ownerSubject,
      String ownerName,
      Boolean ownerActive,
      LocalDate dueDate,
      String status,
      boolean overdue,
      long version,
      String completionNote,
      Instant completedAt,
      String verifiedBy,
      Instant verifiedAt,
      String verificationComment,
      String cancelReason,
      int dueDateChanges,
      int attachmentCount,
      Instant createdAt,
      Instant updatedAt) {}

  static final String ACTION_COLUMNS =
      """
      a.id, a.follow_up_id, a.mission_id, f.reference AS finding_reference, a.reference, a.description,
      a.owner_subject, a.due_date, a.status, a.version, a.completion_note, a.completed_at, a.verified_by,
      a.verified_at, a.verification_comment, a.cancel_reason, a.due_date_changes, a.created_at, a.updated_at,
      (SELECT count(*) FROM action_attachments t WHERE t.action_id = a.id) AS attachment_count
      """;

  private final JdbcTemplate jdbc;
  private final AuditeeDirectory directory;
  private final Clock clock;
  private final ZoneId zone;

  Remediation(
      JdbcTemplate jdbc,
      AuditeeDirectory directory,
      Clock clock,
      @Value("${remediation.time-zone}") String zone) {
    this.jdbc = jdbc;
    this.directory = directory;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  LocalDate today() {
    return LocalDate.now(clock.withZone(zone));
  }

  /** Scope of a mission, locked FOR SHARE for a write. Null when the mission was never issued. */
  Scope scope(UUID mission, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT title, owner_subject, validator_subject, issue_cycle, state, synced_at FROM mission_scopes WHERE mission_id=?"
                + (lock ? " FOR SHARE" : ""),
            mission);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    var auditors =
        Set.copyOf(
            jdbc.queryForList(
                "SELECT subject FROM mission_scope_auditors WHERE mission_id=?", String.class, mission));
    return new Scope(
        mission,
        (String) row.get("title"),
        (Integer) row.get("issue_cycle"),
        (String) row.get("state"),
        instant(row.get("synced_at")),
        new Actor.Team((String) row.get("owner_subject"), (String) row.get("validator_subject"), auditors));
  }

  /** Readable scope for the audit team, 404 otherwise or once the mission is deleted. */
  Scope readable(UUID mission, Actor actor) {
    var scope = scope(mission, false);
    if (scope == null || "WITHDRAWN".equals(scope.state()) || !actor.reads(scope.team()))
      throw notFound();
    return scope;
  }

  /** Locks a follow-up for a write by the audit team. */
  Context followUp(UUID followUp, Actor actor) {
    UUID mission = missionOf("SELECT mission_id FROM finding_follow_ups WHERE id=?", followUp);
    var scope = scope(mission, true);
    if (scope == null || "WITHDRAWN".equals(scope.state()) || !actor.reads(scope.team()))
      throw notFound();
    return new Context(scope, lockFollowUp(followUp, mission), null);
  }

  /** Locks an action for a write. Visible to the audit team and to the auditee it is assigned to. */
  Context action(UUID action, Actor actor) {
    UUID mission = missionOf("SELECT mission_id FROM corrective_actions WHERE id=?", action);
    var scope = scope(mission, true);
    if (scope == null || "WITHDRAWN".equals(scope.state())) throw notFound();
    UUID followUpId =
        jdbc.queryForObject("SELECT follow_up_id FROM corrective_actions WHERE id=?", UUID.class, action);
    var followUp = lockFollowUp(followUpId, mission);
    var row = jdbc.queryForMap("SELECT * FROM corrective_actions WHERE id=? FOR UPDATE", action);
    var context = new Context(scope, followUp, row);
    if (!actor.reads(scope.team()) && !assignee(actor, context.assignee())) throw notFound();
    return context;
  }

  static boolean assignee(Actor actor, String owner) {
    return actor.has("AUDITEE") && actor.subject().equals(owner);
  }

  private Map<String, Object> lockFollowUp(UUID followUp, UUID mission) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM finding_follow_ups WHERE id=? AND mission_id=? FOR UPDATE", followUp, mission);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private UUID missionOf(String sql, UUID id) {
    var rows = jdbc.queryForList(sql, UUID.class, id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  /** Writes need an active mission and a follow-up that is neither suspended, withdrawn nor closed. */
  static void requireWritable(Context context) {
    if (!"ACTIVE".equals(context.scope().state())
        || Set.of("SUSPENDED", "WITHDRAWN", "CLOSED").contains(context.followUpStatus()))
      throw conflict();
  }

  static void requireVersion(Map<String, Object> row, long version) {
    if ((Long) row.get("version") != version) throw conflict();
  }

  List<ActionView> actions(String where, Object... args) {
    var rows =
        jdbc.queryForList(
            "SELECT "
                + ACTION_COLUMNS
                + " FROM corrective_actions a JOIN finding_follow_ups f ON f.id = a.follow_up_id WHERE "
                + where
                + " ORDER BY f.reference, a.reference",
            args);
    var names = directory.names(rows.stream().map(r -> (String) r.get("owner_subject")).toList());
    LocalDate today = today();
    return rows.stream()
        .map(
            r -> {
              var person = names.get((String) r.get("owner_subject"));
              String status = (String) r.get("status");
              LocalDate due = ((java.sql.Date) r.get("due_date")).toLocalDate();
              return new ActionView(
                  (UUID) r.get("id"),
                  (UUID) r.get("follow_up_id"),
                  (UUID) r.get("mission_id"),
                  (Integer) r.get("finding_reference"),
                  (Integer) r.get("reference"),
                  (String) r.get("description"),
                  (String) r.get("owner_subject"),
                  person == null ? null : person.name(),
                  person == null ? null : person.active(),
                  due,
                  status,
                  MyActionsController.isOverdue(status, due, today),
                  (Long) r.get("version"),
                  (String) r.get("completion_note"),
                  instant(r.get("completed_at")),
                  (String) r.get("verified_by"),
                  instant(r.get("verified_at")),
                  (String) r.get("verification_comment"),
                  (String) r.get("cancel_reason"),
                  (Integer) r.get("due_date_changes"),
                  ((Number) r.get("attachment_count")).intValue(),
                  instant(r.get("created_at")),
                  instant(r.get("updated_at")));
            })
        .toList();
  }

  ActionView action(UUID id) {
    return actions("a.id = ?", id).getFirst();
  }

  static Instant instant(Object value) {
    return value == null ? null : ((Timestamp) value).toInstant();
  }

  static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT);
  }

  static ResponseStatusException forbidden() {
    return new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }

  /** Stripped text, or null when blank. */
  static String text(String value) {
    if (value == null) return null;
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }
}
