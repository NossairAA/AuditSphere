package com.auditsphere.remediation;

import static com.auditsphere.remediation.Remediation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Corrective actions: detail, changes by the mission owner, progress and transitions. */
@RestController
@RequestMapping("/remediation/actions/{id}")
class ActionController {
  record FindingCopy(
      UUID followUpId,
      int reference,
      String title,
      String severity,
      String conditionText,
      String cause,
      String recommendation,
      String status) {}

  record ProgressNote(UUID id, String authorSubject, String note, Instant createdAt) {}

  record ActionDetail(
      ActionView action,
      FindingCopy finding,
      List<ProgressNote> progress,
      List<FollowUpController.HistoryEntry> history) {}

  record UpdateInput(
      @NotNull @PositiveOrZero Long version,
      @Size(max = 2000) String description,
      @Size(max = 255) String ownerSubject,
      LocalDate dueDate,
      @Size(max = 2000) String reason) {}

  record TransitionInput(
      @NotNull @PositiveOrZero Long version,
      @NotBlank @Pattern(regexp = "COMPLETE|VERIFY|RETURN|CANCEL|REOPEN") String action,
      @Size(max = 2000) String comment) {}

  record ProgressInput(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 2000) String note) {}

  private final JdbcTemplate jdbc;
  private final Remediation remediation;
  private final AuditeeDirectory directory;
  private final ChangeLog changes;
  private final FollowUpController followUps;

  ActionController(
      JdbcTemplate jdbc,
      Remediation remediation,
      AuditeeDirectory directory,
      ChangeLog changes,
      FollowUpController followUps) {
    this.jdbc = jdbc;
    this.remediation = remediation;
    this.directory = directory;
    this.changes = changes;
    this.followUps = followUps;
  }

  /** Action sheet: the auditee sees its action and the finding copy, never the rest of the mission. */
  @GetMapping
  @Transactional(readOnly = true)
  ActionDetail detail(@PathVariable UUID id, Authentication auth) {
    var actor = Actor.of(auth);
    var rows = jdbc.queryForList("SELECT mission_id, owner_subject FROM corrective_actions WHERE id=?", id);
    if (rows.isEmpty()) throw notFound();
    var scope = remediation.scope((UUID) rows.getFirst().get("mission_id"), false);
    if (scope == null
        || "WITHDRAWN".equals(scope.state())
        || !(actor.reads(scope.team())
            || assignee(actor, (String) rows.getFirst().get("owner_subject")))) throw notFound();
    var action = remediation.action(id);
    var finding =
        jdbc.queryForObject(
            "SELECT id, reference, title, severity, condition_text, cause, recommendation, status FROM finding_follow_ups WHERE id=?",
            (rs, i) ->
                new FindingCopy(
                    rs.getObject("id", UUID.class),
                    rs.getInt("reference"),
                    rs.getString("title"),
                    rs.getString("severity"),
                    rs.getString("condition_text"),
                    rs.getString("cause"),
                    rs.getString("recommendation"),
                    rs.getString("status")),
            action.followUpId());
    var progress =
        jdbc.query(
            "SELECT id, author_subject, note, created_at FROM action_progress_notes WHERE action_id=? ORDER BY created_at",
            (rs, i) ->
                new ProgressNote(
                    rs.getObject("id", UUID.class),
                    rs.getString("author_subject"),
                    rs.getString("note"),
                    rs.getTimestamp("created_at").toInstant()),
            id);
    return new ActionDetail(action, finding, progress, followUps.history("action_id = ?", id));
  }

  @PutMapping
  @Transactional(timeout = 10)
  ActionView update(@PathVariable UUID id, @Valid @RequestBody UpdateInput input, Authentication auth) {
    var actor = Actor.of(auth);
    var context = remediation.action(id, actor);
    var team = context.scope().team();
    boolean override = actor.authorize(actor.manager(team));
    var action = context.action();
    String description = text(input.description());
    String owner = text(input.ownerSubject());
    String reason = text(input.reason());
    if (input.description() != null && description == null) throw invalid();
    boolean described = description != null && !description.equals(action.get("description"));
    boolean reassigned = owner != null && !owner.equals(context.assignee());
    LocalDate due = ((java.sql.Date) action.get("due_date")).toLocalDate();
    boolean postponed = input.dueDate() != null && !input.dueDate().equals(due);
    if (postponed) {
      if (reason == null) throw invalid();
      FollowUpController.requireDueDate(input.dueDate(), remediation);
    }
    if (reassigned) FollowUpController.requireAssignable(owner, team, directory);
    requireOpenPlan(context);
    if (!Set.of("TODO", "IN_PROGRESS").contains(context.actionStatus())) throw conflict();
    requireVersion(action, input.version());
    if (!described && !reassigned && !postponed) return remediation.action(id);
    long version = input.version() + 1;
    jdbc.update(
        """
        UPDATE corrective_actions SET description=?, owner_subject=?, due_date=?,
          due_date_changes=due_date_changes + ?, version=?, updated_by=?, updated_at=now()
        WHERE id=?
        """,
        described ? description : action.get("description"),
        reassigned ? owner : context.assignee(),
        postponed ? input.dueDate() : due,
        postponed ? 1 : 0,
        version,
        actor.subject(),
        id);
    if (described)
      log(context, actor, "UPDATED", version, null, override, "description", action.get("description"), description);
    if (reassigned) {
      log(context, actor, "REASSIGNED", version, reason, override, "ownerSubject", context.assignee(), owner);
      FollowUpController.assigned(jdbc, id, context.followUpId(), context.missionId(), owner, version);
    }
    if (postponed)
      log(context, actor, "DUE_DATE_CHANGED", version, reason, override, "dueDate", due.toString(), input.dueDate().toString());
    return remediation.action(id);
  }

  @PutMapping("/transition")
  @Transactional(timeout = 10)
  ActionView transition(
      @PathVariable UUID id, @Valid @RequestBody TransitionInput input, Authentication auth) {
    var actor = Actor.of(auth);
    var context = remediation.action(id, actor);
    var team = context.scope().team();
    String comment = text(input.comment());
    boolean override =
        switch (input.action()) {
          // Only the owner of the action declares it done, no override.
          case "COMPLETE" -> {
            if (!assignee(actor, context.assignee())) throw forbidden();
            yield false;
          }
          case "VERIFY", "RETURN" -> {
            boolean allowed = actor.authorize(actor.auditor(team) || actor.manager(team));
            // The owner of the action never verifies it, whatever its roles.
            if (actor.subject().equals(context.assignee())) throw forbidden();
            yield allowed;
          }
          default -> actor.authorize(actor.manager(team));
        };
    if (!"VERIFY".equals(input.action()) && comment == null) throw invalid();
    requireOpenPlan(context);
    requireVersion(context.action(), input.version());
    String status = context.actionStatus();
    long version = input.version() + 1;
    String kind;
    switch (input.action()) {
      case "COMPLETE" -> {
        if (!Set.of("TODO", "IN_PROGRESS").contains(status)) throw conflict();
        if (jdbc.queryForObject("SELECT count(*) FROM action_attachments WHERE action_id=?", Integer.class, id) == 0)
          throw conflict();
        jdbc.update(
            "UPDATE corrective_actions SET status='DONE', completion_note=?, completed_at=now(), version=?, updated_by=?, updated_at=now() WHERE id=?",
            comment,
            version,
            actor.subject(),
            id);
        kind = "COMPLETED";
      }
      case "VERIFY" -> {
        if (!"DONE".equals(status)) throw conflict();
        jdbc.update(
            "UPDATE corrective_actions SET status='VERIFIED', verified_by=?, verified_at=now(), verification_comment=?, version=?, updated_by=?, updated_at=now() WHERE id=?",
            actor.subject(),
            comment,
            version,
            actor.subject(),
            id);
        kind = "VERIFIED";
      }
      case "RETURN" -> {
        if (!"DONE".equals(status)) throw conflict();
        jdbc.update(
            "UPDATE corrective_actions SET status='IN_PROGRESS', verification_comment=?, version=?, updated_by=?, updated_at=now() WHERE id=?",
            comment,
            version,
            actor.subject(),
            id);
        kind = "RETURNED";
      }
      case "CANCEL" -> {
        if (!Set.of("TODO", "IN_PROGRESS", "DONE").contains(status)) throw conflict();
        jdbc.update(
            "UPDATE corrective_actions SET status='CANCELLED', cancel_reason=?, version=?, updated_by=?, updated_at=now() WHERE id=?",
            comment,
            version,
            actor.subject(),
            id);
        // No remaining action: the follow-up goes back to planning.
        jdbc.update(
            """
            UPDATE finding_follow_ups SET status='TO_PLAN', version=version+1, updated_at=now()
             WHERE id=? AND NOT EXISTS (SELECT 1 FROM corrective_actions WHERE follow_up_id=? AND status <> 'CANCELLED')
            """,
            context.followUpId(),
            context.followUpId());
        kind = "CANCELLED";
      }
      default -> {
        if (!"VERIFIED".equals(status)) throw conflict();
        jdbc.update(
            "UPDATE corrective_actions SET status='IN_PROGRESS', verified_by=NULL, verified_at=NULL, version=?, updated_by=?, updated_at=now() WHERE id=?",
            version,
            actor.subject(),
            id);
        kind = "REOPENED";
      }
    }
    var view = remediation.action(id);
    log(context, actor, kind, version, comment, override, "status", status, view.status());
    return view;
  }

  @PostMapping("/progress")
  @Transactional(timeout = 10)
  ActionView progress(@PathVariable UUID id, @Valid @RequestBody ProgressInput input, Authentication auth) {
    var actor = Actor.of(auth);
    var context = remediation.action(id, actor);
    if (!assignee(actor, context.assignee())) throw forbidden();
    String note = text(input.note());
    if (note == null) throw invalid();
    requireOpenPlan(context);
    String status = context.actionStatus();
    if (!Set.of("TODO", "IN_PROGRESS").contains(status)) throw conflict();
    requireVersion(context.action(), input.version());
    long version = input.version() + 1;
    jdbc.update(
        "INSERT INTO action_progress_notes(id, action_id, author_subject, note) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        id,
        actor.subject(),
        note);
    // The first progress note starts the action.
    jdbc.update(
        "UPDATE corrective_actions SET status='IN_PROGRESS', version=?, updated_by=?, updated_at=now() WHERE id=?",
        version,
        actor.subject(),
        id);
    log(context, actor, "PROGRESS", version, null, false, "status", status, "IN_PROGRESS");
    return remediation.action(id);
  }

  /** Actions change only while the plan is open: frozen in Verification and after closure. */
  private static void requireOpenPlan(Remediation.Context context) {
    requireWritable(context);
    if (!"ACTION_PLAN".equals(context.followUpStatus())) throw conflict();
  }

  private void log(
      Remediation.Context context,
      Actor actor,
      String kind,
      long version,
      String reason,
      boolean override,
      String field,
      Object before,
      Object after) {
    changes.write(
        context.missionId(),
        context.followUpId(),
        context.actionId(),
        actor.subject(),
        kind,
        version,
        reason,
        override,
        Map.of(field, before),
        Map.of(field, after));
  }
}
