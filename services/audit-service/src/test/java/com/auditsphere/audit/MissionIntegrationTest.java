package com.auditsphere.audit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class MissionIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17.9");

  @org.springframework.test.context.bean.override.mockito.MockitoBean AuditorDirectory directory;

  @org.junit.jupiter.api.BeforeEach
  void directoryFixture() {
    org.mockito.Mockito.when(directory.auditors(0))
        .thenReturn(
            java.util.List.of(
                new AuditorDirectory.Person("assigned", "Auditeur"),
                new AuditorDirectory.Person("new-auditor", "Nouveau")));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive(),
                  "Directory validation must be outside SQL transactions");
              java.util.Set<String> ids = invocation.getArgument(0);
              if (!java.util.Set.of("assigned", "new-auditor").containsAll(ids))
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST);
              return null;
            })
        .when(directory)
        .validate(org.mockito.ArgumentMatchers.anySet());
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  static final com.nimbusds.jose.jwk.RSAKey signingKey;
  static final com.sun.net.httpserver.HttpServer jwks;

  static {
    try {
      signingKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test-key").generate();
      jwks =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      jwks.createContext(
          "/jwks",
          exchange -> {
            byte[] body =
                new com.nimbusds.jose.jwk.JWKSet(signingKey.toPublicJWK())
                    .toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      jwks.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void jwtProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "https://issuer.example.test");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
  }

  @org.junit.jupiter.api.AfterAll
  static void stopJwks() {
    jwks.stop(0);
  }

  String token(String issuer, String audience, long expiresIn) throws Exception {
    var claims =
        new com.nimbusds.jwt.JWTClaimsSet.Builder()
            .subject("owner")
            .issuer(issuer)
            .audience(audience)
            .issueTime(java.util.Date.from(java.time.Instant.now().minusSeconds(300)))
            .expirationTime(java.util.Date.from(java.time.Instant.now().plusSeconds(expiresIn)))
            .claim("realm_access", java.util.Map.of("roles", java.util.List.of("AUDIT_MANAGER")))
            .build();
    var signed =
        new com.nimbusds.jwt.SignedJWT(
            new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID("test-key")
                .build(),
            claims);
    signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(signingKey));
    return signed.serialize();
  }

  @Test
  void validatesRealSignedJwtAndRoleMapping() throws Exception {
    mvc.perform(
            post("/missions")
                .header(
                    "Authorization",
                    "Bearer " + token("https://issuer.example.test", "auditsphere-api", 300))
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isCreated());
  }

  @Test
  void rejectsExpiredWrongIssuerAndWrongAudienceTokens() throws Exception {
    for (String bearer :
        new String[] {
          token("https://issuer.example.test", "auditsphere-api", -120),
          token("https://wrong.example.test", "auditsphere-api", 300),
          token("https://issuer.example.test", "other-api", 300)
        })
      mvc.perform(
              get("/missions/00000000-0000-0000-0000-000000000001")
                  .header("Authorization", "Bearer " + bearer))
          .andExpect(status().isUnauthorized());
  }

  @Test
  void listFiltersBySubjectIncludingAssignmentsAndPaginates() throws Exception {
    String id = create();
    for (String subject : new String[] {"owner", "assigned"}) {
      var response =
          mvc.perform(get("/missions?size=100").with(jwt().jwt(j -> j.subject(subject))))
              .andExpect(status().isOk())
              .andReturn();
      org.junit.jupiter.api.Assertions.assertTrue(
          response.getResponse().getContentAsString().contains(id));
    }
    mvc.perform(
            get("/missions")
                .with(
                    jwt()
                        .jwt(j -> j.subject("unrelated-list-user"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("total").value(0));
    mvc.perform(get("/missions?size=1").with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("items.length()").value(1));
  }

  @Test
  void listRejectsAnonymousAndInvalidPagination() throws Exception {
    mvc.perform(get("/missions")).andExpect(status().isUnauthorized());
    for (String query : new String[] {"page=-1", "size=0", "size=101"})
      mvc.perform(get("/missions?" + query).with(jwt())).andExpect(status().isBadRequest());
  }

  String payload =
      """
 {"title":"Audit achats","description":"Test","perimeter":"Achats","assigneeSubjects":["assigned"]}
 """;

  String create() throws Exception {
    var response =
        mvc.perform(
                post("/missions")
                    .with(
                        jwt()
                            .jwt(j -> j.subject("owner"))
                            .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                    .contentType("application/json")
                    .content(payload))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andReturn();
    return mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void ownerAndAssignedCanReadPersistedMission() throws Exception {
    String id = create();
    for (String subject : new String[] {"owner", "assigned"})
      mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject(subject))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("title").value("Audit achats"));
  }

  @Test
  void unrelatedManagerCannotReadMission() throws Exception {
    String id = create();
    mvc.perform(
            get("/missions/" + id)
                .with(
                    jwt()
                        .jwt(j -> j.subject("outsider"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void ordinaryAuditorCannotCreate() throws Exception {
    mvc.perform(post("/missions").with(jwt()).contentType("application/json").content(payload))
        .andExpect(status().isForbidden());
  }

  @Test
  void anonymousCannotReadOrCreate() throws Exception {
    mvc.perform(get("/missions/00000000-0000-0000-0000-000000000001"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/missions").contentType("application/json").content(payload))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidInputIsRejected() throws Exception {
    mvc.perform(
            post("/missions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content(payload.replace("Audit achats", " ")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ownerReplacesAssignmentsAndRevokesAccessImmediately() throws Exception {
    String id = create();
    mvc.perform(
            put("/missions/" + id + "/assignments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("owner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content("{\"version\":0,\"assigneeSubjects\":[\"new-auditor\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("assigneeSubjects[0]").value("new-auditor"));
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("assigned"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/missions/" + id + "/participants").with(jwt().jwt(j -> j.subject("assigned"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("new-auditor"))))
        .andExpect(status().isOk());
    var list =
        mvc.perform(get("/missions").with(jwt().jwt(j -> j.subject("assigned")))).andReturn();
    org.junit.jupiter.api.Assertions.assertFalse(
        list.getResponse().getContentAsString().contains(id));
    mvc.perform(
            put("/missions/" + id + "/assignments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("owner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content("{\"version\":1,\"assigneeSubjects\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("assigneeSubjects.length()").value(0));
  }

  @Test
  void assignmentMutationRequiresRoleAndOwnership() throws Exception {
    String id = create();
    String endpoint = "/missions/" + id + "/assignments";
    mvc.perform(
            put(endpoint)
                .contentType("application/json")
                .content("{\"version\":0,\"assigneeSubjects\":[]}"))
        .andExpect(status().isUnauthorized());
    for (String subject : new String[] {"assigned", "owner"})
      mvc.perform(
              put(endpoint)
                  .with(jwt().jwt(j -> j.subject(subject)))
                  .contentType("application/json")
                  .content("{\"version\":0,\"assigneeSubjects\":[]}"))
          .andExpect(status().isForbidden());
    mvc.perform(
            put(endpoint)
                .with(
                    jwt()
                        .jwt(j -> j.subject("other-manager"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content("{\"version\":0,\"assigneeSubjects\":[]}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsUnknownAuditorsAndDirectoryOutageWithoutChangingAssignments() throws Exception {
    String id = create();
    mvc.perform(
            put("/missions/" + id + "/assignments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("owner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content("{\"version\":0,\"assigneeSubjects\":[\"unknown\"]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/missions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content(payload.replace("assigned", "unknown")))
        .andExpect(status().isBadRequest());
    org.mockito.Mockito.doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
        .when(directory)
        .validate(org.mockito.ArgumentMatchers.anySet());
    mvc.perform(
            put("/missions/" + id + "/assignments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("owner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content("{\"version\":0,\"assigneeSubjects\":[\"new-auditor\"]}"))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("assigned"))))
        .andExpect(status().isOk());
  }

  @Test
  void directoryIsReservedForManagers() throws Exception {
    mvc.perform(get("/missions/auditors")).andExpect(status().isUnauthorized());
    mvc.perform(get("/missions/auditors").with(jwt())).andExpect(status().isForbidden());
    mvc.perform(
            get("/missions/auditors")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("[0].name").value("Auditeur"));
  }

  @Test
  void staleAssignmentVersionIsRejected() throws Exception {
    String id = create();
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(
              put("/missions/" + id + "/assignments")
                  .with(
                      jwt()
                          .jwt(j -> j.subject("owner"))
                          .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                  .contentType("application/json")
                  .content("{\"version\":0,\"assigneeSubjects\":[]}"))
          .andExpect(status().is(attempt == 0 ? 200 : 409));
    }
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(jsonPath("version").value(1))
        .andExpect(jsonPath("assigneeSubjects.length()").value(0));
  }

  @Test
  void participantNamesAreResolvedOutsideTheDatabaseTransaction() throws Exception {
    String id = create();
    org.mockito.Mockito.when(directory.participants(org.mockito.ArgumentMatchers.anySet()))
        .thenAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return java.util.List.of(new AuditorDirectory.Person("assigned", "Auditeur"));
            });
    mvc.perform(get("/missions/" + id + "/participants").with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(status().isOk());
  }

  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AssignmentOutbox outbox;

  @Test
  void emitsOnlyNewAssignmentsAndRejectsStaleWritesWithoutEvents() throws Exception {
    String id = create();
    var key = java.util.UUID.fromString(id);
    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM assignment_outbox WHERE mission_id=?", Integer.class, key));
    for (int version = 0; version < 2; version++) {
      mvc.perform(
              put("/missions/" + id + "/assignments")
                  .with(
                      jwt()
                          .jwt(j -> j.subject("owner"))
                          .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                  .contentType("application/json")
                  .content(
                      "{\"version\":"
                          + version
                          + ",\"assigneeSubjects\":[\"assigned\",\"new-auditor\"]}"))
          .andExpect(status().isOk());
    }
    org.junit.jupiter.api.Assertions.assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM assignment_outbox WHERE mission_id=?", Integer.class, key));
    mvc.perform(
            put("/missions/" + id + "/assignments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("owner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content("{\"version\":0,\"assigneeSubjects\":[]}"))
        .andExpect(status().isConflict());
    org.junit.jupiter.api.Assertions.assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM assignment_outbox WHERE mission_id=?", Integer.class, key));
  }

  @Test
  void outboxFailureRollsBackMissionAndEventTogether() {
    int missions = jdbc.queryForObject("SELECT count(*) FROM missions", Integer.class);
    int events = jdbc.queryForObject("SELECT count(*) FROM assignment_outbox", Integer.class);
    AssignmentOutbox target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(outbox);
    org.mockito.Mockito.doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("Simulated transaction failure after outbox insert");
            })
        .when(target)
        .append(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anySet());
    org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> create());
    org.junit.jupiter.api.Assertions.assertEquals(
        missions, jdbc.queryForObject("SELECT count(*) FROM missions", Integer.class));
    org.junit.jupiter.api.Assertions.assertEquals(
        events, jdbc.queryForObject("SELECT count(*) FROM assignment_outbox", Integer.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void publisherRetriesAndReclaimsExpiredLeaseWithoutHoldingTransaction() throws Exception {
    // Isolate this candidate from other test missions without deleting their outbox rows.
    jdbc.update(
        "UPDATE assignment_outbox SET lease_until=now()+interval '1 hour' WHERE published_at IS NULL");
    var mission = java.util.UUID.fromString(create());
    var kafka = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
    org.mockito.Mockito.when(
            kafka.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            call -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return java.util.concurrent.CompletableFuture.failedFuture(
                  new IllegalStateException("Broker offline"));
            });
    var publisher = new OutboxPublisher(jdbc, kafka, mapper);
    publisher.publishNext();
    org.junit.jupiter.api.Assertions.assertNull(
        jdbc.queryForObject(
            "SELECT published_at FROM assignment_outbox WHERE mission_id=?",
            java.sql.Timestamp.class,
            mission));
    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT attempts FROM assignment_outbox WHERE mission_id=?", Integer.class, mission));
    // An unexpired lease cannot be stolen by another publisher.
    jdbc.update(
        "UPDATE assignment_outbox SET available_at=now(), lease_until=now()+interval '30 seconds' WHERE mission_id=?",
        mission);
    publisher.publishNext();
    org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(1))
        .send(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    jdbc.update(
        "UPDATE assignment_outbox SET lease_until=now()-interval '1 second' WHERE mission_id=?",
        mission);
    org.mockito.Mockito.when(
            kafka.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
    publisher.publishNext();
    org.junit.jupiter.api.Assertions.assertNotNull(
        jdbc.queryForObject(
            "SELECT published_at FROM assignment_outbox WHERE mission_id=?",
            java.sql.Timestamp.class,
            mission));
    org.junit.jupiter.api.Assertions.assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT attempts FROM assignment_outbox WHERE mission_id=?", Integer.class, mission));
  }

  org.springframework.test.web.servlet.ResultActions lifecycle(
      String id, String resource, String body) throws Exception {
    return mvc.perform(
        put("/missions/" + id + "/" + resource)
            .with(
                jwt()
                    .jwt(j -> j.subject("owner"))
                    .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
            .contentType("application/json")
            .content(body));
  }

  @Test
  void lifecycleRequiresPlanningAndOnlyAllowsForwardTransitions() throws Exception {
    String id = create();
    lifecycle(id, "status", "{\"version\":0,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isConflict());
    lifecycle(id, "status", "{\"version\":0,\"status\":\"CLOSED\"}")
        .andExpect(status().isConflict());
    lifecycle(
            id,
            "schedule",
            "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-10\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("version").value(1));
    lifecycle(id, "status", "{\"version\":0,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isConflict());
    lifecycle(id, "status", "{\"version\":1,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("IN_PROGRESS"))
        .andExpect(jsonPath("version").value(2))
        .andExpect(jsonPath("startedAt").isNotEmpty());
    lifecycle(id, "status", "{\"version\":2,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isConflict());
    lifecycle(id, "status", "{\"version\":2,\"status\":\"CLOSED\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("version").value(3))
        .andExpect(jsonPath("closedAt").isNotEmpty());
    lifecycle(
            id,
            "schedule",
            "{\"version\":3,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-12\"}")
        .andExpect(status().isConflict());
    lifecycle(id, "status", "{\"version\":3,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isConflict());
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("assigned"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("CLOSED"));
  }

  @Test
  void lifecycleRejectsAuditorsOtherOwnersAnonymousAndInvalidDates() throws Exception {
    String id = create();
    for (String resource : new String[] {"schedule", "status"}) {
      String body =
          resource.equals("status")
              ? "{\"version\":0,\"status\":\"IN_PROGRESS\"}"
              : "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-10\"}";
      mvc.perform(
              put("/missions/" + id + "/" + resource).contentType("application/json").content(body))
          .andExpect(status().isUnauthorized());
      mvc.perform(
              put("/missions/" + id + "/" + resource)
                  .with(jwt().jwt(j -> j.subject("assigned")))
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isForbidden());
      mvc.perform(
              put("/missions/" + id + "/" + resource)
                  .with(
                      jwt()
                          .jwt(j -> j.subject("another-owner"))
                          .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isNotFound());
    }
    lifecycle(
            id,
            "schedule",
            "{\"version\":0,\"plannedStartDate\":\"2026-11-10\",\"plannedEndDate\":\"2026-11-01\"}")
        .andExpect(status().isBadRequest());
    lifecycle(id, "schedule", "{\"version\":0,\"plannedStartDate\":\"2026-11-10\"}")
        .andExpect(status().isBadRequest());
    lifecycle(id, "status", "{\"version\":0,\"status\":\"DRAFT\"}")
        .andExpect(status().isBadRequest());
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("version").value(0))
        .andExpect(jsonPath("status").value("DRAFT"));
  }

  @Test
  void createAllowsOptionalPairOfDatesAndRejectsInvalidPairs() throws Exception {
    for (String dates :
        new String[] {
          ",\"plannedStartDate\":\"2026-11-10\"",
          ",\"plannedStartDate\":\"2026-11-10\",\"plannedEndDate\":\"2026-11-01\""
        }) {
      mvc.perform(
              post("/missions")
                  .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                  .contentType("application/json")
                  .content(payload.strip().replace("}", dates + "}")))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/missions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content(
                    payload
                        .strip()
                        .replace(
                            "}",
                            ",\"plannedStartDate\":\"2026-11-10\",\"plannedEndDate\":\"2026-11-10\"}")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("plannedStartDate").value("2026-11-10"));
  }
}
