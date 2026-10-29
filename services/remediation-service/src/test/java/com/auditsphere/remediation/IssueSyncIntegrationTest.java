package com.auditsphere.remediation;

import static org.junit.jupiter.api.Assertions.*;

import com.auditsphere.remediation.IssueSnapshotClient.IssueSnapshot;
import com.auditsphere.remediation.IssueSnapshotClient.IssuedFinding;
import com.auditsphere.remediation.IssueSnapshotClient.SnapshotUnavailable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.flyway.enabled=true",
      "spring.kafka.listener.auto-startup=false",
      "REMEDIATION_SYNC_CLIENT_SECRET=test-sync-secret"
    })
@Testcontainers
class IssueSyncIntegrationTest {
  /** Stands for audit-service and Keycloak: snapshots by "missionId/cycle", then a token endpoint. */
  static final com.sun.net.httpserver.HttpServer upstream;

  static final Map<String, String> snapshots = new ConcurrentHashMap<>();
  static volatile int snapshotStatus = 200;
  static final AtomicInteger snapshotReads = new AtomicInteger();
  static final AtomicInteger tokenRequests = new AtomicInteger();
  static volatile String lastAuthorization;
  static volatile String lastTokenForm;

  static {
    try {
      upstream =
          com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      upstream.createContext(
          "/internal/missions/",
          exchange -> {
            snapshotReads.incrementAndGet();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            String[] path = exchange.getRequestURI().getPath().split("/");
            String body = snapshots.get(path[3] + "/" + path[5]);
            int status = snapshotStatus != 200 ? snapshotStatus : body == null ? 409 : 200;
            reply(exchange, status, status == 200 ? body : null);
          });
      upstream.createContext(
          "/realms/auditsphere/protocol/openid-connect/token",
          exchange -> {
            tokenRequests.incrementAndGet();
            lastTokenForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(
                exchange,
                200,
                "{\"access_token\":\"sync-token-" + tokenRequests.get() + "\",\"expires_in\":300}");
          });
      upstream.start();
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    if (body == null) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  @org.springframework.test.context.DynamicPropertySource
  static void upstreamUrls(org.springframework.test.context.DynamicPropertyRegistry properties) {
    String url = "http://127.0.0.1:" + upstream.getAddress().getPort();
    properties.add("AUDIT_URL", () -> url);
    properties.add("KEYCLOAK_URL", () -> url);
  }

  @AfterAll
  static void stopUpstream() {
    upstream.stop(0);
  }

  @AfterEach
  void restoreUpstream() {
    snapshotStatus = 200;
  }

  @Container @ServiceConnection
  static PostgreSQLContainer db =
      new PostgreSQLContainer(
          "postgres@sha256:2a0d0fe14825b0939f78a8cad5cd4e6aa68bf94d0e5dd96e24b6d23af4315545");

  @Autowired FindingsIssueConsumer consumer;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  void issueCreatesScopeAndFollowUpsAndRedeliveryAddsNothing() throws Exception {
    UUID mission = UUID.randomUUID();
    var first = finding(1, 1);
    var second = finding(2, 3);
    publish(mission, 1, "validator", List.of(first, second));
    String event = issued(UUID.randomUUID(), mission, 1);

    consumer.receive(event);
    consumer.receive(event);

    var scope = jdbc.queryForMap("SELECT * FROM mission_scopes WHERE mission_id=?", mission);
    assertEquals("ACTIVE", scope.get("state"));
    assertEquals(1, scope.get("issue_cycle"));
    assertEquals("owner", scope.get("owner_subject"));
    assertEquals("validator", scope.get("validator_subject"));
    assertEquals(
        List.of("auditor-a", "auditor-b"),
        jdbc.queryForList(
            "SELECT subject FROM mission_scope_auditors WHERE mission_id=? ORDER BY subject",
            String.class,
            mission));
    assertEquals("TO_PLAN", status(first.id()));
    assertEquals(3L, jdbc.queryForObject("SELECT finding_version FROM finding_follow_ups WHERE id=?", Long.class, second.id()));
    assertEquals(
        "Condition 2",
        jdbc.queryForObject("SELECT condition_text FROM finding_follow_ups WHERE id=?", String.class, second.id()));
    assertEquals(2, log(mission, "ISSUED"));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM remediation_change_log WHERE mission_id=? AND actor_subject='audit-service'",
            Integer.class,
            mission));
    // The redelivered record is skipped before any snapshot read.
    assertEquals(1, snapshotReads.get() - readsBefore);
    assertTrue(lastAuthorization.startsWith("Bearer sync-token-"));
    assertTrue(lastTokenForm.contains("client_id=remediation-service"));
    assertTrue(lastTokenForm.contains("client_secret=test-sync-secret"));

    // Another event for the same cycle reads the same snapshot and changes nothing.
    consumer.receive(issued(UUID.randomUUID(), mission, 1));
    assertEquals(2, log(mission, "ISSUED"));
    assertEquals(0L, jdbc.queryForObject("SELECT version FROM finding_follow_ups WHERE id=?", Long.class, first.id()));
    assertEquals(2, processed(mission));
  }

