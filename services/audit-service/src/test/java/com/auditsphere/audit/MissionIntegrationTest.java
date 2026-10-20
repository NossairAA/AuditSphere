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

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ReviewOutbox reviewOutbox;

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
        "UPDATE assignment_outbox SET lease_until=now()+interval '1 hour' WHERE published_at IS"
            + " NULL");
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
        "UPDATE assignment_outbox SET available_at=now(), lease_until=now()+interval '30 seconds'"
            + " WHERE mission_id=?",
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
        .andExpect(status().isConflict());
    approveMission(id, 2);
    lifecycle(id, "status", "{\"version\":5,\"status\":\"CLOSED\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("version").value(6))
        .andExpect(jsonPath("closedAt").isNotEmpty());
    lifecycle(
            id,
            "schedule",
            "{\"version\":6,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-12\"}")
        .andExpect(status().isConflict());
    lifecycle(id, "status", "{\"version\":6,\"status\":\"IN_PROGRESS\"}")
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

  @Test
  void validatorRoleDoesNotGrantMissionManagementOrGlobalVisibility() throws Exception {
    String id = create();
    var validator =
        jwt()
            .jwt(j -> j.subject("validator"))
            .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_VALIDATOR"));
    mvc.perform(get("/missions").with(validator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    for (String path : new String[] {"/missions/" + id, "/missions/" + id + "/participants"})
      mvc.perform(get(path).with(validator)).andExpect(status().isNotFound());
    mvc.perform(get("/missions/auditors").with(validator)).andExpect(status().isForbidden());
    mvc.perform(post("/missions").with(validator).contentType("application/json").content(payload))
        .andExpect(status().isForbidden());
    for (String resource : new String[] {"assignments", "schedule", "status"})
      mvc.perform(
              put("/missions/" + id + "/" + resource)
                  .with(validator)
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isForbidden());
    // A former owner retaining only the validator role cannot mutate their mission either.
    mvc.perform(
            put("/missions/" + id + "/status")
                .with(
                    jwt()
                        .jwt(j -> j.subject("owner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_VALIDATOR")))
                .contentType("application/json")
                .content("{\"version\":0,\"status\":\"CLOSED\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void validatorDesignationIsVersionedScopedAndRevocable() throws Exception {
    String id = create();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              if (!"designated-reviewer".equals(invocation.getArgument(0)))
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST);
              return null;
            })
        .when(directory)
        .validateValidator(org.mockito.ArgumentMatchers.anyString());
    var reviewer =
        jwt()
            .jwt(
                j ->
                    j.subject("designated-reviewer")
                        .claim(
                            "realm_access",
                            java.util.Map.of("roles", java.util.List.of("AUDIT_VALIDATOR"))))
            .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_VALIDATOR"));
    mvc.perform(get("/missions/" + id).with(reviewer)).andExpect(status().isNotFound());
    lifecycle(id, "validator", "{\"version\":0,\"validatorSubject\":\"designated-reviewer\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(get("/missions/" + id).with(reviewer)).andExpect(status().isOk());
    mvc.perform(get("/missions").with(reviewer)).andExpect(jsonPath("$.items[0].id").value(id));
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("designated-reviewer"))))
        .andExpect(status().isNotFound());
    lifecycle(id, "validator", "{\"version\":0,\"validatorSubject\":null}")
        .andExpect(status().isConflict());
    lifecycle(id, "validator", "{\"version\":1,\"validatorSubject\":\"unknown\"}")
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/missions/" + id + "/validator")
                .with(reviewer)
                .contentType("application/json")
                .content("{\"version\":1,\"validatorSubject\":null}"))
        .andExpect(status().isForbidden());
    lifecycle(id, "validator", "{\"version\":1,\"validatorSubject\":null}")
        .andExpect(status().isOk());
    mvc.perform(get("/missions/" + id).with(reviewer)).andExpect(status().isNotFound());
    mvc.perform(get("/missions").with(reviewer)).andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void designationRejectsSelfEvenWithBothRolesAndOtherOwners() throws Exception {
    String id = create();
    mvc.perform(
            put("/missions/" + id + "/validator")
                .with(
                    jwt()
                        .jwt(j -> j.subject("owner"))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER"),
                            new SimpleGrantedAuthority("ROLE_AUDIT_VALIDATOR")))
                .contentType("application/json")
                .content("{\"version\":0,\"validatorSubject\":\"owner\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/missions/" + id + "/validator")
                .with(
                    jwt()
                        .jwt(j -> j.subject("other"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content("{\"version\":0,\"validatorSubject\":\"reviewer\"}"))
        .andExpect(status().isNotFound());
    org.mockito.Mockito.verify(directory, org.mockito.Mockito.never())
        .validateValidator(org.mockito.ArgumentMatchers.anyString());
    lifecycle(
            id,
            "schedule",
            "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-02\"}")
        .andExpect(status().isOk());
    lifecycle(id, "status", "{\"version\":1,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isOk());
    approveMission(id, 2);
    lifecycle(id, "status", "{\"version\":5,\"status\":\"CLOSED\"}").andExpect(status().isOk());
    lifecycle(id, "validator", "{\"version\":6,\"validatorSubject\":\"reviewer\"}")
        .andExpect(status().isConflict());
  }

  void approveMission(String id, long version) throws Exception {
    lifecycle(id, "validator", "{\"version\":" + version + ",\"validatorSubject\":\"reviewer\"}")
        .andExpect(status().isOk());
    lifecycle(id, "submission", "{\"version\":" + (version + 1) + "}").andExpect(status().isOk());
    decide(id, version + 2, "reviewer", "APPROVED").andExpect(status().isOk());
  }

  org.springframework.test.web.servlet.ResultActions decide(
      String id, long version, String subject, String decision) throws Exception {
    return mvc.perform(
        put("/missions/" + id + "/review")
            .with(
                jwt()
                    .jwt(j -> j.subject(subject))
                    .authorities(
                        new SimpleGrantedAuthority("ROLE_AUDIT_VALIDATOR"),
                        new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
            .contentType("application/json")
            .content(
                mapper.writeValueAsString(
                    java.util.Map.of(
                        "version", version, "decision", decision, "comment", "Revue effectuée"))));
  }

  @Test
  void reviewRequiresDesignatedValidatorAndFreezesReviewedMission() throws Exception {
    String id = create();
    lifecycle(
            id,
            "schedule",
            "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-02\"}")
        .andExpect(status().isOk());
    lifecycle(id, "status", "{\"version\":1,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE missions SET status='CLOSED', closed_at=now() WHERE id=?",
                java.util.UUID.fromString(id)));
    lifecycle(id, "submission", "{\"version\":2}").andExpect(status().isConflict());
    lifecycle(id, "validator", "{\"version\":2,\"validatorSubject\":\"reviewer\"}")
        .andExpect(status().isOk());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return null;
            })
        .when(directory)
        .validateValidator("reviewer");
    lifecycle(id, "submission", "{\"version\":3}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("PENDING_REVIEW"));
    org.junit.jupiter.api.Assertions.assertEquals(
        "reviewer",
        jdbc.queryForObject(
            "SELECT recipient_subject FROM assignment_outbox WHERE mission_id=? AND"
                + " event_type='audit.mission.review-requested.v1'",
            String.class,
            java.util.UUID.fromString(id)));
    decide(id, 4, "owner", "APPROVED").andExpect(status().isNotFound());
    decide(id, 4, "other-validator", "APPROVED").andExpect(status().isNotFound());
    mvc.perform(put("/missions/" + id + "/review").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            put("/missions/" + id + "/review")
                .with(jwt().jwt(j -> j.subject("reviewer")))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    decide(id, 3, "reviewer", "APPROVED").andExpect(status().isConflict());
    lifecycle(id, "status", "{\"version\":4,\"status\":\"CLOSED\"}")
        .andExpect(status().isConflict());
    lifecycle(id, "validator", "{\"version\":4,\"validatorSubject\":null}")
        .andExpect(status().isConflict());
    lifecycle(id, "assignments", "{\"version\":4,\"assigneeSubjects\":[]}")
        .andExpect(status().isConflict());
    lifecycle(
            id,
            "schedule",
            "{\"version\":4,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-03\"}")
        .andExpect(status().isConflict());
    mvc.perform(
            put("/missions/" + id + "/review")
                .with(
                    jwt()
                        .jwt(j -> j.subject("reviewer"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_VALIDATOR")))
                .contentType("application/json")
                .content("{\"version\":4,\"decision\":\"APPROVED\",\"comment\":\" \"}"))
        .andExpect(status().isBadRequest());
    decide(id, 4, "reviewer", "REJECTED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("IN_PROGRESS"))
        .andExpect(jsonPath("reviewComment").value("Revue effectuée"));
    lifecycle(id, "status", "{\"version\":5,\"status\":\"CLOSED\"}")
        .andExpect(status().isConflict());
    lifecycle(id, "submission", "{\"version\":5}").andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM assignment_outbox WHERE mission_id=? AND"
                + " event_type='audit.mission.review-requested.v1'",
            Integer.class,
            java.util.UUID.fromString(id)));
    decide(id, 6, "reviewer", "APPROVED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("reviewedBy").value("reviewer"));
    decide(id, 7, "reviewer", "REJECTED").andExpect(status().isConflict());
    lifecycle(id, "validator", "{\"version\":7,\"validatorSubject\":null}")
        .andExpect(status().isConflict());
    lifecycle(id, "status", "{\"version\":7,\"status\":\"CLOSED\"}").andExpect(status().isOk());
  }

  @Test
  void reviewOutboxPublishesInOrderAndRetriesCompletedDecision() throws Exception {
    jdbc.update("DELETE FROM review_workflow_outbox");
    String id = create();
    lifecycle(
            id,
            "schedule",
            "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-02\"}")
        .andExpect(status().isOk());
    lifecycle(id, "status", "{\"version\":1,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isOk());
    approveMission(id, 2);
    java.util.UUID cycle =
        jdbc.queryForObject(
            "SELECT review_cycle_id FROM missions WHERE id=?",
            java.util.UUID.class,
            java.util.UUID.fromString(id));
    org.junit.jupiter.api.Assertions.assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM review_workflow_outbox WHERE cycle_id=?", Integer.class, cycle));
    try (var env = io.temporal.testing.TestWorkflowEnvironment.newInstance()) {
      env.newWorker(com.auditsphere.audit.workflow.ReviewWorkflow.QUEUE)
          .registerWorkflowImplementationTypes(
              com.auditsphere.audit.workflow.ReviewWorkflowImpl.class);
      env.start();
      var publisher = new ReviewWorkflowPublisher(jdbc, env.getWorkflowClient());
      publisher.publishNext();
      org.junit.jupiter.api.Assertions.assertEquals(
          "START",
          jdbc.queryForObject(
              "SELECT event_type FROM review_workflow_outbox WHERE published_at IS NOT NULL",
              String.class));
      publisher.publishNext();
      var stub = env.getWorkflowClient().newUntypedWorkflowStub("mission-review-" + cycle);
      org.junit.jupiter.api.Assertions.assertEquals(
          "APPROVED", stub.getResult(5, java.util.concurrent.TimeUnit.SECONDS, String.class));
      // Simulate losing both delivery acknowledgements after the workflow completed.
      jdbc.update("UPDATE review_workflow_outbox SET published_at=NULL,available_at=now()");
      publisher.publishNext();
      publisher.publishNext();
      org.junit.jupiter.api.Assertions.assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM review_workflow_outbox WHERE published_at IS NULL",
              Integer.class));
    }
    // Failed re-submission must not create another outbox event.
    lifecycle(id, "submission", "{\"version\":5}").andExpect(status().isConflict());
    org.junit.jupiter.api.Assertions.assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM review_workflow_outbox WHERE cycle_id=?", Integer.class, cycle));
  }

  @Test
  void failedOutboxInsertRollsBackSubmission() throws Exception {
    String id = create();
    lifecycle(
            id,
            "schedule",
            "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-02\"}")
        .andExpect(status().isOk());
    lifecycle(id, "status", "{\"version\":1,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isOk());
    lifecycle(id, "validator", "{\"version\":2,\"validatorSubject\":\"reviewer\"}")
        .andExpect(status().isOk());
    ReviewOutbox target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(reviewOutbox);
    org.mockito.Mockito.doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("Synthetic outbox failure");
            })
        .when(target)
        .append(
            org.mockito.ArgumentMatchers.any(Mission.class),
            org.mockito.ArgumentMatchers.eq("START"),
            org.mockito.ArgumentMatchers.isNull());
    org.junit.jupiter.api.Assertions.assertThrows(
        jakarta.servlet.ServletException.class,
        () -> lifecycle(id, "submission", "{\"version\":3}"));
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(jsonPath("status").value("IN_PROGRESS"))
        .andExpect(jsonPath("version").value(3));
    org.junit.jupiter.api.Assertions.assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM review_workflow_outbox WHERE mission_id=?",
            Integer.class,
            java.util.UUID.fromString(id)));
  }

  @Test
  void notificationFailureRollsBackSubmissionAndTemporalMessage() throws Exception {
    String id = create();
    lifecycle(
            id,
            "schedule",
            "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-02\"}")
        .andExpect(status().isOk());
    lifecycle(id, "status", "{\"version\":1,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isOk());
    lifecycle(id, "validator", "{\"version\":2,\"validatorSubject\":\"reviewer\"}")
        .andExpect(status().isOk());
    AssignmentOutbox target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(outbox);
    org.mockito.Mockito.doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("Synthetic notification failure");
            })
        .when(target)
        .requestReview(org.mockito.ArgumentMatchers.any());
    org.junit.jupiter.api.Assertions.assertThrows(
        jakarta.servlet.ServletException.class,
        () -> lifecycle(id, "submission", "{\"version\":3}"));
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(jsonPath("status").value("IN_PROGRESS"));
    var key = java.util.UUID.fromString(id);
    org.junit.jupiter.api.Assertions.assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM review_workflow_outbox WHERE mission_id=?", Integer.class, key));
    org.junit.jupiter.api.Assertions.assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM assignment_outbox WHERE mission_id=? AND"
                + " event_type='audit.mission.review-requested.v1'",
            Integer.class,
            key));
  }

  @Test
  void titleBatchOnlyReturnsAuthorizedMissions() throws Exception {
    String id = create();
    mvc.perform(get("/missions/titles").param("ids", id).with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$['" + id + "']").exists());
    mvc.perform(
            get("/missions/titles").param("ids", id).with(jwt().jwt(j -> j.subject("outsider"))))
        .andExpect(status().isOk())
        .andExpect(content().json("{}"));
    String ids =
        java.util.stream.IntStream.range(0, 21)
            .mapToObj(n -> java.util.UUID.randomUUID().toString())
            .collect(java.util.stream.Collectors.joining(","));
    mvc.perform(get("/missions/titles").param("ids", ids).with(jwt()))
        .andExpect(status().isBadRequest());
  }

  org.springframework.test.web.servlet.ResultActions deleteDraft(
      String id, String subject, String role, long version, String reason) throws Exception {
    return mvc.perform(
        delete("/missions/" + id)
            .with(
                jwt()
                    .jwt(j -> j.subject(subject))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role)))
            .contentType("application/json")
            .content(
                mapper.writeValueAsString(java.util.Map.of("version", version, "reason", reason))));
  }

  String pendingReview() throws Exception {
    String id = create();
    lifecycle(
            id,
            "schedule",
            "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-10\"}")
        .andExpect(status().isOk());
    lifecycle(id, "status", "{\"version\":1,\"status\":\"IN_PROGRESS\"}")
        .andExpect(status().isOk());
    lifecycle(id, "validator", "{\"version\":2,\"validatorSubject\":\"reviewer\"}")
        .andExpect(status().isOk());
    lifecycle(id, "submission", "{\"version\":3}").andExpect(status().isOk());
    return id;
  }

  @Test
  void draftDeletionRequiresOwnerRoleVersionAndReasonAndKeepsTrace() throws Exception {
    String id = create();
    deleteDraft(id, "owner", "AUDITOR", 0, "Doublon").andExpect(status().isForbidden());
    deleteDraft(id, "other", "AUDIT_MANAGER", 0, "Doublon").andExpect(status().isNotFound());
    deleteDraft(id, "owner", "AUDIT_MANAGER", 1, "Doublon").andExpect(status().isConflict());
    deleteDraft(id, "owner", "AUDIT_MANAGER", 0, " ").andExpect(status().isBadRequest());
    deleteDraft(id, "owner", "AUDIT_MANAGER", 0, "Doublon").andExpect(status().isNoContent());
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(status().isNotFound());
    deleteDraft(id, "owner", "AUDIT_MANAGER", 0, "Doublon").andExpect(status().isNotFound());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Doublon",
        jdbc.queryForObject(
            "SELECT reason FROM mission_action_log WHERE mission_id=? AND action='DRAFT_DELETED'",
            String.class,
            java.util.UUID.fromString(id)));
    org.junit.jupiter.api.Assertions.assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM assignment_outbox WHERE mission_id=?",
            Integer.class,
            java.util.UUID.fromString(id)));
    String submitted = pendingReview();
    deleteDraft(submitted, "owner", "AUDIT_MANAGER", 4, "Doublon").andExpect(status().isConflict());
  }

  @Test
  void withdrawalReopensMissionPreservesTraceAndStartsANewCycleOnResubmission() throws Exception {
    String id = pendingReview();
    var cycle =
        jdbc.queryForObject(
            "SELECT review_cycle_id FROM missions WHERE id=?",
            java.util.UUID.class,
            java.util.UUID.fromString(id));
    mvc.perform(
            put("/missions/" + id + "/withdrawal")
                .with(
                    jwt()
                        .jwt(j -> j.subject("owner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDITOR")))
                .contentType("application/json")
                .content("{\"version\":4,\"reason\":\"Correction\"}"))
        .andExpect(status().isForbidden());
    lifecycle(id, "withdrawal", "{\"version\":3,\"reason\":\"Correction\"}")
        .andExpect(status().isConflict());
    lifecycle(id, "withdrawal", "{\"version\":4,\"reason\":\" \"}")
        .andExpect(status().isBadRequest());
    lifecycle(id, "withdrawal", "{\"version\":4,\"reason\":\"Périmètre à corriger\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("IN_PROGRESS"))
        .andExpect(jsonPath("version").value(5))
        .andExpect(jsonPath("withdrawalReason").value("Périmètre à corriger"));
    decide(id, 4, "reviewer", "APPROVED").andExpect(status().isConflict());
    org.junit.jupiter.api.Assertions.assertEquals(
        "WITHDRAWN",
        jdbc.queryForObject(
            "SELECT decision FROM review_workflow_outbox WHERE cycle_id=? AND"
                + " event_type='DECISION'",
            String.class,
            cycle));
    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM mission_action_log WHERE mission_id=? AND"
                + " action='REVIEW_WITHDRAWN'",
            Integer.class,
            java.util.UUID.fromString(id)));
    lifecycle(id, "submission", "{\"version\":5}").andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertNotEquals(
        cycle,
        jdbc.queryForObject(
            "SELECT review_cycle_id FROM missions WHERE id=?",
            java.util.UUID.class,
            java.util.UUID.fromString(id)));
    decide(id, 6, "reviewer", "APPROVED").andExpect(status().isOk());
    lifecycle(id, "withdrawal", "{\"version\":7,\"reason\":\"Correction\"}")
        .andExpect(status().isConflict());
  }

  @Test
  void withdrawalAndApprovalRaceHaveOnlyOneWinner() throws Exception {
    String id = pendingReview();
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var withdraw =
          threads.submit(
              () -> {
                gate.await();
                return lifecycle(id, "withdrawal", "{\"version\":4,\"reason\":\"Correction\"}")
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      var approve =
          threads.submit(
              () -> {
                gate.await();
                return decide(id, 4, "reviewer", "APPROVED").andReturn().getResponse().getStatus();
              });
      gate.countDown();
      org.junit.jupiter.api.Assertions.assertEquals(
          java.util.Set.of(200, 409), java.util.Set.of(withdraw.get(), approve.get()));
    }
    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM review_workflow_outbox WHERE mission_id=? AND"
                + " event_type='DECISION'",
            Integer.class,
            java.util.UUID.fromString(id)));
  }

  @Test
  void failedWithdrawalOutboxRollsBackStatusAndJournal() throws Exception {
    String id = pendingReview();
    ReviewOutbox target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(reviewOutbox);
    org.mockito.Mockito.doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("Synthetic failure");
            })
        .when(target)
        .append(
            org.mockito.ArgumentMatchers.any(Mission.class),
            org.mockito.ArgumentMatchers.eq("DECISION"),
            org.mockito.ArgumentMatchers.eq("WITHDRAWN"));
    org.junit.jupiter.api.Assertions.assertThrows(
        jakarta.servlet.ServletException.class,
        () -> lifecycle(id, "withdrawal", "{\"version\":4,\"reason\":\"Correction\"}"));
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("owner"))))
        .andExpect(jsonPath("status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("version").value(4));
    org.junit.jupiter.api.Assertions.assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM mission_action_log WHERE mission_id=?",
            Integer.class,
            java.util.UUID.fromString(id)));
  }

  org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      adminJwt(String subject) {
    return jwt()
        .jwt(
            j ->
                j.subject(subject)
                    .claim(
                        "realm_access",
                        java.util.Map.of("roles", java.util.List.of("SUPER_ADMIN"))))
        .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
  }

  org.springframework.test.web.servlet.ResultActions overrideMission(
      String id, long version, String action, String reason) throws Exception {
    return mvc.perform(
        put("/missions/" + id + "/override")
            .with(adminJwt("owner"))
            .contentType("application/json")
            .content(
                mapper.writeValueAsString(
                    java.util.Map.of("version", version, "action", action, "reason", reason))));
  }

  @Test
  void superAdminHasGlobalAccessWithoutChangingOwnership() throws Exception {
    String id = create();
    mvc.perform(get("/missions/" + id).with(adminJwt("administrator")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("ownerSubject").value("owner"));
    mvc.perform(get("/missions/titles").param("ids", id).with(adminJwt("administrator")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$." + id).exists());
    mvc.perform(get("/missions").param("size", "100").with(adminJwt("administrator")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.id == '" + id + "')]").isNotEmpty());
    mvc.perform(
            put("/missions/" + id + "/schedule")
                .with(adminJwt("administrator"))
                .contentType("application/json")
                .content(
                    "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-10\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("ownerSubject").value("owner"));
    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM mission_action_log WHERE mission_id=? AND action='ADMIN_CHANGE'",
            Integer.class,
            java.util.UUID.fromString(id)));
    mvc.perform(get("/missions/" + id).with(jwt().jwt(j -> j.subject("superadmin"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void overridesRequireAdminReasonAndCurrentVersion() throws Exception {
    String id = pendingReview();
    mvc.perform(
            put("/missions/" + id + "/override")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                .contentType("application/json")
                .content("{\"version\":4,\"action\":\"CLOSE\",\"reason\":\"Test\"}"))
        .andExpect(status().isForbidden());
    overrideMission(id, 4, "CLOSE", " ").andExpect(status().isBadRequest());
    overrideMission(id, 3, "CLOSE", "Erreur de périmètre").andExpect(status().isConflict());
    overrideMission(id, 4, "INVALID", "Test").andExpect(status().isBadRequest());
    mvc.perform(get("/missions/" + id + "/action-log").with(jwt()))
        .andExpect(status().isForbidden());
    org.junit.jupiter.api.Assertions.assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM mission_action_log WHERE mission_id=? AND"
                + " action='ADMIN_OVERRIDE'",
            Integer.class,
            java.util.UUID.fromString(id)));
  }

  @Test
  void explicitOverridesCloseReopenSelfApproveAndHideMissionWithHistory() throws Exception {
    String id = pendingReview();
    overrideMission(id, 4, "CLOSE", "Clôture exceptionnelle")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("CLOSED"));
    org.junit.jupiter.api.Assertions.assertEquals(
        "WITHDRAWN",
        jdbc.queryForObject(
            "SELECT decision FROM review_workflow_outbox WHERE mission_id=? AND"
                + " event_type='DECISION'",
            String.class,
            java.util.UUID.fromString(id)));
    overrideMission(id, 5, "REOPEN", "Travaux complémentaires")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("IN_PROGRESS"));
    overrideMission(id, 6, "APPROVE", "Approbation personnelle explicitement autorisée")
        .andExpect(status().isOk())
        .andExpect(jsonPath("reviewedBy").value("owner"))
        .andExpect(jsonPath("status").value("APPROVED"));
    overrideMission(id, 7, "DELETE", "Mission à retirer").andExpect(status().isNoContent());
    mvc.perform(get("/missions/" + id).with(adminJwt("owner"))).andExpect(status().isNotFound());
    mvc.perform(get("/missions/titles").param("ids", id).with(adminJwt("owner")))
        .andExpect(content().json("{}"));
    overrideMission(id, 8, "REOPEN", "Test").andExpect(status().isNotFound());
    mvc.perform(get("/missions/" + id + "/action-log").with(adminJwt("owner")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].previous_status").value("APPROVED"))
        .andExpect(jsonPath("$[0].resulting_status").value("DELETED"));
    org.junit.jupiter.api.Assertions.assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM review_workflow_outbox WHERE mission_id=?",
            Integer.class,
            java.util.UUID.fromString(id)));
  }

  @Test
  void superAdminCannotSilentlySelfApproveOrClose() throws Exception {
    String id = pendingReview();
    mvc.perform(
            put("/missions/" + id + "/review")
                .with(adminJwt("owner"))
                .contentType("application/json")
                .content("{\"version\":4,\"decision\":\"APPROVED\",\"comment\":\"Test\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/missions/" + id + "/status")
                .with(adminJwt("owner"))
                .contentType("application/json")
                .content("{\"version\":4,\"status\":\"CLOSED\"}"))
        .andExpect(status().isConflict());
    overrideMission(id, 4, "APPROVE", "Dérogation motivée").andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertEquals(
        "APPROVED",
        jdbc.queryForObject(
            "SELECT decision FROM review_workflow_outbox WHERE mission_id=? AND"
                + " event_type='DECISION'",
            String.class,
            java.util.UUID.fromString(id)));
  }

  @Test
  void pendingDeletionEndsCycleAndDraftOverrideKeepsValidDates() throws Exception {
    String id = pendingReview();
    overrideMission(id, 4, "DELETE", "Erreur de création").andExpect(status().isNoContent());
    org.junit.jupiter.api.Assertions.assertEquals(
        "WITHDRAWN",
        jdbc.queryForObject(
            "SELECT decision FROM review_workflow_outbox WHERE mission_id=? AND"
                + " event_type='DECISION'",
            String.class,
            java.util.UUID.fromString(id)));
    String draft = create();
    overrideMission(draft, 0, "CLOSE", "Test").andExpect(status().isConflict());
    overrideMission(draft, 0, "APPROVE", "Test").andExpect(status().isConflict());
    overrideMission(draft, 0, "DELETE", "Brouillon erroné").andExpect(status().isNoContent());
  }

  @Test
  void overrideFailureRollsBackTraceAndState() throws Exception {
    String id = pendingReview();
    ReviewOutbox target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(reviewOutbox);
    org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic failure"))
        .when(target)
        .append(
            org.mockito.ArgumentMatchers.any(Mission.class),
            org.mockito.ArgumentMatchers.eq("DECISION"),
            org.mockito.ArgumentMatchers.eq("APPROVED"));
    org.junit.jupiter.api.Assertions.assertThrows(
        jakarta.servlet.ServletException.class,
        () -> overrideMission(id, 4, "APPROVE", "Test atomique"));
    org.junit.jupiter.api.Assertions.assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM mission_action_log WHERE mission_id=? AND"
                + " action='ADMIN_OVERRIDE'",
            Integer.class,
            java.util.UUID.fromString(id)));
    mvc.perform(get("/missions/" + id).with(adminJwt("owner")))
        .andExpect(jsonPath("status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("adminOverrideAction").isEmpty())
        .andExpect(jsonPath("version").value(4));
  }
}
