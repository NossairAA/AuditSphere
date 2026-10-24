package com.auditsphere.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

// Constats d'une mission, voir ADR-0021 : tables et journal distincts du code des missions.
@RestController
@RequestMapping("/missions/{missionId}/findings")
class FindingController {
  static final int MAX_FINDINGS = 100;

  private final MissionRepository missions;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  FindingController(MissionRepository missions, JdbcTemplate jdbc, ObjectMapper json) {
    this.missions = missions;
    this.jdbc = jdbc;
    this.json = json;
  }

  record LinkedControl(UUID id, String objective, String outcome) {}

  record Finding(
      UUID id,
      int reference,
      String title,
      String condition,
      String criterion,
      String cause,
      String consequence,
      String recommendation,
      String severity,
      String status,
      long version,
      boolean everSubmitted,
      String createdBy,
      String updatedBy,
      Instant updatedAt,
      String submittedBy,
      Instant submittedAt,
      String decidedBy,
      Instant decidedAt,
      String decisionComment,
      List<LinkedControl> controls,
      Set<UUID> riskIds,
      boolean inconsistentControls) {}

  record Snapshot(
      String title,
      String condition,
      String criterion,
      String cause,
      String consequence,
      String recommendation,
      String severity,
      String status,
      Set<UUID> controlIds) {
    static Snapshot of(Finding f) {
      var ids = new TreeSet<UUID>();
      f.controls().forEach(c -> ids.add(c.id()));
      return new Snapshot(
          f.title(),
          f.condition(),
          f.criterion(),
          f.cause(),
          f.consequence(),
          f.recommendation(),
          f.severity(),
          f.status(),
          ids);
    }
  }

  record HistoryEntry(
      String action,
      String actorSubject,
      long findingVersion,
      String reason,
      boolean override,
      Instant occurredAt) {}