  @Test
  void reopenSuspendsThenNextCycleRestoresAndWithdrawsAbsentFindings() throws Exception {
    UUID mission = UUID.randomUUID();
    var kept = finding(1, 1);
    var dropped = finding(2, 1);
    publish(mission, 1, "validator", List.of(kept, dropped));
    consumer.receive(issued(UUID.randomUUID(), mission, 1));
    jdbc.update("UPDATE finding_follow_ups SET status='ACTION_PLAN' WHERE id=?", kept.id());

    consumer.receive(withdrawn(UUID.randomUUID(), mission, 1, "REOPENED"));
    assertEquals("SUSPENDED", scopeState(mission));
    assertEquals("SUSPENDED", status(kept.id()));
    assertEquals(
        "ACTION_PLAN",
        jdbc.queryForObject("SELECT status_before_suspension FROM finding_follow_ups WHERE id=?", String.class, kept.id()));
    assertEquals(2, log(mission, "SUSPENDED"));

    // The issue of cycle 1 arriving late on its own topic is stale: no snapshot is read.
    snapshotStatus = 500;
    consumer.receive(issued(UUID.randomUUID(), mission, 1));
    snapshotStatus = 200;
    assertEquals("SUSPENDED", scopeState(mission));

    var revised = new IssuedFinding(kept.id(), 2, 1, "Constat révisé", "CRITICAL", "Condition révisée", "Cause", "Recommandation");
    var added = finding(3, 1);
    publish(mission, 2, "validator", List.of(revised, added));
    consumer.receive(issued(UUID.randomUUID(), mission, 2));

    assertEquals("ACTIVE", scopeState(mission));
    assertEquals(2, jdbc.queryForObject("SELECT issue_cycle FROM mission_scopes WHERE mission_id=?", Integer.class, mission));
    var restored = jdbc.queryForMap("SELECT * FROM finding_follow_ups WHERE id=?", kept.id());
    assertEquals("ACTION_PLAN", restored.get("status"));
    assertNull(restored.get("status_before_suspension"));
    assertEquals("Constat révisé", restored.get("title"));
    assertEquals("CRITICAL", restored.get("severity"));
    assertEquals(2L, restored.get("finding_version"));
    assertEquals(2L, restored.get("version"));
    assertEquals("WITHDRAWN", status(dropped.id()));
    assertEquals("TO_PLAN", status(added.id()));
    assertEquals(1, log(mission, "WITHDRAWN"));

    // A withdrawal of an older cycle no longer applies.
    consumer.receive(withdrawn(UUID.randomUUID(), mission, 1, "REOPENED"));
    assertEquals("ACTIVE", scopeState(mission));
    assertEquals("ACTION_PLAN", status(kept.id()));

    // A finding validated again in a later cycle comes back, with its actions if any.
    consumer.receive(withdrawn(UUID.randomUUID(), mission, 2, "REOPENED"));
    jdbc.update(
        """
        INSERT INTO corrective_actions(id, follow_up_id, mission_id, reference, description, owner_subject, due_date,
          status, created_by, updated_by)
        VALUES (?, ?, ?, 1, 'Action', 'auditee', DATE '2026-12-01', 'TODO', 'owner', 'owner')
        """,
        UUID.randomUUID(),
        dropped.id(),
        mission);
    publish(mission, 3, "validator", List.of(revised, finding(2, dropped.id(), 2)));
    consumer.receive(issued(UUID.randomUUID(), mission, 3));
    assertEquals("ACTION_PLAN", status(kept.id()));
    assertEquals("ACTION_PLAN", status(dropped.id()));
    assertEquals("WITHDRAWN", status(added.id()));
  }

  @Test
  void unavailableAuditServiceLeavesTheEventToRetry() throws Exception {
    UUID mission = UUID.randomUUID();
    publish(mission, 1, "validator", List.of(finding(1, 1)));
    String event = issued(UUID.randomUUID(), mission, 1);

    snapshotStatus = 503;
    assertThrows(SnapshotUnavailable.class, () -> consumer.receive(event));
    assertEquals(0, processed(mission));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM mission_scopes WHERE mission_id=?", Integer.class, mission));

    // A rejected token is dropped, the next attempt asks Keycloak for a new one.
    snapshotStatus = 401;
    int tokens = tokenRequests.get();
    assertThrows(SnapshotUnavailable.class, () -> consumer.receive(event));
    snapshotStatus = 200;
    consumer.receive(event);
    assertEquals(tokens + 1, tokenRequests.get());
    assertEquals("ACTIVE", scopeState(mission));
    assertEquals(1, processed(mission));
  }

  @Test
  void withdrawalFirstAndConflictsAreAcknowledgedWithoutScope() throws Exception {
    UUID mission = UUID.randomUUID();
    consumer.receive(withdrawn(UUID.randomUUID(), mission, 1, "REOPENED"));
    // No snapshot for this cycle: audit-service answers 409, a newer event will follow.
    consumer.receive(issued(UUID.randomUUID(), mission, 1));
    snapshotStatus = 404;
    consumer.receive(issued(UUID.randomUUID(), mission, 2));
    assertEquals(3, processed(mission));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM mission_scopes WHERE mission_id=?", Integer.class, mission));
  }

