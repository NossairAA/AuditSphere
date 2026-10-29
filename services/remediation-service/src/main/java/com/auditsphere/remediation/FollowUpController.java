package com.auditsphere.remediation;

import static com.auditsphere.remediation.Remediation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Follow-ups of an issued mission, their closure and the creation of corrective actions. */
@RestController
@RequestMapping("/remediation")
class FollowUpController {
  static final int MAX_ACTIONS = 20;

  record FollowUpView(
      UUID id,
      int reference,
      long findingVersion,
      String title,
      String severity,
      String conditionText,
      String cause,
      String recommendation,
      String status,
      long version,
      String closureRequestedBy,
      Instant closureRequestedAt,
      String closedBy,
      Instant closedAt,
      String returnComment,
      List<ActionView> actions) {}

  record MissionFollowUps(
      UUID missionId,
      String title,
      int issueCycle,
      String state,
      Instant syncedAt,
      String validatorSubject,
      List<FollowUpView> followUps) {}

  record HistoryEntry(
      UUID id,
      UUID actionId,
      String actorSubject,
      String action,
      long entityVersion,
      String reason,
      boolean override,
      Object previousData,
      Object resultingData,
      Instant occurredAt) {}

  record TransitionInput(
      @NotNull @PositiveOrZero Long version,
      @NotBlank @Pattern(regexp = "REQUEST_CLOSURE|CLOSE|RETURN") String action,
      @Size(max = 2000) String comment) {}

  record ActionInput(
      @NotBlank @Size(max = 2000) String description,
      @NotBlank @Size(max = 255) String ownerSubject,
      @NotNull LocalDate dueDate) {}

  private final JdbcTemplate jdbc;
  private final Remediation remediation;
  private final AuditeeDirectory directory;
  private final ChangeLog changes;
  private final ObjectMapper mapper;

  FollowUpController(
      JdbcTemplate jdbc,
      Remediation remediation,
      AuditeeDirectory directory,
      ChangeLog changes,
      ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.remediation = remediation;
    this.directory = directory;
    this.changes = changes;
    this.mapper = mapper;
  }

  @GetMapping("/missions/{missionId}/follow-ups")
  @Transactional(readOnly = true)
  MissionFollowUps list(@PathVariable UUID missionId, Authentication auth) {
    var scope = remediation.readable(missionId, Actor.of(auth));
    var actions = new HashMap<UUID, List<ActionView>>();
    for (var action : remediation.actions("a.mission_id = ?", missionId))
      actions.computeIfAbsent(action.followUpId(), k -> new ArrayList<>()).add(action);
    var followUps =
        jdbc.queryForList(
                "SELECT * FROM finding_follow_ups WHERE mission_id=? ORDER BY reference", missionId)
            .stream()
            .map(
                r ->
                    new FollowUpView(
                        (UUID) r.get("id"),
                        (Integer) r.get("reference"),
                        (Long) r.get("finding_version"),
                        (String) r.get("title"),
                        (String) r.get("severity"),
                        (String) r.get("condition_text"),
                        (String) r.get("cause"),
                        (String) r.get("recommendation"),
                        (String) r.get("status"),
                        (Long) r.get("version"),
                        (String) r.get("closure_requested_by"),
                        instant(r.get("closure_requested_at")),
                        (String) r.get("closed_by"),
                        instant(r.get("closed_at")),
                        (String) r.get("return_comment"),
                        actions.getOrDefault((UUID) r.get("id"), List.of())))
            .toList();
    return new MissionFollowUps(
        missionId,
        scope.title(),
        scope.issueCycle(),
        scope.state(),
        scope.syncedAt(),
        scope.team().validator(),
        followUps);
  }

  @GetMapping("/follow-ups/{id}/history")
  @Transactional(readOnly = true)
  List<HistoryEntry> history(@PathVariable UUID id, Authentication auth) {
    var missions = jdbc.queryForList("SELECT mission_id FROM finding_follow_ups WHERE id=?", UUID.class, id);
    if (missions.isEmpty()) throw notFound();
    remediation.readable(missions.getFirst(), Actor.of(auth));
    return history("follow_up_id = ?", id);
  }

