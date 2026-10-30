package com.auditsphere.remediation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.flyway.enabled=true",
      "spring.kafka.listener.auto-startup=false",
      "remediation.mission-quota-bytes=10485760"
    })
@AutoConfigureMockMvc
@Testcontainers
class AttachmentIntegrationTest {
  static final long QUOTA = 10485760;
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

  @Container @ServiceConnection
  static PostgreSQLContainer db =
      new PostgreSQLContainer(
          "postgres@sha256:2a0d0fe14825b0939f78a8cad5cd4e6aa68bf94d0e5dd96e24b6d23af4315545");

  static final byte[] PDF = "%PDF-1.7\nsynthetic\n%%EOF".getBytes();
  static final byte[] PNG = {
    (byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1
  };

  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @MockitoBean AuditeeDirectory directory;
  @MockitoBean AttachmentStore store;

  /** In-memory bucket standing for Garage. */
  final Map<String, byte[]> objects = new ConcurrentHashMap<>();

  UUID mission, followUp;
  String manager, auditor, auditee;

  @BeforeEach
  void setUp() {
    doAnswer(i -> objects.put(i.getArgument(0), i.getArgument(1)))
        .when(store)
        .put(anyString(), any(byte[].class), anyString());
    when(store.get(anyString())).thenAnswer(i -> new ByteArrayInputStream(objects.get(i.<String>getArgument(0))));
    doAnswer(i -> objects.remove(i.<String>getArgument(0))).when(store).delete(anyString());
    when(directory.requireAuditee(anyString()))
        .thenAnswer(i -> new AuditeeDirectory.Person(i.getArgument(0), "Audité", true));
    when(directory.names(any())).thenReturn(Map.of());
    mission = UUID.randomUUID();
    String suffix = mission.toString().substring(0, 8);
    manager = "manager-" + suffix;
    auditor = "auditor-" + suffix;
    auditee = "auditee-" + suffix;
    jdbc.update(
        "INSERT INTO mission_scopes(mission_id,title,owner_subject,validator_subject,issue_cycle,state) VALUES (?,?,?,?,1,'ACTIVE')",
        mission, "Mission synthétique", manager, "validator-" + suffix);
    jdbc.update("INSERT INTO mission_scope_auditors(mission_id,subject) VALUES (?,?)", mission, auditor);
    followUp = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO finding_follow_ups(id,mission_id,reference,finding_version,title,severity,condition_text,cause,recommendation,status) VALUES (?,?,1,1,'Constat','HIGH','Condition','Cause','Recommandation','TO_PLAN')",
        followUp, mission);
  }

  static RequestPostProcessor user(String subject, String... roles) {
    return RemediationIntegrationTest.user(subject, roles);
  }

  UUID action() throws Exception {
    return action(followUp);
  }