  @Test
  void deletionWithdrawsTheMissionAndItsFollowUps() throws Exception {
    UUID mission = UUID.randomUUID();
    var finding = finding(1, 1);
    publish(mission, 1, null, List.of(finding));
    consumer.receive(issued(UUID.randomUUID(), mission, 1));
    // Approved by a SUPER_ADMIN override: no validator (migration V2).
    assertNull(jdbc.queryForObject("SELECT validator_subject FROM mission_scopes WHERE mission_id=?", String.class, mission));

    consumer.receive(withdrawn(UUID.randomUUID(), mission, 1, "DELETED"));
    assertEquals("WITHDRAWN", scopeState(mission));
    assertEquals("WITHDRAWN", status(finding.id()));
    assertEquals(
        "DELETED",
        jdbc.queryForObject(
            "SELECT reason FROM remediation_change_log WHERE follow_up_id=? AND action='WITHDRAWN'",
            String.class,
            finding.id()));
  }

  @Test
  void malformedEventsAndSnapshotsAreRejectedWithoutChanges() throws Exception {
    UUID mission = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () -> consumer.receive(null));
    assertThrows(IllegalArgumentException.class, () -> consumer.receive("null"));
    assertThrows(JacksonException.class, () -> consumer.receive("{"));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.receive(issued(UUID.randomUUID(), mission, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.receive(withdrawn(UUID.randomUUID(), mission, 1, "ARCHIVED")));

    var invalid = new IssuedFinding(UUID.randomUUID(), 1, 1, "Constat", "SEVERE", "Condition", "Cause", "Recommandation");
    publish(mission, 1, "validator", List.of(invalid));
    assertThrows(IllegalArgumentException.class, () -> consumer.receive(issued(UUID.randomUUID(), mission, 1)));
    snapshots.put(
        mission + "/1",
        mapper.writeValueAsString(
            new IssueSnapshot(UUID.randomUUID(), 1, "Mission", "owner", null, List.of(), List.of())));
    assertThrows(IllegalArgumentException.class, () -> consumer.receive(issued(UUID.randomUUID(), mission, 1)));
    assertEquals(0, processed(mission));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM mission_scopes WHERE mission_id=?", Integer.class, mission));
  }

  int readsBefore;

  @BeforeEach
  void countReads() {
    readsBefore = snapshotReads.get();
  }

  IssuedFinding finding(int reference, long version) {
    return finding(reference, UUID.randomUUID(), version);
  }

  IssuedFinding finding(int reference, UUID id, long version) {
    return new IssuedFinding(
        id, version, reference, "Constat " + reference, "HIGH", "Condition " + reference, "Cause", "Recommandation");
  }

  void publish(UUID mission, int cycle, String validator, List<IssuedFinding> findings) {
    snapshots.put(
        mission + "/" + cycle,
        mapper.writeValueAsString(
            new IssueSnapshot(
                mission, cycle, "Mission " + cycle, "owner", validator, List.of("auditor-b", "auditor-a"), findings)));
  }

  String issued(UUID eventId, UUID mission, int cycle) {
    sent.computeIfAbsent(mission, m -> new ArrayList<>()).add(eventId);
    return mapper.writeValueAsString(
        Map.of(
            "eventId", eventId,
            "eventType", IssueEvent.ISSUED,
            "eventVersion", 1,
            "missionId", mission,
            "issueCycle", cycle,
            "aggregateVersion", 4,
            "occurredAt", "2026-10-29T10:00:00Z"));
  }

  String withdrawn(UUID eventId, UUID mission, int cycle, String reason) {
    sent.computeIfAbsent(mission, m -> new ArrayList<>()).add(eventId);
    return mapper.writeValueAsString(
        Map.of(
            "eventId", eventId,
            "eventType", IssueEvent.WITHDRAWN,
            "eventVersion", 1,
            "missionId", mission,
            "issueCycle", cycle,
            "aggregateVersion", 5,
            "occurredAt", "2026-10-29T11:00:00Z",
            "reason", reason));
  }

  String status(UUID followUp) {
    return jdbc.queryForObject("SELECT status FROM finding_follow_ups WHERE id=?", String.class, followUp);
  }

  String scopeState(UUID mission) {
    return jdbc.queryForObject("SELECT state FROM mission_scopes WHERE mission_id=?", String.class, mission);
  }

  int log(UUID mission, String action) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM remediation_change_log WHERE mission_id=? AND action=?", Integer.class, mission, action);
  }

  /** processed_events has no mission column: a test counts the events it sent for its mission. */
  int processed(UUID mission) {
    var done = new HashSet<>(jdbc.queryForList("SELECT event_id FROM processed_events", UUID.class));
    return (int) sent.getOrDefault(mission, List.of()).stream().filter(done::contains).count();
  }

  final Map<UUID, List<UUID>> sent = new ConcurrentHashMap<>();
}