  List<HistoryEntry> history(String where, UUID id) {
    return jdbc.query(
        "SELECT id, action_id, actor_subject, action, entity_version, reason, override, previous_data::text AS previous_data,"
            + " resulting_data::text AS resulting_data, occurred_at FROM remediation_change_log WHERE "
            + where
            + " ORDER BY occurred_at, entity_version LIMIT 1000",
        (rs, i) ->
            new HistoryEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("action_id", UUID.class),
                rs.getString("actor_subject"),
                rs.getString("action"),
                rs.getLong("entity_version"),
                rs.getString("reason"),
                rs.getBoolean("override"),
                json(rs.getString("previous_data")),
                json(rs.getString("resulting_data")),
                rs.getTimestamp("occurred_at").toInstant()),
        id);
  }

  private Object json(String value) {
    return value == null ? null : mapper.readValue(value, Map.class);
  }

  @PutMapping("/follow-ups/{id}/transition")
  @Transactional(timeout = 10)
  Map<String, Object> transition(
      @PathVariable UUID id, @Valid @RequestBody TransitionInput input, Authentication auth) {
    var actor = Actor.of(auth);
    var context = remediation.followUp(id, actor);
    var team = context.scope().team();
    String comment = text(input.comment());
    boolean override =
        switch (input.action()) {
          case "REQUEST_CLOSURE" -> actor.authorize(actor.manager(team));
          default -> actor.authorize(actor.validator(team));
        };
    if ("RETURN".equals(input.action()) && comment == null) throw invalid();
    requireWritable(context);
    requireVersion(context.followUp(), input.version());
    String status = context.followUpStatus();
    long version = input.version() + 1;
    String kind;
    switch (input.action()) {
      case "REQUEST_CLOSURE" -> {
        if (!"ACTION_PLAN".equals(status)) throw conflict();
        var counts =
            jdbc.queryForMap(
                """
                SELECT count(*) FILTER (WHERE status = 'VERIFIED') AS verified,
                       count(*) FILTER (WHERE status NOT IN ('VERIFIED','CANCELLED')) AS open
                  FROM corrective_actions WHERE follow_up_id=?
                """,
                id);
        if (((Number) counts.get("verified")).longValue() == 0
            || ((Number) counts.get("open")).longValue() > 0) throw conflict();
        jdbc.update(
            """
            UPDATE finding_follow_ups SET status='VERIFICATION', closure_requested_by=?, closure_requested_at=now(),
              return_comment=NULL, version=?, updated_at=now() WHERE id=?
            """,
            actor.subject(),
            version,
            id);
        kind = "CLOSURE_REQUESTED";
      }
      case "CLOSE" -> {
        if (!"VERIFICATION".equals(status)) throw conflict();
        // Whoever asked for the closure never closes, even by override.
        if (actor.subject().equals(context.followUp().get("closure_requested_by"))) throw forbidden();
        jdbc.update(
            "UPDATE finding_follow_ups SET status='CLOSED', closed_by=?, closed_at=now(), version=?, updated_at=now() WHERE id=?",
            actor.subject(),
            version,
            id);
        kind = "CLOSED";
      }
      default -> {
        if (!"VERIFICATION".equals(status)) throw conflict();
        jdbc.update(
            """
            UPDATE finding_follow_ups SET status='ACTION_PLAN', return_comment=?, closure_requested_by=NULL,
              closure_requested_at=NULL, version=?, updated_at=now() WHERE id=?
            """,
            comment,
            version,
            id);
        kind = "CLOSURE_RETURNED";
      }
    }
    String next = jdbc.queryForObject("SELECT status FROM finding_follow_ups WHERE id=?", String.class, id);
    changes.write(
        context.missionId(),
        id,
        null,
        actor.subject(),
        kind,
        version,
        comment,
        override,
        Map.of("status", status),
        Map.of("status", next));
    return Map.of("id", id, "status", next, "version", version);
  }

  @PostMapping("/follow-ups/{id}/actions")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional(timeout = 10)
  ActionView create(@PathVariable UUID id, @Valid @RequestBody ActionInput input, Authentication auth) {
    var actor = Actor.of(auth);
    var context = remediation.followUp(id, actor);
    boolean override = actor.authorize(actor.manager(context.scope().team()));
    String description = text(input.description());
    String owner = input.ownerSubject().strip();
    if (description == null) throw invalid();
    requireDueDate(input.dueDate(), remediation);
    requireAssignable(owner, context.scope().team(), directory);
    requireWritable(context);
    String status = context.followUpStatus();
    if (!Set.of("TO_PLAN", "ACTION_PLAN").contains(status)) throw conflict();
    if (jdbc.queryForObject("SELECT count(*) FROM corrective_actions WHERE follow_up_id=?", Integer.class, id)
        >= MAX_ACTIONS) throw conflict();
    int reference =
        jdbc.queryForObject(
            """
            INSERT INTO action_sequences(follow_up_id, last_reference) VALUES (?, 1)
            ON CONFLICT (follow_up_id) DO UPDATE SET last_reference = action_sequences.last_reference + 1
            RETURNING last_reference
            """,
            Integer.class,
            id);
    UUID action = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO corrective_actions(id, follow_up_id, mission_id, reference, description, owner_subject, due_date,
          status, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'TODO', ?, ?)
        """,
        action,
        id,
        context.missionId(),
        reference,
        description,
        owner,
        input.dueDate(),
        actor.subject(),
        actor.subject());
    if ("TO_PLAN".equals(status))
      jdbc.update(
          "UPDATE finding_follow_ups SET status='ACTION_PLAN', version=version+1, updated_at=now() WHERE id=?", id);
    changes.write(
        context.missionId(),
        id,
        action,
        actor.subject(),
        "CREATED",
        0,
        null,
        override,
        null,
        Map.of("description", description, "ownerSubject", owner, "dueDate", input.dueDate().toString(), "status", "TODO"));
    assigned(jdbc, action, id, context.missionId(), owner, 0);
    return remediation.action(action);
  }

  static void requireDueDate(LocalDate due, Remediation remediation) {
    LocalDate today = remediation.today();
    if (due.isBefore(today) || due.isAfter(today.plusYears(5))) throw invalid();
  }

  /** The owner of an action is an active AUDITEE outside the audit team of the mission. */
  static void requireAssignable(String owner, Actor.Team team, AuditeeDirectory directory) {
    if (team.member(owner)) throw invalid();
    directory.requireAuditee(owner);
  }

  /** Outbox row published by lot 6 to notify the new owner. Identifiers only, never texts. */
  static void assigned(JdbcTemplate jdbc, UUID action, UUID followUp, UUID mission, String owner, long version) {
    jdbc.update(
        """
        INSERT INTO remediation_outbox(event_id, event_type, action_id, follow_up_id, mission_id, recipient_subject,
          aggregate_version)
        VALUES (?, 'audit.action.assigned.v1', ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        action,
        followUp,
        mission,
        owner,
        version);
  }
}
