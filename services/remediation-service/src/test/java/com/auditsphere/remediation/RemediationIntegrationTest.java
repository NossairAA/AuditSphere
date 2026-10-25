package com.auditsphere.remediation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class RemediationIntegrationTest {
  static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
  static final com.sun.net.httpserver.HttpServer auditSecurity;
  static volatile int securityStatus = 200;

  static {
    try {
      auditSecurity =
          com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      auditSecurity.createContext(
          "/account/security",
          exchange -> {
            exchange.sendResponseHeaders(securityStatus, -1);
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
      return Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Container @ServiceConnection
  static PostgreSQLContainer db =
      new PostgreSQLContainer(
          "postgres@sha256:2a0d0fe14825b0939f78a8cad5cd4e6aa68bf94d0e5dd96e24b6d23af4315545");

  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  @AfterEach
  void restoreSecurity() {
    securityStatus = 200;
  }

  static RequestPostProcessor user(String subject, String... roles) {
    var authorities =
        java.util.Arrays.stream(roles)
            .map(r -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
            .toList();
    return jwt().jwt(j -> j.subject(subject)).authorities(authorities);
  }

  UUID mission(String state) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission_scopes(mission_id,title,owner_subject,validator_subject,issue_cycle,state) VALUES (?,?,?,?,1,?)",
        id, "Mission synthétique", "manager-" + id, "validator-" + id, state);
    return id;
  }

  UUID followUp(UUID mission, int reference, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO finding_follow_ups(id,mission_id,reference,finding_version,title,severity,condition_text,cause,recommendation,status) VALUES (?,?,?,1,'Constat','HIGH','Condition','Cause','Recommandation',?)",
        id, mission, reference, status);
    return id;
  }

  UUID action(UUID mission, UUID followUp, int reference, String owner, LocalDate due, String status) {
    UUID id = UUID.randomUUID();
    boolean done = status.equals("DONE") || status.equals("VERIFIED");
    jdbc.update(
        "INSERT INTO corrective_actions(id,follow_up_id,mission_id,reference,description,owner_subject,due_date,status,created_by,updated_by,completion_note,completed_at,verified_by,verified_at) VALUES (?,?,?,?,'Action synthétique',?,?,?,'manager','manager',?,?,?,?)",
        id, followUp, mission, reference, owner, due, status,
        done ? "Réalisée" : null, done ? java.sql.Timestamp.from(Instant.now()) : null,
        status.equals("VERIFIED") ? "auditor" : null,
        status.equals("VERIFIED") ? java.sql.Timestamp.from(Instant.now()) : null);
    return id;
  }

  @Test
  void migrationCreatesTheSchemaOnAnEmptyDatabase() {
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success", Integer.class));
    var tables =
        jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history' ORDER BY tablename",
            String.class);
    assertEquals(
        java.util.List.of(
            "action_attachments", "action_progress_notes", "action_sequences", "corrective_actions",
            "finding_follow_ups", "mission_scope_auditors", "mission_scopes", "processed_events",
            "remediation_change_log", "remediation_outbox"),
        tables);
  }

  @Test
  void databaseRejectsInconsistentRows() {
    UUID mission = mission("ACTIVE");
    UUID other = mission("ACTIVE");
    UUID followUp = followUp(mission, 1, "ACTION_PLAN");
    UUID action = action(mission, followUp, 1, "auditee", TODAY, "TODO");

    // An action cannot be attached to a follow-up of another mission.
    assertThrows(DataIntegrityViolationException.class, () -> action(other, followUp, 2, "auditee", TODAY, "TODO"));
    // Completion needs a note, cancellation a reason, and the owner never verifies.
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE corrective_actions SET status='DONE' WHERE id=?", action));
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE corrective_actions SET status='CANCELLED' WHERE id=?", action));
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE corrective_actions SET status='CANCELLED', cancel_reason='  ' WHERE id=?", action));
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE corrective_actions SET verified_by=owner_subject WHERE id=?", action));
    assertThrows(DataIntegrityViolationException.class, () -> action(mission, followUp, 1, "other", TODAY, "TODO"));
    // The person who requested closure cannot close, and suspension keeps the previous status.
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update(
            "UPDATE finding_follow_ups SET status='CLOSED', closure_requested_by='manager', closure_requested_at=now(), closed_by='manager', closed_at=now() WHERE id=?",
            followUp));
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE finding_follow_ups SET status='VERIFICATION' WHERE id=?", followUp));
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE finding_follow_ups SET status='SUSPENDED' WHERE id=?", followUp));
    assertEquals(1, jdbc.update(
        "UPDATE finding_follow_ups SET status='SUSPENDED', status_before_suspension='ACTION_PLAN' WHERE id=?", followUp));
    // Reasons are required in the trace for returns, and attachments are bounded.
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update(
            "INSERT INTO remediation_change_log(id,mission_id,action_id,actor_subject,action,entity_version) VALUES (?,?,?,'auditor','RETURNED',1)",
            UUID.randomUUID(), mission, action));
    assertThrows(DataIntegrityViolationException.class,
        () -> jdbc.update(
            "INSERT INTO action_attachments(id,action_id,mission_id,object_key,file_name,content_type,size_bytes,sha256,uploaded_by) VALUES (?,?,?,?,'a.exe','application/x-msdownload',10,?,'auditee')",
            UUID.randomUUID(), action, mission, "k-" + UUID.randomUUID(), "a".repeat(64)));
  }

  @Test
  void auditeeSeesOnlyOwnActionsWithDerivedLateness() throws Exception {
    String auditee = "auditee-" + UUID.randomUUID();
    UUID mission = mission("ACTIVE");
    UUID followUp = followUp(mission, 1, "ACTION_PLAN");
    UUID late = action(mission, followUp, 1, auditee, TODAY.minusDays(1), "TODO");
    UUID doneLate = action(mission, followUp, 2, auditee, TODAY.minusDays(3), "DONE");
    action(mission, followUp, 3, "someone-else", TODAY.minusDays(1), "TODO");
    UUID withdrawn = followUp(mission, 2, "WITHDRAWN");
    action(mission, withdrawn, 1, auditee, TODAY.plusDays(5), "TODO");
    UUID dueToday = action(mission, followUp, 4, auditee, TODAY, "IN_PROGRESS");

    mvc.perform(get("/remediation/me/actions").with(user(auditee, "AUDITEE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(doneLate.toString()))
        .andExpect(jsonPath("$[0].overdue").value(false))
        .andExpect(jsonPath("$[1].id").value(late.toString()))
        .andExpect(jsonPath("$[1].overdue").value(true))
        .andExpect(jsonPath("$[1].findingReference").value(1))
        .andExpect(jsonPath("$[2].id").value(dueToday.toString()))
        .andExpect(jsonPath("$[2].overdue").value(false));
  }

  @Test
  void accessIsLimitedToAuthenticatedHumanRoles() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/remediation/me/actions")).andExpect(status().isUnauthorized());
    mvc.perform(get("/remediation/me/actions").header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/remediation/me/actions").with(user("auditor", "AUDITOR")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/remediation/me/actions").with(user("service", "REMEDIATION_SYNC")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/remediation/missions/" + UUID.randomUUID() + "/follow-ups").with(user("service", "REMEDIATION_SYNC")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/internal/anything").with(user("auditee", "AUDITEE")))
        .andExpect(status().isForbidden());
  }

  @Test
  void revokedOrUnverifiableAccountIsRefused() throws Exception {
    securityStatus = 401;
    mvc.perform(get("/remediation/me/actions").with(user("revoked", "AUDITEE")))
        .andExpect(status().isUnauthorized());
    securityStatus = 500;
    mvc.perform(get("/remediation/me/actions").with(user("auditee", "AUDITEE")))
        .andExpect(status().isServiceUnavailable());
  }
}
