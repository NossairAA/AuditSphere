package com.auditsphere.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/missions/{missionId}")
class AuditProgramController {
  private final MissionRepository missions;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  AuditProgramController(MissionRepository missions, JdbcTemplate jdbc, ObjectMapper json) {
    this.missions = missions;
    this.jdbc = jdbc;
    this.json = json;
  }

  record Risk(UUID id, String title, String description, int probability, int impact) {}

  record Control(UUID id, String objective, String procedure, Set<UUID> riskIds) {}

  record Program(MissionController.MissionView mission, List<Risk> risks, List<Control> controls) {}

  record RiskInput(
      @NotNull @PositiveOrZero Long version,
      UUID id,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 4000) String description,
      @NotNull @Min(1) @Max(5) Integer probability,
      @NotNull @Min(1) @Max(5) Integer impact) {}

  record ControlInput(
      @NotNull @PositiveOrZero Long version,
      UUID id,
      @NotBlank @Size(max = 1000) String objective,
      @NotBlank @Size(max = 4000) String procedure,
      @NotNull @Size(min = 1, max = 100) Set<@NotNull UUID> riskIds) {}

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

  private Mission editable(UUID id, Jwt jwt, long version) {
    Mission m = accessible(id, jwt, true);
    if (!(role(jwt, "SUPER_ADMIN")
        || (role(jwt, "AUDIT_MANAGER") && m.ownerSubject.equals(jwt.getSubject()))))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (m.version != version || !Set.of("DRAFT", "IN_PROGRESS").contains(m.status))
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    return m;
  }

  private List<Risk> risks(UUID id) {
    return jdbc.query(
        "SELECT id,title,description,probability,impact FROM mission_risks WHERE mission_id=? ORDER BY created_at,id",
        (r, n) ->
            new Risk(
                r.getObject("id", UUID.class),
                r.getString("title"),
                r.getString("description"),
                r.getInt("probability"),
                r.getInt("impact")),
        id);
  }

  private Program program(Mission m) {
    Map<UUID, Set<UUID>> links = new HashMap<>();
    jdbc.query(
        "SELECT control_id,risk_id FROM mission_control_risks WHERE mission_id=?",
        r -> {
          links
              .computeIfAbsent(r.getObject("control_id", UUID.class), k -> new LinkedHashSet<>())
              .add(r.getObject("risk_id", UUID.class));
        },
        m.id);
    var controls =
        jdbc.query(
            "SELECT id,objective,procedure FROM mission_controls WHERE mission_id=? ORDER BY created_at,id",
            (r, n) ->
                new Control(
                    r.getObject("id", UUID.class),
                    r.getString("objective"),
                    r.getString("procedure"),
                    links.getOrDefault(r.getObject("id", UUID.class), Set.of())),
            m.id);
    return new Program(MissionController.MissionView.of(m), risks(m.id), controls);
  }

  @GetMapping("/program")
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Program get(@PathVariable UUID missionId, @AuthenticationPrincipal Jwt jwt) {
    return program(accessible(missionId, jwt, false));
  }

  private String serialized(Object value) {
    if (value == null) return null;
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void changed(
      Mission m, Jwt jwt, UUID target, String resource, Object before, Object after) {
    m.programRevision = UUID.randomUUID();
    missions.saveAndFlush(m);
    jdbc.update(
        "INSERT INTO program_change_log(id,mission_id,target_id,actor_subject,resource,mission_version,previous_data,resulting_data) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))",
        UUID.randomUUID(),
        m.id,
        target,
        jwt.getSubject(),
        resource,
        m.version,
        serialized(before),
        serialized(after));
  }

  @PutMapping("/risks")
  @Transactional(timeout = 5)
  Program risk(
      @PathVariable UUID missionId,
      @Valid @RequestBody RiskInput input,
      @AuthenticationPrincipal Jwt jwt) {
    Mission m = editable(missionId, jwt, input.version());
    var current = risks(m.id);
    Risk before =
        input.id() == null
            ? null
            : current.stream()
                .filter(r -> r.id().equals(input.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (before == null && current.size() >= 100)
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    UUID id = before == null ? UUID.randomUUID() : before.id();
    Risk after =
        new Risk(
            id,
            input.title().strip(),
            input.description().strip(),
            input.probability(),
            input.impact());
    if (before == null)
      jdbc.update(
          "INSERT INTO mission_risks(id,mission_id,title,description,probability,impact) VALUES (?,?,?,?,?,?)",
          id,
          m.id,
          after.title(),
          after.description(),
          after.probability(),
          after.impact());
    else
      jdbc.update(
          "UPDATE mission_risks SET title=?,description=?,probability=?,impact=? WHERE id=? AND mission_id=?",
          after.title(),
          after.description(),
          after.probability(),
          after.impact(),
          id,
          m.id);
    changed(m, jwt, id, "RISK", before, after);
    return program(m);
  }

  @PutMapping("/controls")
  @Transactional(timeout = 5)
  Program control(
      @PathVariable UUID missionId,
      @Valid @RequestBody ControlInput input,
      @AuthenticationPrincipal Jwt jwt) {
    Mission m = editable(missionId, jwt, input.version());
    Program current = program(m);
    var allowed = new HashSet<UUID>();
    current.risks().forEach(r -> allowed.add(r.id()));
    if (!allowed.containsAll(input.riskIds()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    Control before =
        input.id() == null
            ? null
            : current.controls().stream()
                .filter(c -> c.id().equals(input.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (before == null && current.controls().size() >= 100)
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    UUID id = before == null ? UUID.randomUUID() : before.id();
    Control after =
        new Control(
            id, input.objective().strip(), input.procedure().strip(), Set.copyOf(input.riskIds()));
    if (before == null)
      jdbc.update(
          "INSERT INTO mission_controls(id,mission_id,objective,procedure) VALUES (?,?,?,?)",
          id,
          m.id,
          after.objective(),
          after.procedure());
    else
      jdbc.update(
          "UPDATE mission_controls SET objective=?,procedure=? WHERE id=? AND mission_id=?",
          after.objective(),
          after.procedure(),
          id,
          m.id);
    jdbc.update("DELETE FROM mission_control_risks WHERE control_id=? AND mission_id=?", id, m.id);
    for (UUID risk : after.riskIds())
      jdbc.update(
          "INSERT INTO mission_control_risks(mission_id,control_id,risk_id) VALUES (?,?,?)",
          m.id,
          id,
          risk);
    changed(m, jwt, id, "CONTROL", before, after);
    return program(m);
  }
}
