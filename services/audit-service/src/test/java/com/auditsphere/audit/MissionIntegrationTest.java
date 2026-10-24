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

@SpringBootTest(properties = {"spring.flyway.enabled=true", "EVIDENCE_MISSION_QUOTA_BYTES=10485770"})
@AutoConfigureMockMvc
@Testcontainers
class MissionIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres@sha256:2a0d0fe14825b0939f78a8cad5cd4e6aa68bf94d0e5dd96e24b6d23af4315545");

  @org.springframework.test.context.bean.override.mockito.MockitoBean AuditorDirectory directory;

  @org.junit.jupiter.api.BeforeEach
  void directoryFixture() {
    org.mockito.Mockito.when(directory.participants(org.mockito.ArgumentMatchers.anySet()))
        .thenAnswer(invocation -> {
          java.util.Set<String> subjects = invocation.getArgument(0);
          return subjects.stream().map(subject -> new AuditorDirectory.Person(subject, "Annuaire " + subject)).toList();
        });
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
  @Autowired ControlEvidenceController evidenceController;
  @Autowired javax.sql.DataSource dataSource;
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
  void creationDesignatesEligibleValidatorAtomically() throws Exception {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              if (!"reviewer".equals(invocation.getArgument(0)))
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST);
              return null;
            })
        .when(directory)
        .validateValidator(org.mockito.ArgumentMatchers.anyString());
    String data =
        "{\"title\":\"Création avec validateur\",\"description\":\"Test\",\"perimeter\":\"Achats\",\"assigneeSubjects\":[],\"validatorSubject\":\"reviewer\"}";
    var response =
        mvc.perform(
                post("/missions")
                    .with(
                        jwt()
                            .jwt(j -> j.subject("owner"))
                            .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                    .contentType("application/json")
                    .content(data))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("validatorSubject").value("reviewer"))
            .andReturn();
    String id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(
            get("/missions/" + id)
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject("reviewer")
                                    .claim(
                                        "realm_access",
                                        java.util.Map.of(
                                            "roles", java.util.List.of("AUDIT_VALIDATOR"))))
                        .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_VALIDATOR"))))
        .andExpect(status().isOk());

    for (String invalid : new String[] {"owner", "unknown"})
      mvc.perform(
              post("/missions")
                  .with(
                      jwt()
                          .jwt(j -> j.subject("owner"))
                          .authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
                  .contentType("application/json")
                  .content(data.replace("reviewer", invalid)))
          .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM missions WHERE title='Création avec validateur'",
            Integer.class));
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
    return overrideMission(id, version, action, reason, "owner");
  }

  org.springframework.test.web.servlet.ResultActions overrideMission(
      String id, long version, String action, String reason, String subject) throws Exception {
    return mvc.perform(
        put("/missions/" + id + "/override")
            .with(adminJwt(subject))
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
    overrideMission(id, 6, "APPROVE", "Approbation exceptionnelle par un administrateur distinct", "administrator")
        .andExpect(status().isOk())
        .andExpect(jsonPath("reviewedBy").value("administrator"))
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
    overrideMission(id, 4, "APPROVE", "Dérogation motivée").andExpect(status().isConflict());
    overrideMission(id, 4, "APPROVE", "Dérogation motivée", "reviewer").andExpect(status().isConflict());
    overrideMission(id, 4, "APPROVE", "Dérogation motivée", "assigned").andExpect(status().isConflict());
    overrideMission(id, 4, "APPROVE", "Dérogation par tiers", "administrator").andExpect(status().isOk());
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
        () -> overrideMission(id, 4, "APPROVE", "Test atomique", "administrator"));
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

  org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      programJwt(String subject, String role) {
    return jwt()
        .jwt(
            j ->
                j.subject(subject)
                    .claim("name", "Nom choisi dans le jeton")
                    .claim("realm_access", java.util.Map.of("roles", java.util.List.of(role))))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  org.springframework.test.web.servlet.ResultActions saveRisk(
      String mission, long version, String subject, String role) throws Exception {
    return mvc.perform(
        put("/missions/" + mission + "/risks")
            .with(programJwt(subject, role))
            .contentType("application/json")
            .content(
                mapper.writeValueAsString(
                    java.util.Map.of(
                        "version",
                        version,
                        "title",
                        "Paiement en double",
                        "description",
                        "Factures payées deux fois",
                        "probability",
                        3,
                        "impact",
                        4))));
  }

  @Test
  void programPersistsLinksAndAuditTrailAndDetectsConflicts() throws Exception {
    String id = create();
    var response =
        saveRisk(id, 0, "owner", "AUDIT_MANAGER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("mission.version").value(1))
            .andReturn();
    String risk =
        mapper.readTree(response.getResponse().getContentAsString()).at("/risks/0/id").asText();
    saveRisk(id, 0, "owner", "AUDIT_MANAGER").andExpect(status().isConflict());
    String controlBody =
        mapper.writeValueAsString(
            java.util.Map.of(
                "version",
                1,
                "objective",
                "Vérifier les doublons",
                "procedure",
                "Comparer fournisseur, numéro et montant",
                "riskIds",
                java.util.List.of(risk)));
    response =
        mvc.perform(
                put("/missions/" + id + "/controls")
                    .with(programJwt("owner", "AUDIT_MANAGER"))
                    .contentType("application/json")
                    .content(controlBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("controls[0].riskIds[0]").value(risk))
            .andExpect(jsonPath("mission.version").value(2))
            .andReturn();
    String control =
        mapper.readTree(response.getResponse().getContentAsString()).at("/controls/0/id").asText();
    mvc.perform(
            put("/missions/" + id + "/controls")
                .with(programJwt("owner", "AUDIT_MANAGER"))
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of(
                            "version",
                            2,
                            "id",
                            control,
                            "objective",
                            "Contrôle révisé",
                            "procedure",
                            "Nouvelle procédure",
                            "riskIds",
                            java.util.List.of(risk)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("controls.length()").value(1));
    org.junit.jupiter.api.Assertions.assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM program_change_log WHERE mission_id=?",
            Integer.class,
            java.util.UUID.fromString(id)));
    org.junit.jupiter.api.Assertions.assertEquals(
        "Vérifier les doublons",
        jdbc.queryForObject(
            "SELECT previous_data->>'objective' FROM program_change_log WHERE mission_id=? AND previous_data IS NOT NULL",
            String.class,
            java.util.UUID.fromString(id)));
  }

  @Test
  void programPermissionsAndMissionIsolation() throws Exception {
    String id = create();
    saveRisk(id, 0, "assigned", "AUDITOR").andExpect(status().isForbidden());
    saveRisk(id, 0, "stranger", "AUDIT_MANAGER").andExpect(status().isNotFound());
    saveRisk(id, 0, "owner", "AUDITOR").andExpect(status().isForbidden());
    saveRisk(id, 0, "administrator", "SUPER_ADMIN").andExpect(status().isOk());
    jdbc.update(
        "UPDATE missions SET validator_subject='validator' WHERE id=?",
        java.util.UUID.fromString(id));
    for (String subject : java.util.List.of("owner", "assigned", "validator")) {
      mvc.perform(
              get("/missions/" + id + "/program")
                  .with(
                      programJwt(
                          subject, subject.equals("validator") ? "AUDIT_VALIDATOR" : "AUDITOR")))
          .andExpect(status().isOk());
    }
    mvc.perform(get("/missions/" + id + "/program").with(programJwt("validator", "AUDITOR")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/missions/" + id + "/program").with(programJwt("stranger", "AUDIT_MANAGER")))
        .andExpect(status().isNotFound());
    String other = create();
    var response = saveRisk(other, 0, "owner", "AUDIT_MANAGER").andReturn();
    String foreign =
        mapper.readTree(response.getResponse().getContentAsString()).at("/risks/0/id").asText();
    mvc.perform(
            put("/missions/" + id + "/controls")
                .with(programJwt("owner", "AUDIT_MANAGER"))
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of(
                            "version",
                            1,
                            "objective",
                            "Test",
                            "procedure",
                            "Test",
                            "riskIds",
                            java.util.List.of(foreign)))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/missions/" + id + "/risks")
                .with(programJwt("owner", "AUDIT_MANAGER"))
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of(
                            "version",
                            1,
                            "id",
                            foreign,
                            "title",
                            "Test",
                            "description",
                            "Test",
                            "probability",
                            1,
                            "impact",
                            1))))
        .andExpect(status().isNotFound());
  }

  @Test
  void programLockedDuringReviewAndDeletedMissionHidden() throws Exception {
    String id = create();
    jdbc.update(
        "UPDATE missions SET status='PENDING_REVIEW',planned_start_date=current_date,planned_end_date=current_date,started_at=now(),submitted_at=now(),validator_subject='validator' WHERE id=?",
        java.util.UUID.fromString(id));
    saveRisk(id, 0, "owner", "AUDIT_MANAGER").andExpect(status().isConflict());
    saveRisk(id, 0, "admin", "SUPER_ADMIN").andExpect(status().isConflict());
    mvc.perform(
            get("/missions/" + id + "/program").with(programJwt("validator", "AUDIT_VALIDATOR")))
        .andExpect(status().isOk());
    jdbc.update(
        "UPDATE missions SET status='APPROVED',reviewed_at=now(),reviewed_by='validator',review_decision='APPROVED',review_comment='Validé' WHERE id=?",
        java.util.UUID.fromString(id));
    saveRisk(id, 0, "owner", "AUDIT_MANAGER").andExpect(status().isConflict());
    jdbc.update(
        "UPDATE missions SET status='CLOSED',closed_at=now() WHERE id=?",
        java.util.UUID.fromString(id));
    saveRisk(id, 0, "owner", "AUDIT_MANAGER").andExpect(status().isConflict());
    jdbc.update("UPDATE missions SET deleted_at=now() WHERE id=?", java.util.UUID.fromString(id));
    mvc.perform(get("/missions/" + id + "/program").with(programJwt("admin", "SUPER_ADMIN")))
        .andExpect(status().isNotFound());
  }

  @Test
  void programSupportsMultipleRisksAndRejectsInvalidFields() throws Exception {
    String id = create();
    for (int probability : new int[] {0, 6})
      mvc.perform(
              put("/missions/" + id + "/risks")
                  .with(programJwt("owner", "AUDIT_MANAGER"))
                  .contentType("application/json")
                  .content(
                      mapper.writeValueAsString(
                          java.util.Map.of(
                              "version",
                              0,
                              "title",
                              "Risque",
                              "description",
                              "Test",
                              "probability",
                              probability,
                              "impact",
                              3))))
          .andExpect(status().isBadRequest());
    saveRisk(id, 0, "owner", "AUDIT_MANAGER").andExpect(status().isOk());
    var response = saveRisk(id, 1, "owner", "AUDIT_MANAGER").andExpect(status().isOk()).andReturn();
    var risks = mapper.readTree(response.getResponse().getContentAsString()).get("risks");
    var ids = java.util.List.of(risks.get(0).get("id").asText(), risks.get(1).get("id").asText());
    mvc.perform(
            put("/missions/" + id + "/controls")
                .with(programJwt("owner", "AUDIT_MANAGER"))
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of(
                            "version",
                            2,
                            "objective",
                            "Contrôle",
                            "procedure",
                            "Test",
                            "riskIds",
                            java.util.List.of()))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/missions/" + id + "/controls")
                .with(programJwt("owner", "AUDIT_MANAGER"))
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of(
                            "version",
                            2,
                            "objective",
                            "Contrôle",
                            "procedure",
                            "Test",
                            "riskIds",
                            ids))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("controls[0].riskIds.length()").value(2));
  }

  String programControl(String id) throws Exception {
    var r = saveRisk(id, 0, "owner", "AUDIT_MANAGER").andReturn();
    String risk = mapper.readTree(r.getResponse().getContentAsString()).at("/risks/0/id").asText();
    var c =
        mvc.perform(
                put("/missions/" + id + "/controls")
                    .with(programJwt("owner", "AUDIT_MANAGER"))
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            java.util.Map.of(
                                "version",
                                1,
                                "objective",
                                "Doublons",
                                "procedure",
                                "Comparer les factures",
                                "riskIds",
                                java.util.List.of(risk)))))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(c.getResponse().getContentAsString()).at("/controls/0/id").asText();
  }

  org.springframework.test.web.servlet.ResultActions recordControl(
      String id,
      String control,
      long version,
      String subject,
      String role,
      String outcome,
      String comment)
      throws Exception {
    return mvc.perform(
        put("/missions/" + id + "/results")
            .with(programJwt(subject, role))
            .contentType("application/json")
            .content(
                mapper.writeValueAsString(
                    java.util.Map.of(
                        "version",
                        version,
                        "controlId",
                        control,
                        "outcome",
                        outcome,
                        "comment",
                        comment))));
  }

  @Test
  void resultsPersistCorrectionsAndInvalidateWhenControlChanges() throws Exception {
    String id = create(), control = programControl(id);
    jdbc.update(
        "UPDATE missions SET status='IN_PROGRESS',planned_start_date=current_date,planned_end_date=current_date,started_at=now() WHERE id=?",
        java.util.UUID.fromString(id));
    recordControl(
            id, control, 2, "assigned", "AUDITOR", "NON_CONFORMING", "Deux factures identiques")
        .andExpect(status().isOk())
        .andExpect(jsonPath("controls[0].result.authorSubject").value("assigned"))
        .andExpect(jsonPath("controls[0].result.authorName").value("Annuaire assigned"))
        .andExpect(jsonPath("controls[0].result.recordedAt").exists());
    recordControl(id, control, 2, "assigned", "AUDITOR", "CONFORMING", "Version périmée")
        .andExpect(status().isConflict());
    recordControl(
            id,
            control,
            3,
            "assigned",
            "AUDITOR",
            "CONFORMING",
            "Après vérification, références distinctes")
        .andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM program_change_log WHERE mission_id=? AND resource='RESULT'",
            Integer.class,
            java.util.UUID.fromString(id)));
    String risk =
        jdbc.queryForObject(
                "SELECT id FROM mission_risks WHERE mission_id=?",
                java.util.UUID.class,
                java.util.UUID.fromString(id))
            .toString();
    mvc.perform(
            put("/missions/" + id + "/controls")
                .with(programJwt("owner", "AUDIT_MANAGER"))
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of(
                            "version",
                            4,
                            "id",
                            control,
                            "objective",
                            "Doublons",
                            "procedure",
                            "Nouvelle procédure",
                            "riskIds",
                            java.util.List.of(risk)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("controls[0].result").isEmpty());
    org.junit.jupiter.api.Assertions.assertEquals(
        "CONFORMING",
        jdbc.queryForObject(
            "SELECT previous_data->'result'->>'outcome' FROM program_change_log WHERE mission_id=? AND resource='CONTROL' AND previous_data IS NOT NULL",
            String.class,
            java.util.UUID.fromString(id)));
  }

  @Test
  void resultsEnforceAssignmentLifecycleAndInputValidation() throws Exception {
    String id = create(), control = programControl(id);
    recordControl(id, control, 2, "assigned", "AUDITOR", "CONFORMING", "Test")
        .andExpect(status().isConflict());
    jdbc.update(
        "UPDATE missions SET status='IN_PROGRESS',planned_start_date=current_date,planned_end_date=current_date,started_at=now(),validator_subject='validator' WHERE id=?",
        java.util.UUID.fromString(id));
    recordControl(id, control, 2, "owner", "AUDIT_MANAGER", "CONFORMING", "Test")
        .andExpect(status().isForbidden());
    recordControl(id, control, 2, "validator", "AUDIT_VALIDATOR", "CONFORMING", "Test")
        .andExpect(status().isForbidden());
    recordControl(id, control, 2, "stranger", "AUDITOR", "CONFORMING", "Test")
        .andExpect(status().isNotFound());
    recordControl(id, control, 2, "assigned", "AUDITOR", "INVALID", "Test")
        .andExpect(status().isBadRequest());
    recordControl(id, control, 2, "assigned", "AUDITOR", "CONFORMING", " ")
        .andExpect(status().isBadRequest());
    String other = create(), foreign = programControl(other);
    recordControl(id, foreign, 2, "assigned", "AUDITOR", "CONFORMING", "Test")
        .andExpect(status().isNotFound());
    recordControl(
            id, control, 2, "administrator", "SUPER_ADMIN", "NOT_APPLICABLE", "Hors périmètre")
        .andExpect(status().isOk());
    jdbc.update(
        "UPDATE missions SET status='PENDING_REVIEW',submitted_at=now() WHERE id=?",
        java.util.UUID.fromString(id));
    recordControl(id, control, 3, "assigned", "AUDITOR", "CONFORMING", "Test")
        .andExpect(status().isConflict());
    jdbc.update(
        "UPDATE missions SET status='APPROVED',reviewed_at=now(),reviewed_by='validator',review_decision='APPROVED',review_comment='Validé' WHERE id=?",
        java.util.UUID.fromString(id));
    recordControl(id, control, 3, "administrator", "SUPER_ADMIN", "CONFORMING", "Test")
        .andExpect(status().isConflict());
    jdbc.update(
        "UPDATE missions SET status='CLOSED',closed_at=now() WHERE id=?",
        java.util.UUID.fromString(id));
    recordControl(id, control, 3, "assigned", "AUDITOR", "CONFORMING", "Test")
        .andExpect(status().isConflict());
  }

  @org.springframework.test.context.bean.override.mockito.MockitoBean EvidenceStore evidenceStore;

  @Test
  void privateEvidenceChecksPermissionsVersionAndCompensatesConflict() throws Exception {
    String id = create(), control = programControl(id);
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "facture.pdf", "application/pdf", "%PDF-1.4\nsynthetic".getBytes());
    String path = "/missions/" + id + "/controls/" + control + "/evidence";
    mvc.perform(
            multipart(path)
                .file(file)
                .param("version", "2")
                .with(programJwt("assigned", "AUDITOR")))
        .andExpect(status().isConflict());
    jdbc.update(
        "UPDATE missions SET status='IN_PROGRESS',planned_start_date=current_date,planned_end_date=current_date,started_at=now() WHERE id=?",
        java.util.UUID.fromString(id));
    mvc.perform(
            multipart(path)
                .file(file)
                .param("version", "2")
                .with(programJwt("owner", "AUDIT_MANAGER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            multipart(path)
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "fake.pdf", "application/pdf", "not a PDF".getBytes()))
                .param("version", "2")
                .with(programJwt("assigned", "AUDITOR")))
        .andExpect(status().isBadRequest());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return null;
            })
        .when(evidenceStore)
        .put(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
    var response =
        mvc.perform(
                multipart(path)
                    .file(file)
                    .param("version", "2")
                    .with(programJwt("assigned", "AUDITOR")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("originalName").value("facture.pdf"))
            .andExpect(jsonPath("authorName").value("Annuaire assigned"))
            .andReturn();
    String proof = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(get(path).with(programJwt("owner", "AUDIT_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("length()").value(1));
    mvc.perform(get(path + "/" + proof).with(programJwt("stranger", "AUDITOR")))
        .andExpect(status().isNotFound());
    jdbc.update(
        "DELETE FROM mission_assignments WHERE mission_id=?", java.util.UUID.fromString(id));
    mvc.perform(get(path + "/" + proof).with(programJwt("assigned", "AUDITOR")))
        .andExpect(status().isNotFound());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              jdbc.update(
                  "UPDATE missions SET version=version+1 WHERE id=?",
                  java.util.UUID.fromString(id));
              return null;
            })
        .when(evidenceStore)
        .put(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
    mvc.perform(
            multipart(path)
                .file(file)
                .param("version", "3")
                .with(programJwt("administrator", "SUPER_ADMIN")))
        .andExpect(status().isConflict());
    org.mockito.Mockito.verify(evidenceStore).delete(org.mockito.ArgumentMatchers.anyString());
  }

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  UserAdministration userAdministration;

  @Test
  void adminSummaryCountsStatusesAndIgnoresDeletedMissions() throws Exception {
    var before =
        mapper.readTree(
            mvc.perform(get("/admin/summary").with(programJwt("administrator", "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String marker = "summary-" + java.util.UUID.randomUUID();
    jdbc.update(
        "INSERT INTO missions(id,version,title,description,perimeter,owner_subject,status,created_at) VALUES (?,0,?,'Test','Finance','summary-owner','DRAFT',now())",
        java.util.UUID.randomUUID(),
        marker + "-draft");
    jdbc.update(
        "INSERT INTO missions(id,version,title,description,perimeter,owner_subject,status,created_at,planned_start_date,planned_end_date,started_at) VALUES (?,0,?,'Test','Finance','summary-owner','IN_PROGRESS',now(),current_date-2,current_date-1,now())",
        java.util.UUID.randomUUID(),
        marker + "-overdue");
    jdbc.update(
        "INSERT INTO missions(id,version,title,description,perimeter,owner_subject,status,created_at,deleted_at) VALUES (?,0,?,'Test','Finance','summary-owner','DRAFT',now(),now())",
        java.util.UUID.randomUUID(),
        marker + "-deleted");

    var after =
        mapper.readTree(
            mvc.perform(get("/admin/summary").with(programJwt("administrator", "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    org.junit.jupiter.api.Assertions.assertEquals(
        before.path("total").asLong() + 2, after.path("total").asLong());
    org.junit.jupiter.api.Assertions.assertEquals(
        before.path("byStatus").path("DRAFT").asLong() + 1,
        after.path("byStatus").path("DRAFT").asLong());
    org.junit.jupiter.api.Assertions.assertEquals(
        before.path("byStatus").path("IN_PROGRESS").asLong() + 1,
        after.path("byStatus").path("IN_PROGRESS").asLong());
    org.junit.jupiter.api.Assertions.assertEquals(
        before.path("overdue").asLong() + 1, after.path("overdue").asLong());
  }

  @Test
  void dashboardPrioritiesAreOrderedAndBounded() throws Exception {
    String marker = "priority-" + java.util.UUID.randomUUID();
    for (int i = 0; i < 9; i++) {
      jdbc.update(
          "INSERT INTO missions(id,version,title,description,perimeter,owner_subject,status,created_at,planned_start_date,planned_end_date,started_at,closed_at,deleted_at) VALUES (?,0,?,'Test','Finance','priority-owner',?,now(),current_date-30,current_date-20+?,now(),?,?)",
          java.util.UUID.randomUUID(), marker + "-" + i,
          i == 7 ? "CLOSED" : "IN_PROGRESS", i,
          i == 7 ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(2)) : null,
          i == 8 ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }
    mvc.perform(get("/missions").param("q", marker).param("overdue", "true")
            .param("sort", "end").param("size", "5").with(adminJwt("priority-admin")))
        .andExpect(status().isOk()).andExpect(jsonPath("total").value(7))
        .andExpect(jsonPath("items.length()").value(5))
        .andExpect(jsonPath("items[0].title").value(marker + "-0"))
        .andExpect(jsonPath("items[4].title").value(marker + "-4"));
  }

  @Test
  void searchFiltersAcrossPagesAndPreservesVisibility() throws Exception {
    String marker = "search-" + java.util.UUID.randomUUID();
    for (int i = 0; i < 22; i++)
      jdbc.update(
          "INSERT INTO missions(id,version,title,description,perimeter,owner_subject,status,created_at,planned_start_date,planned_end_date,started_at) VALUES (?,0,?,'Test','Finance','search-owner','IN_PROGRESS',now(),current_date-2,current_date-1,now())",
          java.util.UUID.randomUUID(),
          marker + "-" + i);
    mvc.perform(
            get("/missions")
                .param("q", marker)
                .param("page", "1")
                .param("size", "20")
                .with(jwt().jwt(j -> j.subject("search-owner"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("total").value(22))
        .andExpect(jsonPath("items.length()").value(2));
    mvc.perform(
            get("/missions")
                .param("q", marker + "-21")
                .param("status", "IN_PROGRESS")
                .param("owner", "search-owner")
                .param("overdue", "true")
                .param("sort", "end")
                .with(adminJwt("search-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("total").value(1));
    mvc.perform(
            get("/missions").param("q", marker).with(jwt().jwt(j -> j.subject("search-outsider"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("total").value(0));
    mvc.perform(
            get("/missions")
                .param("q", marker)
                .param("status", "CLOSED")
                .with(adminJwt("search-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("total").value(0));
    mvc.perform(get("/missions?sort=invalid").with(adminJwt("search-admin")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void userAdministrationProtectsRolesAndRevokesTokens() throws Exception {
    var target = java.util.UUID.randomUUID();
    var actor = java.util.UUID.randomUUID().toString();
    org.mockito.Mockito.when(userAdministration.token()).thenReturn("private-test-token");
    org.mockito.Mockito.when(userAdministration.sendPasswordAction(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn("EMAIL_SENT");
    org.mockito.Mockito.doReturn(java.util.Map.of("email", "test@example.invalid"))
        .when(userAdministration).user(target.toString(), "private-test-token");
    String body =
        "{\"firstName\":\"Test\",\"lastName\":\"User\",\"email\":\"test@example.invalid\",\"enabled\":false,\"roles\":[\"AUDITOR\"],\"reason\":\"Test de désactivation\"}";
    mvc.perform(get("/admin/users").with(jwt())).andExpect(status().isForbidden());
    mvc.perform(
            put("/admin/users/" + target).with(jwt()).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/admin/users/" + actor)
                .with(adminJwt(actor))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/admin/users/" + target)
                .with(adminJwt(actor))
                .contentType("application/json")
                .content(body.replace("AUDITOR", "realm-admin")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/admin/users/" + target)
                .with(adminJwt(actor))
                .contentType("application/json")
                .content(body.replace("test@example.invalid", "redirect@example.invalid")))
        .andExpect(status().isBadRequest());
    org.mockito.Mockito.verify(userAdministration, org.mockito.Mockito.never())
        .logout(target.toString(), "private-test-token");
    org.junit.jupiter.api.Assertions.assertEquals(0,
        jdbc.queryForObject("SELECT count(*) FROM account_revocations WHERE subject=?", Integer.class, target.toString()));
    mvc.perform(
            put("/admin/users/" + target)
                .with(adminJwt(actor))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    org.mockito.Mockito.verify(userAdministration).logout(target.toString(), "private-test-token");
    mvc.perform(
            get("/account/security")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(target.toString())
                                    .issuedAt(java.time.Instant.now().minusSeconds(10)))))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/admin/users/" + target + "/history").with(adminJwt(actor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("events[0].outcome").value("SUCCESS"));
    mvc.perform(
            post("/admin/users/" + target + "/reset-password")
                .with(adminJwt(actor))
                .contentType("application/json")
                .content("{\"reason\":\"Test de courrier\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("EMAIL_SENT"))
        .andExpect(jsonPath("temporaryPassword").doesNotExist());
    org.mockito.Mockito.doThrow(new IllegalStateException("network failure"))
        .when(userAdministration)
        .sendPasswordAction(
            org.mockito.ArgumentMatchers.eq(target.toString()),
            org.mockito.ArgumentMatchers.anyString());
    mvc.perform(
            post("/admin/users/" + target + "/reset-password")
                .with(adminJwt(actor))
                .contentType("application/json")
                .content("{\"reason\":\"Test panne\"}"))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(get("/admin/users/" + target + "/history").with(adminJwt(actor)))
        .andExpect(jsonPath("events[0].outcome").value("UNCERTAIN"));
  }
  @Test
  void validatorAndAssigneeMustRemainSeparateAcrossApiAndDatabase() throws Exception {
    org.mockito.Mockito.doNothing().when(directory).validateValidator("assigned");
    mvc.perform(post("/missions")
            .with(jwt().jwt(j -> j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_AUDIT_MANAGER")))
            .contentType("application/json")
            .content("{\"title\":\"Séparation\",\"description\":\"Test\",\"perimeter\":\"Achats\",\"assigneeSubjects\":[\"assigned\"],\"validatorSubject\":\"assigned\"}"))
        .andExpect(status().isBadRequest());
    String id = create();
    lifecycle(id, "validator", "{\"version\":0,\"validatorSubject\":\"assigned\"}")
        .andExpect(status().isBadRequest());
    jdbc.update("DELETE FROM mission_assignments WHERE mission_id=?", java.util.UUID.fromString(id));
    lifecycle(id, "validator", "{\"version\":0,\"validatorSubject\":\"assigned\"}")
        .andExpect(status().isOk());
    lifecycle(id, "assignments", "{\"version\":1,\"assigneeSubjects\":[\"assigned\"]}")
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("INSERT INTO mission_assignments(mission_id,subject) VALUES (?,?)", java.util.UUID.fromString(id), "assigned"));
    jdbc.update("UPDATE missions SET validator_subject=NULL WHERE id=?", java.util.UUID.fromString(id));
    jdbc.update("INSERT INTO mission_assignments(mission_id,subject) VALUES (?,?)", java.util.UUID.fromString(id), "assigned");
    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE missions SET validator_subject='assigned' WHERE id=?", java.util.UUID.fromString(id)));
  }

  @Test
  void databaseSerializesConcurrentValidatorAndAssignmentChanges() throws Exception {
    var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      for (boolean validatorFirst : new boolean[] {true, false}) {
        java.util.UUID mission = java.util.UUID.fromString(create());
        jdbc.update("DELETE FROM mission_assignments WHERE mission_id=?", mission);
        try (var first = dataSource.getConnection()) {
          first.setAutoCommit(false);
          try (var statement = first.prepareStatement(validatorFirst
              ? "UPDATE missions SET validator_subject='assigned' WHERE id=?"
              : "INSERT INTO mission_assignments(mission_id,subject) VALUES (?,'assigned')")) {
            statement.setObject(1, mission);
            statement.executeUpdate();
          }
          var second = workers.submit(() -> {
            try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(validatorFirst
                    ? "INSERT INTO mission_assignments(mission_id,subject) VALUES (?,'assigned')"
                    : "UPDATE missions SET validator_subject='assigned' WHERE id=?")) {
              statement.setObject(1, mission);
              statement.executeUpdate();
              return false;
            } catch (java.sql.SQLException conflict) {
              return "23514".equals(conflict.getSQLState());
            }
          });
          Thread.sleep(150);
          org.junit.jupiter.api.Assertions.assertFalse(second.isDone(), "Concurrent write must wait on mission lock");
          first.commit();
          org.junit.jupiter.api.Assertions.assertTrue(second.get(5, java.util.concurrent.TimeUnit.SECONDS),
              "The second write must reject the new conflict");
        }
      }
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void closedMissionAssignmentsAreFrozen() throws Exception {
    String id = create();
    lifecycle(id, "schedule", "{\"version\":0,\"plannedStartDate\":\"2026-11-01\",\"plannedEndDate\":\"2026-11-02\"}")
        .andExpect(status().isOk());
    lifecycle(id, "status", "{\"version\":1,\"status\":\"IN_PROGRESS\"}").andExpect(status().isOk());
    approveMission(id, 2);
    lifecycle(id, "status", "{\"version\":5,\"status\":\"CLOSED\"}").andExpect(status().isOk());
    lifecycle(id, "assignments", "{\"version\":6,\"assigneeSubjects\":[]}")
        .andExpect(status().isConflict());
  }

  @Test
  void evidenceDownloadDetectsTamperingWhileStreaming() throws Exception {
    String id = create(), control = programControl(id);
    java.util.UUID missionId = java.util.UUID.fromString(id);
    java.util.UUID controlId = java.util.UUID.fromString(control);
    java.util.UUID proofId = java.util.UUID.randomUUID();
    String key = "evidence/" + proofId;
    byte[] expected = "%PDF-1.4\noriginal".getBytes();
    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(expected));
    jdbc.update("INSERT INTO control_evidence(id,mission_id,control_id,object_key,original_name,media_type,byte_size,sha256,author_subject,author_name) VALUES (?,?,?,?,?,?,?,?,?,?)",
        proofId, missionId, controlId, key, "existing.pdf", "application/pdf", expected.length, hash, "assigned", "Annuaire assigned");
    org.mockito.Mockito.when(evidenceStore.get(key)).thenReturn(new java.io.ByteArrayInputStream("%PDF-1.4\ntampered".getBytes()));
    var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "none").subject("owner").build();
    var body = evidenceController.download(missionId, controlId, proofId, jwt).getBody();
    org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
        () -> body.writeTo(new java.io.ByteArrayOutputStream()));
  }

  @Test
  void missionEvidenceQuotaRejectsOverflowAndDeletesUnreferencedObject() throws Exception {
    String id = create(), control = programControl(id);
    java.util.UUID missionId = java.util.UUID.fromString(id);
    java.util.UUID controlId = java.util.UUID.fromString(control);
    jdbc.update("UPDATE missions SET status='IN_PROGRESS',planned_start_date=current_date,planned_end_date=current_date,started_at=now() WHERE id=?", missionId);
    jdbc.update("INSERT INTO control_evidence(id,mission_id,control_id,object_key,original_name,media_type,byte_size,sha256,author_subject,author_name) VALUES (?,?,?,?,?,?,?,?,?,?)",
        java.util.UUID.randomUUID(), missionId, controlId, "evidence/" + java.util.UUID.randomUUID(), "existing.pdf", "application/pdf", 10485760L, "0".repeat(64), "assigned", "Annuaire assigned");
    var file = new org.springframework.mock.web.MockMultipartFile("file", "next.pdf", "application/pdf", "%PDF-1.4\nsynthetic".getBytes());
    mvc.perform(multipart("/missions/" + id + "/controls/" + control + "/evidence")
            .file(file).param("version", "2").with(programJwt("assigned", "AUDITOR")))
        .andExpect(status().isPayloadTooLarge());
    org.mockito.Mockito.verify(evidenceStore).delete(org.mockito.ArgumentMatchers.anyString());
  }

  java.util.Map<String, Object> findingBody(
      Long version, String cause, String recommendation, String severity, String... controls) {
    var body = new java.util.HashMap<String, Object>();
    if (version != null) body.put("version", version);
    body.put("title", "Factures en double");
    body.put("condition", "Deux paiements pour la même facture");
    body.put("criterion", "Une facture est payée une seule fois");
    body.put("cause", cause);
    body.put("consequence", "Perte financière");
    body.put("recommendation", recommendation);
    body.put("severity", severity);
    body.put("controlIds", java.util.List.of(controls));
    return body;
  }

  org.springframework.test.web.servlet.ResultActions saveFinding(
      String mission, String finding, String subject, String role, java.util.Map<String, Object> body)
      throws Exception {
    var request =
        finding == null
            ? post("/missions/" + mission + "/findings")
            : put("/missions/" + mission + "/findings/" + finding);
    return mvc.perform(
        request
            .with(programJwt(subject, role))
            .contentType("application/json")
            .content(mapper.writeValueAsString(body)));
  }

  org.springframework.test.web.servlet.ResultActions moveFinding(
      String mission, String finding, long version, String subject, String role, String action, String comment)
      throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("version", version);
    body.put("action", action);
    body.put("comment", comment);
    return mvc.perform(
        put("/missions/" + mission + "/findings/" + finding + "/transition")
            .with(programJwt(subject, role))
            .contentType("application/json")
            .content(mapper.writeValueAsString(body)));
  }

  String startedMissionWithNonConformingControl() throws Exception {
    String id = create(), control = programControl(id);
    jdbc.update(
        "UPDATE missions SET status='IN_PROGRESS',planned_start_date=current_date,planned_end_date=current_date,started_at=now(),validator_subject='validator' WHERE id=?",
        java.util.UUID.fromString(id));
    recordControl(id, control, 2, "assigned", "AUDITOR", "NON_CONFORMING", "Doublon constaté")
        .andExpect(status().isOk());
    return id;
  }

  String firstControl(String mission) {
    return jdbc.queryForObject(
            "SELECT id FROM mission_controls WHERE mission_id=?",
            java.util.UUID.class,
            java.util.UUID.fromString(mission))
        .toString();
  }

  String idOf(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
    return mapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void findingsFollowReviewLifecycleAndKeepAppendOnlyTrace() throws Exception {
    String id = startedMissionWithNonConformingControl(), control = firstControl(id);
    String finding =
        idOf(
            saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, " ", null, "HIGH", control))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("reference").value(1))
                .andExpect(jsonPath("status").value("DRAFT"))
                .andExpect(jsonPath("version").value(0))
                .andExpect(jsonPath("cause").isEmpty())
                .andExpect(jsonPath("riskIds.length()").value(1))
                .andExpect(jsonPath("inconsistentControls").value(false))
                .andExpect(jsonPath("controls[0].outcome").value("NON_CONFORMING")));
    moveFinding(id, finding, 0, "assigned", "AUDITOR", "SUBMIT", null)
        .andExpect(status().isConflict());
    saveFinding(id, finding, "assigned", "AUDITOR", findingBody(0L, "Contrôle absent", "Bloquer les doublons", "CRITICAL", control))
        .andExpect(status().isOk())
        .andExpect(jsonPath("version").value(1))
        .andExpect(jsonPath("severity").value("CRITICAL"));
    saveFinding(id, finding, "assigned", "AUDITOR", findingBody(0L, "Périmé", "Périmé", "LOW", control))
        .andExpect(status().isConflict());
    moveFinding(id, finding, 1, "assigned", "AUDITOR", "SUBMIT", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("SUBMITTED"))
        .andExpect(jsonPath("submittedBy").value("assigned"));
    saveFinding(id, finding, "assigned", "AUDITOR", findingBody(2L, "Soumis", "Soumis", "LOW", control))
        .andExpect(status().isConflict());
    moveFinding(id, finding, 2, "assigned", "AUDITOR", "VALIDATE", null)
        .andExpect(status().isForbidden());
    moveFinding(id, finding, 2, "owner", "AUDIT_MANAGER", "RETURN", " ")
        .andExpect(status().isBadRequest());
    moveFinding(id, finding, 2, "owner", "AUDIT_MANAGER", "RETURN", "Préciser le montant")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("DRAFT"))
        .andExpect(jsonPath("decisionComment").value("Préciser le montant"));
    moveFinding(id, finding, 3, "assigned", "AUDITOR", "SUBMIT", null).andExpect(status().isOk());
    moveFinding(id, finding, 4, "assigned", "AUDITOR", "WITHDRAW", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("DRAFT"));
    moveFinding(id, finding, 5, "assigned", "AUDITOR", "SUBMIT", null).andExpect(status().isOk());
    moveFinding(id, finding, 6, "owner", "AUDIT_MANAGER", "VALIDATE", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("VALIDATED"))
        .andExpect(jsonPath("decidedBy").value("owner"));
    moveFinding(id, finding, 7, "owner", "AUDIT_MANAGER", "REJECT", "Trop tard")
        .andExpect(status().isConflict());
    moveFinding(id, finding, 7, "owner", "AUDIT_MANAGER", "REOPEN", "Montant révisé")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("DRAFT"));
    mvc.perform(delete("/missions/" + id + "/findings/" + finding + "?version=8").with(programJwt("assigned", "AUDITOR")))
        .andExpect(status().isConflict());
    mvc.perform(get("/missions/" + id + "/findings/" + finding + "/history").with(programJwt("validator", "AUDIT_VALIDATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.contains(
            "CREATED", "UPDATED", "SUBMITTED", "RETURNED", "SUBMITTED", "WITHDRAWN", "SUBMITTED", "VALIDATED", "REOPENED")))
        .andExpect(jsonPath("$[3].reason").value("Préciser le montant"));
    org.junit.jupiter.api.Assertions.assertEquals(
        "Contrôle absent",
        jdbc.queryForObject(
            "SELECT resulting_data->>'cause' FROM finding_change_log WHERE finding_id=? AND action='UPDATED'",
            String.class,
            java.util.UUID.fromString(finding)));
    String draft =
        idOf(saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, null, null, "LOW", control))
            .andExpect(jsonPath("reference").value(2)));
    mvc.perform(delete("/missions/" + id + "/findings/" + draft + "?version=0").with(programJwt("assigned", "AUDITOR")))
        .andExpect(status().isNoContent());
    saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, null, null, "LOW", control))
        .andExpect(jsonPath("reference").value(3));
    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM finding_change_log WHERE finding_id=? AND action='DELETED' AND resulting_data IS NULL",
            Integer.class,
            java.util.UUID.fromString(draft)));
    mvc.perform(get("/missions/" + id + "/findings").with(programJwt("owner", "AUDIT_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].reference").value(1));
  }

  @Test
  void findingsEnforcePermissionsSeparationAndMissionStatus() throws Exception {
    String id = create(), control = programControl(id);
    saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, null, null, "LOW", control))
        .andExpect(status().isConflict());
    jdbc.update(
        "UPDATE missions SET status='IN_PROGRESS',planned_start_date=current_date,planned_end_date=current_date,started_at=now(),validator_subject='validator' WHERE id=?",
        java.util.UUID.fromString(id));
    saveFinding(id, null, "stranger", "AUDITOR", findingBody(null, null, null, "LOW", control))
        .andExpect(status().isNotFound());
    mvc.perform(get("/missions/" + id + "/findings").with(programJwt("stranger", "AUDIT_MANAGER")))
        .andExpect(status().isNotFound());
    saveFinding(id, null, "owner", "AUDIT_MANAGER", findingBody(null, null, null, "LOW", control))
        .andExpect(status().isForbidden());
    saveFinding(id, null, "validator", "AUDIT_VALIDATOR", findingBody(null, null, null, "LOW", control))
        .andExpect(status().isForbidden());
    saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, null, null, "SEVERE", control))
        .andExpect(status().isBadRequest());
    saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, null, null, "LOW"))
        .andExpect(status().isBadRequest());
    String[] tooMany = new String[21];
    for (int i = 0; i < tooMany.length; i++) tooMany[i] = java.util.UUID.randomUUID().toString();
    saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, null, null, "LOW", tooMany))
        .andExpect(status().isBadRequest());
    String other = create(), foreign = programControl(other);
    saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, null, null, "LOW", control, foreign))
        .andExpect(status().isBadRequest());

    String unchecked =
        idOf(saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, "Cause", "Recommandation", "LOW", control))
            .andExpect(jsonPath("inconsistentControls").value(true)));
    moveFinding(id, unchecked, 0, "assigned", "AUDITOR", "SUBMIT", null).andExpect(status().isConflict());
    recordControl(id, control, 2, "assigned", "AUDITOR", "NON_CONFORMING", "Doublon").andExpect(status().isOk());

    String byAdmin =
        idOf(saveFinding(id, null, "administrator", "SUPER_ADMIN", findingBody(null, "Cause", "Recommandation", "MODERATE", control)));
    moveFinding(id, byAdmin, 0, "administrator", "SUPER_ADMIN", "SUBMIT", null).andExpect(status().isOk());
    moveFinding(id, byAdmin, 1, "administrator", "SUPER_ADMIN", "VALIDATE", null).andExpect(status().isForbidden());
    moveFinding(id, byAdmin, 1, "owner", "AUDIT_MANAGER", "REJECT", null).andExpect(status().isBadRequest());
    moveFinding(id, byAdmin, 1, "owner", "AUDIT_MANAGER", "REJECT", "Non fondé")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("REJECTED"));
    moveFinding(id, byAdmin, 2, "owner", "AUDIT_MANAGER", "REOPEN", "Revoir").andExpect(status().isConflict());

    moveFinding(id, unchecked, 0, "assigned", "AUDITOR", "SUBMIT", null).andExpect(status().isOk());
    moveFinding(id, unchecked, 1, "validator", "AUDIT_VALIDATOR", "VALIDATE", null).andExpect(status().isForbidden());
    moveFinding(id, unchecked, 1, "administrator", "SUPER_ADMIN", "VALIDATE", null).andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.List.of(true, true, false, true),
        jdbc.queryForList(
            "SELECT override FROM finding_change_log WHERE mission_id=? AND actor_subject IN ('administrator','owner') ORDER BY occurred_at,id",
            Boolean.class,
            java.util.UUID.fromString(id)));

    mvc.perform(get("/missions/" + id + "/findings").with(programJwt("validator", "AUDIT_VALIDATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    jdbc.update("UPDATE missions SET status='PENDING_REVIEW',submitted_at=now() WHERE id=?", java.util.UUID.fromString(id));
    saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, null, null, "LOW", control))
        .andExpect(status().isConflict());
    moveFinding(id, unchecked, 2, "owner", "AUDIT_MANAGER", "REOPEN", "Revoir").andExpect(status().isConflict());
    jdbc.update("UPDATE missions SET deleted_at=now() WHERE id=?", java.util.UUID.fromString(id));
    mvc.perform(get("/missions/" + id + "/findings").with(programJwt("assigned", "AUDITOR")))
        .andExpect(status().isNotFound());
  }

  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @Test
  void missionSubmissionWaitsForEveryFindingDecision() throws Exception {
    String id = startedMissionWithNonConformingControl(), control = firstControl(id);
    String kept =
        idOf(saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, "Cause", "Recommandation", "HIGH", control)));
    String dropped =
        idOf(saveFinding(id, null, "assigned", "AUDITOR", findingBody(null, "Cause", "Recommandation", "LOW", control)));
    lifecycle(id, "submission", "{\"version\":3}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("code").value("FINDINGS_PENDING"))
        .andExpect(jsonPath("pending").value(2));
    moveFinding(id, kept, 0, "assigned", "AUDITOR", "SUBMIT", null).andExpect(status().isOk());
    moveFinding(id, dropped, 0, "assigned", "AUDITOR", "SUBMIT", null).andExpect(status().isOk());
    moveFinding(id, kept, 1, "owner", "AUDIT_MANAGER", "VALIDATE", null).andExpect(status().isOk());
    lifecycle(id, "submission", "{\"version\":3}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("pending").value(1));
    overrideMission(id, 3, "APPROVE", "Urgence", "administrator")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("code").value("FINDINGS_PENDING"));
    overrideMission(id, 3, "CLOSE", "Urgence", "administrator")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("code").value("FINDINGS_PENDING"));
    moveFinding(id, dropped, 1, "owner", "AUDIT_MANAGER", "REJECT", "Doublon du constat C-01")
        .andExpect(status().isOk());
    lifecycle(id, "submission", "{\"version\":3}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("PENDING_REVIEW"));
  }

  @Test
  void missionSubmissionCountsFindingsUnderMissionLock() throws Exception {
    String id = startedMissionWithNonConformingControl();
    java.util.UUID missionId = java.util.UUID.fromString(id);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var writer =
        java.util.concurrent.CompletableFuture.runAsync(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(
                        state -> {
                          jdbc.queryForObject("SELECT id FROM missions WHERE id=? FOR UPDATE", java.util.UUID.class, missionId);
                          jdbc.update(
                              "INSERT INTO mission_findings(id,mission_id,reference,title,condition_text,severity,status,created_by,updated_by)"
                                  + " VALUES (?,?,1,'Concurrent','Écrit sous verrou','LOW','DRAFT','assigned','assigned')",
                              java.util.UUID.randomUUID(),
                              missionId);
                          locked.countDown();
                          try {
                            Thread.sleep(700);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    org.junit.jupiter.api.Assertions.assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
    lifecycle(id, "submission", "{\"version\":3}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("code").value("FINDINGS_PENDING"));
    writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
    org.junit.jupiter.api.Assertions.assertEquals(
        "IN_PROGRESS",
        jdbc.queryForObject("SELECT status FROM missions WHERE id=?", String.class, missionId));
  }
}
