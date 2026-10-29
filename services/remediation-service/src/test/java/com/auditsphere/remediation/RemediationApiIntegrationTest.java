package com.auditsphere.remediation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {"spring.flyway.enabled=true", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class RemediationApiIntegrationTest {
  static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
  static final com.sun.net.httpserver.HttpServer auditSecurity;

  static {
    try {
      auditSecurity =
          com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      auditSecurity.createContext(
          "/account/security",
          exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      auditSecurity.start();
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void auditSecurityUrl(org.springframework.test.context.DynamicPropertyRegistry properties) {
    properties.add("AUDIT_URL", () -> "http://127.0.0.1:" + auditSecurity.getAddress().getPort());
  }

  @AfterAll
  static void stopAuditSecurity() {
    auditSecurity.stop(0);
  }

  @TestConfiguration
  static class FixedClock {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Container @ServiceConnection
  static PostgreSQLContainer db =
      new PostgreSQLContainer(
          "postgres@sha256:2a0d0fe14825b0939f78a8cad5cd4e6aa68bf94d0e5dd96e24b6d23af4315545");

  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @MockitoBean AuditeeDirectory directory;

  UUID mission;
  UUID followUp;
  String manager, validator, auditor, auditee, otherAuditee;

  @BeforeEach
  void issueMission() {
    mission = UUID.randomUUID();
    String suffix = mission.toString().substring(0, 8);
    manager = "manager-" + suffix;
    validator = "validator-" + suffix;
    auditor = "auditor-" + suffix;
    auditee = "auditee-" + suffix;
    otherAuditee = "auditee-other-" + suffix;
    jdbc.update(
        "INSERT INTO mission_scopes(mission_id,title,owner_subject,validator_subject,issue_cycle,state) VALUES (?,?,?,?,1,'ACTIVE')",
        mission, "Mission synthétique", manager, validator);
    jdbc.update("INSERT INTO mission_scope_auditors(mission_id,subject) VALUES (?,?)", mission, auditor);
    followUp = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO finding_follow_ups(id,mission_id,reference,finding_version,title,severity,condition_text,cause,recommendation,status) VALUES (?,?,1,1,'Constat','HIGH','Condition','Cause','Recommandation','TO_PLAN')",
        followUp, mission);
    when(directory.requireAuditee(anyString()))
        .thenAnswer(i -> new AuditeeDirectory.Person(i.getArgument(0), "Audité", true));
    when(directory.names(any())).thenReturn(Map.of());
  }

  static RequestPostProcessor user(String subject, String... roles) {
    return RemediationIntegrationTest.user(subject, roles);
  }

  RequestPostProcessor managerUser() {
    return user(manager, "AUDIT_MANAGER");
  }

  RequestPostProcessor auditorUser() {
    return user(auditor, "AUDITOR");
  }

  RequestPostProcessor validatorUser() {
    return user(validator, "AUDIT_VALIDATOR");
  }

  RequestPostProcessor auditeeUser() {
    return user(auditee, "AUDITEE");
  }

  JsonNode call(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      Object body,
      RequestPostProcessor as,
      ResultMatcher expected)
      throws Exception {
    if (body != null)
      request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    String content = mvc.perform(request.with(as)).andExpect(expected).andReturn().getResponse().getContentAsString();
    return content.isEmpty() ? null : mapper.readTree(content);
  }

  JsonNode create(RequestPostProcessor as, ResultMatcher expected) throws Exception {
    return call(
        post("/remediation/follow-ups/{id}/actions", followUp),
        Map.of("description", "Mettre à jour la procédure", "ownerSubject", auditee, "dueDate", TODAY.plusMonths(2).toString()),
        as,
        expected);
  }

  JsonNode act(UUID action, String transition, long version, String comment, RequestPostProcessor as, ResultMatcher expected)
      throws Exception {
    var body = new HashMap<String, Object>(Map.of("action", transition, "version", version));
    if (comment != null) body.put("comment", comment);
    return call(put("/remediation/actions/{id}/transition", action), body, as, expected);
  }

  JsonNode close(String transition, long version, String comment, RequestPostProcessor as, ResultMatcher expected)
      throws Exception {
    var body = new HashMap<String, Object>(Map.of("action", transition, "version", version));
    if (comment != null) body.put("comment", comment);
    return call(put("/remediation/follow-ups/{id}/transition", followUp), body, as, expected);
  }

  void attach(UUID action) {
    jdbc.update(
        "INSERT INTO action_attachments(id,action_id,mission_id,object_key,file_name,content_type,size_bytes,sha256,uploaded_by) VALUES (?,?,?,?,'preuve.pdf','application/pdf',10,?,?)",
        UUID.randomUUID(), action, mission, "key-" + UUID.randomUUID(), "a".repeat(64), auditee);
  }

  long followUpVersion() {
    return jdbc.queryForObject("SELECT version FROM finding_follow_ups WHERE id=?", Long.class, followUp);
  }

  String followUpStatus() {
    return jdbc.queryForObject("SELECT status FROM finding_follow_ups WHERE id=?", String.class, followUp);
  }

  @Test
  void nominalPlanFromCreationToClosure() throws Exception {
    var created = create(managerUser(), status().isCreated());
    UUID action = UUID.fromString(created.get("id").asString());
    assertEquals(1, created.get("reference").asInt());
    assertEquals("TODO", created.get("status").asString());
    assertEquals("ACTION_PLAN", followUpStatus());
    assertEquals(
        auditee,
        jdbc.queryForObject(
            "SELECT recipient_subject FROM remediation_outbox WHERE action_id=? AND event_type='audit.action.assigned.v1'",
            String.class,
            action));

    var progressed =
        call(post("/remediation/actions/{id}/progress", action), Map.of("version", 0, "note", "Procédure en relecture"), auditeeUser(), status().isOk());
    assertEquals("IN_PROGRESS", progressed.get("status").asString());
    act(action, "COMPLETE", 1, "Procédure publiée", auditeeUser(), status().isConflict());
    attach(action);
    act(action, "COMPLETE", 1, null, auditeeUser(), status().isBadRequest());
    act(action, "COMPLETE", 1, "Procédure publiée", auditeeUser(), status().isOk());
    act(action, "RETURN", 2, null, auditorUser(), status().isBadRequest());
    act(action, "RETURN", 2, "Version signée attendue", auditorUser(), status().isOk());
    act(action, "COMPLETE", 3, "Version signée jointe", auditeeUser(), status().isOk());
    act(action, "VERIFY", 4, null, auditeeUser(), status().isForbidden());
    act(action, "VERIFY", 3, null, auditorUser(), status().isConflict());
    var verified = act(action, "VERIFY", 4, "Conforme", auditorUser(), status().isOk());
    assertEquals("VERIFIED", verified.get("status").asString());
    assertEquals(auditor, verified.get("verifiedBy").asString());

    close("REQUEST_CLOSURE", followUpVersion(), null, auditorUser(), status().isForbidden());
    close("REQUEST_CLOSURE", followUpVersion(), null, managerUser(), status().isOk());
    // Actions are frozen during the verification.
    act(action, "REOPEN", 5, "Nouvel écart", managerUser(), status().isConflict());
    close("CLOSE", followUpVersion(), null, managerUser(), status().isForbidden());
    close("RETURN", followUpVersion(), null, validatorUser(), status().isBadRequest());
    close("RETURN", followUpVersion(), "Preuve à compléter", validatorUser(), status().isOk());
    assertEquals("ACTION_PLAN", followUpStatus());
    close("REQUEST_CLOSURE", followUpVersion(), null, managerUser(), status().isOk());
    close("CLOSE", followUpVersion() - 1, null, validatorUser(), status().isConflict());
    var closed = close("CLOSE", followUpVersion(), null, validatorUser(), status().isOk());
    assertEquals("CLOSED", closed.get("status").asString());
    close("RETURN", followUpVersion(), "Trop tard", validatorUser(), status().isConflict());

    var history =
        call(get("/remediation/follow-ups/{id}/history", followUp), null, validatorUser(), status().isOk());
    var kinds = new ArrayList<String>();
    history.forEach(h -> kinds.add(h.get("action").asString()));
    assertEquals(
        List.of(
            "CREATED", "PROGRESS", "COMPLETED", "RETURNED", "COMPLETED", "VERIFIED",
            "CLOSURE_REQUESTED", "CLOSURE_RETURNED", "CLOSURE_REQUESTED", "CLOSED"),
        kinds);
    assertFalse(history.get(0).get("override").asBoolean());

    var list = call(get("/remediation/missions/{id}/follow-ups", mission), null, auditorUser(), status().isOk());
    assertEquals("CLOSED", list.get("followUps").get(0).get("status").asString());
    assertEquals(1, list.get("followUps").get(0).get("actions").get(0).get("attachmentCount").asInt());
    var mine = call(get("/remediation/me/actions?status=VERIFIED"), null, auditeeUser(), status().isOk());
    assertEquals(1, mine.size());
  }

  @Test
  void scopeAndRolesAreCheckedOnEveryRoute() throws Exception {
    UUID action = UUID.fromString(create(managerUser(), status().isCreated()).get("id").asString());
    var outsider = user("someone-else", "AUDIT_MANAGER", "AUDITOR", "AUDIT_VALIDATOR");
    call(get("/remediation/missions/{id}/follow-ups", mission), null, outsider, status().isNotFound());
    call(get("/remediation/follow-ups/{id}/history", followUp), null, outsider, status().isNotFound());
    call(get("/remediation/actions/{id}", action), null, outsider, status().isNotFound());
    create(outsider, status().isNotFound());
    act(action, "VERIFY", 0, null, outsider, status().isNotFound());
    // The auditee sees its own action only, never the mission.
    call(get("/remediation/missions/{id}/follow-ups", mission), null, auditeeUser(), status().isNotFound());
    var detail = call(get("/remediation/actions/{id}", action), null, auditeeUser(), status().isOk());
    assertEquals("Condition", detail.get("finding").get("conditionText").asString());
    call(get("/remediation/actions/{id}", action), null, user(otherAuditee, "AUDITEE"), status().isNotFound());
    // Holding the subject without the AUDITEE role is not enough.
    call(get("/remediation/actions/{id}", action), null, user(auditee, "AUDITOR"), status().isNotFound());
    call(put("/remediation/actions/{id}", action), Map.of("version", 0, "description", "Autre"), auditeeUser(), status().isForbidden());
    call(post("/remediation/actions/{id}/progress", action), Map.of("version", 0, "note", "Note"), auditorUser(), status().isForbidden());
    create(auditorUser(), status().isForbidden());
    create(validatorUser(), status().isForbidden());
    act(action, "CANCEL", 0, "Motif", auditorUser(), status().isForbidden());
    call(get("/remediation/directory/auditees?q=ali"), null, auditorUser(), status().isForbidden());
    when(directory.search("ali")).thenReturn(List.of(new AuditeeDirectory.Person("p1", "Alice", true)));
    var found = call(get("/remediation/directory/auditees?q=ali"), null, managerUser(), status().isOk());
    assertEquals("Alice", found.get(0).get("name").asString());
    call(get("/remediation/me/actions?status=WRONG"), null, auditeeUser(), status().isBadRequest());
  }

  @Test
  void assignmentRulesAndBounds() throws Exception {
    var body = new HashMap<String, Object>(Map.of("description", "Action", "dueDate", TODAY.plusDays(1).toString()));
    for (String member : List.of(manager, validator, auditor)) {
      body.put("ownerSubject", member);
      call(post("/remediation/follow-ups/{id}/actions", followUp), body, managerUser(), status().isBadRequest());
    }
    when(directory.requireAuditee("not-auditee")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));
    body.put("ownerSubject", "not-auditee");
    call(post("/remediation/follow-ups/{id}/actions", followUp), body, managerUser(), status().isBadRequest());
    body.put("ownerSubject", auditee);
    body.put("dueDate", TODAY.minusDays(1).toString());
    call(post("/remediation/follow-ups/{id}/actions", followUp), body, managerUser(), status().isBadRequest());
    body.put("dueDate", TODAY.plusYears(5).plusDays(1).toString());
    call(post("/remediation/follow-ups/{id}/actions", followUp), body, managerUser(), status().isBadRequest());
    body.put("dueDate", TODAY.toString());
    body.put("description", "  ");
    call(post("/remediation/follow-ups/{id}/actions", followUp), body, managerUser(), status().isBadRequest());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM corrective_actions WHERE follow_up_id=?", Integer.class, followUp));

    for (int i = 0; i < FollowUpController.MAX_ACTIONS; i++) create(managerUser(), status().isCreated());
    create(managerUser(), status().isConflict());
    assertEquals(
        20,
        jdbc.queryForObject("SELECT max(reference) FROM corrective_actions WHERE follow_up_id=?", Integer.class, followUp));
  }

  @Test
  void reassignmentPostponementCancellationAndReopening() throws Exception {
    UUID action = UUID.fromString(create(managerUser(), status().isCreated()).get("id").asString());
    var postpone = new HashMap<String, Object>(Map.of("version", 0, "dueDate", TODAY.plusMonths(4).toString()));
    call(put("/remediation/actions/{id}", action), postpone, managerUser(), status().isBadRequest());
    postpone.put("reason", "Arbitrage budgétaire");
    var postponed = call(put("/remediation/actions/{id}", action), postpone, managerUser(), status().isOk());
    assertEquals(1, postponed.get("dueDateChanges").asInt());
    call(put("/remediation/actions/{id}", action), postpone, managerUser(), status().isConflict());

    var reassigned =
        call(put("/remediation/actions/{id}", action), Map.of("version", 1, "ownerSubject", otherAuditee), managerUser(), status().isOk());
    assertEquals(otherAuditee, reassigned.get("ownerSubject").asString());
    // The previous owner loses access at once, the new one is notified.
    call(get("/remediation/actions/{id}", action), null, auditeeUser(), status().isNotFound());
    call(get("/remediation/actions/{id}", action), null, user(otherAuditee, "AUDITEE"), status().isOk());
    assertEquals(
        List.of(auditee, otherAuditee),
        jdbc.queryForList(
            "SELECT recipient_subject FROM remediation_outbox WHERE action_id=? ORDER BY aggregate_version",
            String.class,
            action));

    act(action, "CANCEL", 2, null, managerUser(), status().isBadRequest());
    act(action, "CANCEL", 2, "Doublon", managerUser(), status().isOk());
    assertEquals("TO_PLAN", followUpStatus());

    UUID second = UUID.fromString(create(managerUser(), status().isCreated()).get("id").asString());
    assertEquals(2, jdbc.queryForObject("SELECT reference FROM corrective_actions WHERE id=?", Integer.class, second));
    attach(second);
    act(second, "COMPLETE", 0, "Fait", auditeeUser(), status().isOk());
    act(second, "VERIFY", 1, null, managerUser(), status().isOk());
    act(second, "CANCEL", 2, "Trop tard", managerUser(), status().isConflict());
    act(second, "REOPEN", 2, null, managerUser(), status().isBadRequest());
    var reopened = act(second, "REOPEN", 2, "Écart constaté", managerUser(), status().isOk());
    assertEquals("IN_PROGRESS", reopened.get("status").asString());
    assertTrue(reopened.get("verifiedBy").isNull());
    assertEquals(
        List.of("CREATED", "DUE_DATE_CHANGED", "REASSIGNED", "CANCELLED", "CREATED", "COMPLETED", "VERIFIED", "REOPENED"),
        jdbc.queryForList(
            "SELECT action FROM remediation_change_log WHERE follow_up_id=? ORDER BY occurred_at, entity_version",
            String.class,
            followUp));
  }

  @Test
  void separationOfDutiesHoldsEvenForOverrides() throws Exception {
    var admin = user("admin-" + mission, "SUPER_ADMIN");
    var created = create(admin, status().isCreated());
    UUID action = UUID.fromString(created.get("id").asString());
    assertTrue(
        jdbc.queryForObject(
            "SELECT override FROM remediation_change_log WHERE action_id=? AND action='CREATED'", Boolean.class, action));
    attach(action);
    act(action, "COMPLETE", 0, "Fait", admin, status().isForbidden());
    act(action, "COMPLETE", 0, "Fait", auditeeUser(), status().isOk());
    // An action owner who is also an auditor of another kind still cannot verify it.
    act(action, "VERIFY", 1, null, user(auditee, "AUDITEE", "SUPER_ADMIN"), status().isForbidden());
    act(action, "VERIFY", 1, null, admin, status().isOk());
    close("REQUEST_CLOSURE", followUpVersion(), null, admin, status().isOk());
    close("CLOSE", followUpVersion(), null, admin, status().isForbidden());
    close("CLOSE", followUpVersion(), null, user("admin-two", "SUPER_ADMIN"), status().isOk());

    // No designated validator after an override approval: only a SUPER_ADMIN closes.
    jdbc.update("UPDATE mission_scopes SET validator_subject=NULL WHERE mission_id=?", mission);
    jdbc.update("UPDATE finding_follow_ups SET status='VERIFICATION', closure_requested_by=?, closure_requested_at=now(), closed_by=NULL, closed_at=NULL WHERE id=?", manager, followUp);
    close("CLOSE", followUpVersion(), null, validatorUser(), status().isNotFound());
    close("CLOSE", followUpVersion(), null, admin, status().isOk());
  }

  @Test
  void writesNeedAnActiveMissionAndOpenFollowUp() throws Exception {
    UUID action = UUID.fromString(create(managerUser(), status().isCreated()).get("id").asString());
    jdbc.update("UPDATE mission_scopes SET state='SUSPENDED' WHERE mission_id=?", mission);
    create(managerUser(), status().isConflict());
    call(post("/remediation/actions/{id}/progress", action), Map.of("version", 0, "note", "Note"), auditeeUser(), status().isConflict());
    // Suspended missions stay readable for the team.
    call(get("/remediation/missions/{id}/follow-ups", mission), null, managerUser(), status().isOk());
    jdbc.update("UPDATE mission_scopes SET state='WITHDRAWN' WHERE mission_id=?", mission);
    call(get("/remediation/missions/{id}/follow-ups", mission), null, managerUser(), status().isNotFound());
    call(get("/remediation/actions/{id}", action), null, auditeeUser(), status().isNotFound());
  }
}