  UUID action(UUID followUp) throws Exception {
    String body =
        mvc.perform(
                post("/remediation/follow-ups/{id}/actions", followUp)
                    .with(user(manager, "AUDIT_MANAGER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("description", "Action", "ownerSubject", auditee, "dueDate", "2030-01-01"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(mapper.readTree(body).get("id").asString());
  }

  long version(UUID action) {
    return jdbc.queryForObject("SELECT version FROM corrective_actions WHERE id=?", Long.class, action);
  }

  String upload(UUID action, long version, String name, String type, byte[] bytes, RequestPostProcessor as, ResultMatcher expected)
      throws Exception {
    return mvc.perform(
            multipart("/remediation/actions/{id}/attachments", action)
                .file(new MockMultipartFile("file", name, type, bytes))
                .param("version", String.valueOf(version))
                .with(as))
        .andExpect(expected)
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  byte[] download(UUID action, UUID attachment, RequestPostProcessor as) throws Exception {
    var started =
        mvc.perform(get("/remediation/actions/{id}/attachments/{a}", action, attachment).with(as))
            .andExpect(request().asyncStarted())
            .andReturn();
    return mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
  }

  @Test
  void auditeeAttachesThenDeclaresDoneAndAttachmentsFreeze() throws Exception {
    UUID action = action();
    var auditeeUser = user(auditee, "AUDITEE");
    var saved = mapper.readTree(upload(action, 0, "preuve.pdf", "application/pdf", PDF, auditeeUser, status().isCreated()));
    UUID first = UUID.fromString(saved.get("id").asString());
    assertEquals(64, saved.get("sha256").asString().length());
    assertArrayEquals(PDF, download(action, first, user(auditor, "AUDITOR")));
    assertArrayEquals(PDF, download(action, first, auditeeUser));
    mvc.perform(get("/remediation/actions/{id}/attachments/{a}", action, first).with(user("outsider", "AUDITOR")))
        .andExpect(status().isNotFound());
    var detail =
        mapper.readTree(
            mvc.perform(get("/remediation/actions/{id}", action).with(auditeeUser))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertEquals("preuve.pdf", detail.get("attachments").get(0).get("fileName").asString());

    // Removal by its author before completion: the object goes after the commit.
    mvc.perform(delete("/remediation/actions/{id}/attachments/{a}", action, first).param("version", "0").with(auditeeUser))
        .andExpect(status().isConflict());
    mvc.perform(delete("/remediation/actions/{id}/attachments/{a}", action, first).param("version", "1").with(user(manager, "AUDIT_MANAGER")))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/remediation/actions/{id}/attachments/{a}", action, first).param("version", "1").with(auditeeUser))
        .andExpect(status().isNoContent());
    assertTrue(objects.isEmpty());

    var image = mapper.readTree(upload(action, 2, "capture.png", "image/png", PNG, auditeeUser, status().isCreated()));
    mvc.perform(
            put("/remediation/actions/{id}/transition", action)
                .with(auditeeUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("version", 3, "action", "COMPLETE", "comment", "Fait"))))
        .andExpect(status().isOk());
    upload(action, 4, "tard.pdf", "application/pdf", PDF, auditeeUser, status().isConflict());
    mvc.perform(
            delete("/remediation/actions/{id}/attachments/{a}", action, image.get("id").asString())
                .param("version", "4")
                .with(auditeeUser))
        .andExpect(status().isConflict());
    assertEquals(1, objects.size());
    assertEquals(
        List.of("CREATED", "ATTACHMENT_ADDED", "ATTACHMENT_REMOVED", "ATTACHMENT_ADDED", "COMPLETED"),
        jdbc.queryForList(
            "SELECT action FROM remediation_change_log WHERE action_id=? ORDER BY occurred_at, entity_version",
            String.class,
            action));
  }

  @Test
  void invalidFilesAndRefusedRecordsLeaveNoObject() throws Exception {
    UUID action = action();
    var auditeeUser = user(auditee, "AUDITEE");
    upload(action, 0, "note.pdf", "application/pdf", "pas un PDF".getBytes(), auditeeUser, status().isBadRequest());
    upload(action, 0, "image.pdf", "application/pdf", PNG, auditeeUser, status().isBadRequest());
    upload(action, 0, "a/b.pdf", "application/pdf", PDF, auditeeUser, status().isBadRequest());
    upload(action, 0, "vide.pdf", "application/pdf", new byte[0], auditeeUser, status().isPayloadTooLarge());
    upload(action, 0, "preuve.pdf", "application/pdf", PDF, user(manager, "AUDIT_MANAGER"), status().isForbidden());
    upload(action, 0, "preuve.pdf", "application/pdf", PDF, user("auditee-other", "AUDITEE"), status().isNotFound());
    upload(action, 5, "preuve.pdf", "application/pdf", PDF, auditeeUser, status().isConflict());
    for (int i = 0; i < AttachmentController.MAX_PER_ACTION; i++)
      upload(action, i, "preuve-" + i + ".pdf", "application/pdf", PDF, auditeeUser, status().isCreated());
    assertEquals(10, objects.size());
    // Refused under lock after the object was stored: the object is removed again.
    upload(action, 10, "onzieme.pdf", "application/pdf", PDF, auditeeUser, status().isConflict());
    assertEquals(10, objects.size());
    verify(store, times(11)).put(anyString(), any(byte[].class), anyString());
  }

  @Test
  void concurrentUploadsCannotExceedTheMissionQuota() throws Exception {
    // Two follow-ups: only the mission scope lock serializes the two uploads.
    UUID otherFollowUp = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO finding_follow_ups(id,mission_id,reference,finding_version,title,severity,condition_text,cause,recommendation,status) VALUES (?,?,2,1,'Constat 2','LOW','Condition','Cause','Recommandation','TO_PLAN')",
        otherFollowUp, mission);
    UUID first = action(), second = action(otherFollowUp);
    jdbc.update(
        "INSERT INTO action_attachments(id,action_id,mission_id,object_key,file_name,content_type,size_bytes,sha256,uploaded_by) VALUES (?,?,?,?,'ancien.pdf','application/pdf',?,?,?)",
        UUID.randomUUID(), first, mission, "key-" + UUID.randomUUID(), QUOTA - PDF.length - 1, "a".repeat(64), auditee);
    var auditeeUser = user(auditee, "AUDITEE");
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    // Holds every insert into action_attachments, so both uploads are in flight at the same time.
    var gate = jdbc.getDataSource().getConnection();
    gate.setAutoCommit(false);
    gate.createStatement().execute("LOCK TABLE action_attachments IN SHARE ROW EXCLUSIVE MODE");
    try {
      var results = new ArrayList<Future<Integer>>();
      for (UUID action : List.of(first, second))
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return mvc.perform(
                          multipart("/remediation/actions/{id}/attachments", action)
                              .file(new MockMultipartFile("file", "preuve.pdf", "application/pdf", PDF))
                              .param("version", "0")
                              .with(auditeeUser))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      start.countDown();
      // One upload waits to insert, the other waits on the mission scope lock, until the gate opens.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND datname=current_database()",
              Integer.class)
          < 2) {
        assertTrue(System.nanoTime() < deadline, "uploads never reached the lock");
        Thread.sleep(20);
      }
      gate.commit();
      var statuses = new ArrayList<Integer>();
      for (var result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
      Collections.sort(statuses);
      assertEquals(List.of(201, 413), statuses);
    } finally {
      gate.rollback();
      gate.close();
      pool.shutdownNow();
    }
    assertEquals(1, objects.size());
    assertEquals(
        QUOTA - 1,
        jdbc.queryForObject(
            "SELECT sum(size_bytes) FROM action_attachments WHERE mission_id=?", Long.class, mission));
  }

  @Test
  void tamperedObjectIsNeverServedAsComplete() throws Exception {
    UUID action = action();
    var saved =
        mapper.readTree(upload(action, 0, "preuve.pdf", "application/pdf", PDF, user(auditee, "AUDITEE"), status().isCreated()));
    objects.replaceAll((key, bytes) -> "%PDF-1.7\nmodifie\n%%EOF".getBytes());
    var started =
        mvc.perform(
                get("/remediation/actions/{id}/attachments/{a}", action, saved.get("id").asString())
                    .with(user(auditor, "AUDITOR")))
            .andReturn();
    assertThrows(Exception.class, () -> mvc.perform(asyncDispatch(started)));
  }
}