  record FindingInput(
      @PositiveOrZero Long version,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 4000) String condition,
      @Size(max = 4000) String criterion,
      @Size(max = 4000) String cause,
      @Size(max = 4000) String consequence,
      @Size(max = 4000) String recommendation,
      @NotBlank @Pattern(regexp = "LOW|MODERATE|HIGH|CRITICAL") String severity,
      @NotNull @Size(min = 1, max = 20) Set<@NotNull UUID> controlIds) {}

  record TransitionInput(
      @NotNull @PositiveOrZero Long version,
      @NotBlank @Pattern(regexp = "SUBMIT|WITHDRAW|VALIDATE|RETURN|REJECT|REOPEN") String action,
      @Size(max = 2000) String comment) {}

  private boolean role(Jwt jwt, String name) {
    Map<String, Object> realm = jwt.getClaim("realm_access");
    return realm != null && realm.get("roles") instanceof List<?> roles && roles.contains(name);
  }

  private Mission accessible(UUID id, Jwt jwt, boolean write) {
    Mission m =
        (write ? missions.findForUpdate(id) : missions.findById(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (m.deletedAt != null
        || !(role(jwt, "SUPER_ADMIN")
            || m.ownerSubject.equals(jwt.getSubject())
            || m.assigneeSubjects.contains(jwt.getSubject())
            || (role(jwt, "AUDIT_VALIDATOR") && jwt.getSubject().equals(m.validatorSubject))))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return m;
  }

  private boolean auditor(Mission m, Jwt jwt) {
    return role(jwt, "AUDITOR") && m.assigneeSubjects.contains(jwt.getSubject());
  }

  private boolean manager(Mission m, Jwt jwt) {
    return role(jwt, "AUDIT_MANAGER") && m.ownerSubject.equals(jwt.getSubject());
  }

  // Retourne vrai lorsque l'action passe par la dérogation du superadministrateur.
  private boolean authorize(boolean allowed, Jwt jwt) {
    if (allowed) return false;
    if (role(jwt, "SUPER_ADMIN")) return true;
    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  private void requireInProgress(Mission m) {
    if (!m.status.equals("IN_PROGRESS")) throw new ResponseStatusException(HttpStatus.CONFLICT);
  }

  private static String trimmed(String value) {
    if (value == null) return null;
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  private List<Finding> findings(UUID missionId) {
    Map<UUID, List<LinkedControl>> controls = new HashMap<>();
    Map<UUID, Set<UUID>> risks = new HashMap<>();
    jdbc.query(
        "SELECT fc.finding_id,c.id,c.objective,c.result_data->>'outcome' AS outcome FROM"
            + " mission_finding_controls fc JOIN mission_controls c ON c.id=fc.control_id AND"
            + " c.mission_id=fc.mission_id WHERE fc.mission_id=? ORDER BY c.created_at,c.id",
        r -> {
          controls
              .computeIfAbsent(r.getObject("finding_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new LinkedControl(
                      r.getObject("id", UUID.class),
                      r.getString("objective"),
                      r.getString("outcome")));
        },
        missionId);
    jdbc.query(
        "SELECT DISTINCT fc.finding_id,cr.risk_id FROM mission_finding_controls fc JOIN"
            + " mission_control_risks cr ON cr.control_id=fc.control_id AND"
            + " cr.mission_id=fc.mission_id WHERE fc.mission_id=?",
        r -> {
          risks
              .computeIfAbsent(r.getObject("finding_id", UUID.class), k -> new TreeSet<>())
              .add(r.getObject("risk_id", UUID.class));
        },
        missionId);
    return jdbc.query(
        "SELECT * FROM mission_findings WHERE mission_id=? ORDER BY reference",
        (r, n) -> {
          UUID id = r.getObject("id", UUID.class);
          var linked = controls.getOrDefault(id, List.of());
          return new Finding(
              id,
              r.getInt("reference"),
              r.getString("title"),
              r.getString("condition_text"),
              r.getString("criterion"),
              r.getString("cause"),
              r.getString("consequence"),
              r.getString("recommendation"),
              r.getString("severity"),
              r.getString("status"),
              r.getLong("version"),
              r.getBoolean("ever_submitted"),
              r.getString("created_by"),
              r.getString("updated_by"),
              r.getObject("updated_at", java.time.OffsetDateTime.class).toInstant(),
              r.getString("submitted_by"),
              instant(r.getObject("submitted_at", java.time.OffsetDateTime.class)),
              r.getString("decided_by"),
              instant(r.getObject("decided_at", java.time.OffsetDateTime.class)),
              r.getString("decision_comment"),
              linked,
              risks.getOrDefault(id, Set.of()),
              linked.stream().anyMatch(c -> !"NON_CONFORMING".equals(c.outcome())));
        },
        missionId);
  }

  private static Instant instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private Finding finding(UUID missionId, UUID findingId) {
    return findings(missionId).stream()
        .filter(f -> f.id().equals(findingId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private void requireControls(UUID missionId, Set<UUID> controlIds) {
    Integer known =
        jdbc.queryForObject(
            "SELECT count(*) FROM mission_controls WHERE mission_id=? AND id = ANY(?)",
            Integer.class,
            missionId,
            controlIds.toArray(UUID[]::new));
    if (known == null || known != controlIds.size())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }

  private void link(UUID missionId, UUID findingId, Set<UUID> controlIds) {
    jdbc.update(
        "DELETE FROM mission_finding_controls WHERE finding_id=? AND mission_id=?",
        findingId,
        missionId);
    for (UUID control : controlIds)
      jdbc.update(
          "INSERT INTO mission_finding_controls(mission_id,finding_id,control_id) VALUES (?,?,?)",
          missionId,
          findingId,
          control);
  }

  private String serialized(Object value) {
    if (value == null) return null;
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void log(
      UUID missionId,
      UUID findingId,
      Jwt jwt,
      String action,
      long version,
      String reason,
      boolean override,
      Finding before,
      Finding after) {
    jdbc.update(
        "INSERT INTO finding_change_log(id,mission_id,finding_id,actor_subject,action,finding_version,reason,override,previous_data,resulting_data)"
            + " VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))",
        UUID.randomUUID(),
        missionId,
        findingId,
        jwt.getSubject(),
        action,
        version,
        reason,
        override,
        serialized(before == null ? null : Snapshot.of(before)),
        serialized(after == null ? null : Snapshot.of(after)));
  }

  @GetMapping
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  List<Finding> list(@PathVariable UUID missionId, @AuthenticationPrincipal Jwt jwt) {
    return findings(accessible(missionId, jwt, false).id);
  }

  @GetMapping("/{findingId}/history")
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  List<HistoryEntry> history(
      @PathVariable UUID missionId,
      @PathVariable UUID findingId,
      @AuthenticationPrincipal Jwt jwt) {
    Mission m = accessible(missionId, jwt, false);
    finding(m.id, findingId);
    return jdbc.query(
        "SELECT action,actor_subject,finding_version,reason,override,occurred_at FROM"
            + " finding_change_log WHERE mission_id=? AND finding_id=? ORDER BY occurred_at,id",
        (r, n) ->
            new HistoryEntry(
                r.getString("action"),
                r.getString("actor_subject"),
                r.getLong("finding_version"),
                r.getString("reason"),
                r.getBoolean("override"),
                r.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant()),
        m.id,
        findingId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional(timeout = 5)
  Finding create(
      @PathVariable UUID missionId,
      @Valid @RequestBody FindingInput input,
      @AuthenticationPrincipal Jwt jwt) {
    Mission m = accessible(missionId, jwt, true);
    boolean override = authorize(auditor(m, jwt), jwt);
    requireInProgress(m);
    requireControls(m.id, input.controlIds());
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM mission_findings WHERE mission_id=?", Integer.class, m.id);
    if (count >= MAX_FINDINGS) throw new ResponseStatusException(HttpStatus.CONFLICT);
    // Une référence libérée par la suppression d'un brouillon n'est jamais réattribuée.
    Integer reference =
        jdbc.queryForObject(
            "INSERT INTO mission_finding_sequences(mission_id,last_reference) VALUES (?,1)"
                + " ON CONFLICT (mission_id) DO UPDATE SET last_reference=mission_finding_sequences.last_reference+1"
                + " RETURNING last_reference",
            Integer.class,
            m.id);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission_findings(id,mission_id,reference,title,condition_text,criterion,cause,consequence,recommendation,severity,status,created_by,updated_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?)",
        id,
        m.id,
        reference,
        input.title().strip(),
        input.condition().strip(),
        trimmed(input.criterion()),
        trimmed(input.cause()),
        trimmed(input.consequence()),
        trimmed(input.recommendation()),
        input.severity(),
        jwt.getSubject(),
        jwt.getSubject());
    link(m.id, id, input.controlIds());
    Finding after = finding(m.id, id);
    log(m.id, id, jwt, "CREATED", after.version(), null, override, null, after);
    return after;
  }

  @PutMapping("/{findingId}")
  @Transactional(timeout = 5)
  Finding update(
      @PathVariable UUID missionId,
      @PathVariable UUID findingId,
      @Valid @RequestBody FindingInput input,
      @AuthenticationPrincipal Jwt jwt) {
    if (input.version() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    Mission m = accessible(missionId, jwt, true);
    boolean override = authorize(auditor(m, jwt), jwt);
    requireInProgress(m);
    Finding before = finding(m.id, findingId);
    if (!before.status().equals("DRAFT") || before.version() != input.version())
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    requireControls(m.id, input.controlIds());
    jdbc.update(
        "UPDATE mission_findings SET title=?,condition_text=?,criterion=?,cause=?,consequence=?,recommendation=?,severity=?,"
            + "version=version+1,updated_by=?,updated_at=now() WHERE id=? AND mission_id=?",
        input.title().strip(),
        input.condition().strip(),
        trimmed(input.criterion()),
        trimmed(input.cause()),
        trimmed(input.consequence()),
        trimmed(input.recommendation()),
        input.severity(),
        jwt.getSubject(),
        findingId,
        m.id);
    link(m.id, findingId, input.controlIds());
    Finding after = finding(m.id, findingId);
    log(m.id, findingId, jwt, "UPDATED", after.version(), null, override, before, after);
    return after;
  }

  @DeleteMapping("/{findingId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional(timeout = 5)
  void delete(
      @PathVariable UUID missionId,
      @PathVariable UUID findingId,
      @RequestParam long version,
      @AuthenticationPrincipal Jwt jwt) {
    Mission m = accessible(missionId, jwt, true);
    boolean override = authorize(auditor(m, jwt), jwt);
    requireInProgress(m);
    Finding before = finding(m.id, findingId);
    if (!before.status().equals("DRAFT") || before.everSubmitted() || before.version() != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    log(m.id, findingId, jwt, "DELETED", before.version(), null, override, before, null);
    jdbc.update("DELETE FROM mission_findings WHERE id=? AND mission_id=?", findingId, m.id);
  }

  @PutMapping("/{findingId}/transition")
  @Transactional(timeout = 5)
  Finding transition(
      @PathVariable UUID missionId,
      @PathVariable UUID findingId,
      @Valid @RequestBody TransitionInput input,
      @AuthenticationPrincipal Jwt jwt) {
    Mission m = accessible(missionId, jwt, true);
    String action = input.action();
    boolean decision = Set.of("VALIDATE", "RETURN", "REJECT", "REOPEN").contains(action);
    boolean override = authorize(decision ? manager(m, jwt) : auditor(m, jwt), jwt);
    String comment = trimmed(input.comment());
    if (Set.of("RETURN", "REJECT", "REOPEN").contains(action) && comment == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    requireInProgress(m);
    Finding before = finding(m.id, findingId);
    if (before.version() != input.version()) throw new ResponseStatusException(HttpStatus.CONFLICT);
    String expected = action.equals("REOPEN") ? "VALIDATED" : action.equals("SUBMIT") ? "DRAFT" : "SUBMITTED";
    if (!before.status().equals(expected)) throw new ResponseStatusException(HttpStatus.CONFLICT);
    if (decision && !action.equals("REOPEN") && jwt.getSubject().equals(before.submittedBy()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    String logged;
    switch (action) {
      case "SUBMIT" -> {
        if (before.cause() == null
            || before.recommendation() == null
            || before.controls().stream().noneMatch(c -> "NON_CONFORMING".equals(c.outcome())))
          throw new ResponseStatusException(HttpStatus.CONFLICT);
        jdbc.update(
            "UPDATE mission_findings SET status='SUBMITTED',ever_submitted=true,submitted_by=?,submitted_at=now(),"
                + "decided_by=NULL,decided_at=NULL,decision_comment=NULL,version=version+1 WHERE id=? AND mission_id=?",
            jwt.getSubject(),
            findingId,
            m.id);
        logged = "SUBMITTED";
      }
      case "WITHDRAW" -> {
        jdbc.update(
            "UPDATE mission_findings SET status='DRAFT',submitted_by=NULL,submitted_at=NULL,version=version+1"
                + " WHERE id=? AND mission_id=?",
            findingId,
            m.id);
        logged = "WITHDRAWN";
      }
      default -> {
        String status = action.equals("VALIDATE") ? "VALIDATED" : action.equals("REJECT") ? "REJECTED" : "DRAFT";
        jdbc.update(
            "UPDATE mission_findings SET status=?,decided_by=?,decided_at=now(),decision_comment=?,version=version+1"
                + " WHERE id=? AND mission_id=?",
            status,
            jwt.getSubject(),
            comment,
            findingId,
            m.id);
        logged =
            switch (action) {
              case "VALIDATE" -> "VALIDATED";
              case "RETURN" -> "RETURNED";
              case "REJECT" -> "REJECTED";
              default -> "REOPENED";
            };
      }
    }
    Finding after = finding(m.id, findingId);
    log(m.id, findingId, jwt, logged, after.version(), comment, override, before, after);
    return after;
  }
}
