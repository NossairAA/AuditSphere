package com.auditsphere.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {"spring.flyway.enabled=true", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class NotificationIntegrationTest {
  static final com.sun.net.httpserver.HttpServer auditSecurity;

  static {
    try {
      auditSecurity = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      auditSecurity.createContext("/account/security", exchange -> {
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

  @org.junit.jupiter.api.AfterAll
  static void stopAuditSecurity() {
    auditSecurity.stop(0);
  }

  @Container @ServiceConnection
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres@sha256:2a0d0fe14825b0939f78a8cad5cd4e6aa68bf94d0e5dd96e24b6d23af4315545");

  @Autowired AssignmentConsumer consumer;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean NotificationStream stream;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @Test
  void liveSignalOnlyFollowsACommittedNewNotification() throws Exception {
    String subject = "live-" + UUID.randomUUID();
    UUID id = UUID.randomUUID();
    String payload = event(id, subject);
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .execute(
            status -> {
              try {
                consumer.receive(payload);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              status.setRollbackOnly();
              return null;
            });
    org.mockito.Mockito.verify(stream, org.mockito.Mockito.never()).changed(subject);
    consumer.receive(payload);
    consumer.receive(payload);
    org.mockito.Mockito.verify(stream, org.mockito.Mockito.times(1)).changed(subject);
  }

  @Test
  void streamRequiresAuthenticationAndFiltersByJwtSubject() throws Exception {
    mvc.perform(get("/notifications/stream")).andExpect(status().isUnauthorized());
    String subject = "stream-" + UUID.randomUUID();
    var result =
        mvc.perform(
                get("/notifications/stream?recipientSubject=someone-else")
                    .with(
                        jwt()
                            .jwt(j -> j.subject(subject).expiresAt(Instant.now().plusSeconds(60)))))
            .andExpect(request().asyncStarted())
            .andExpect(status().isOk())
            .andReturn();
    assertTrue(result.getResponse().getContentAsString().contains("data:refresh"));
    stream.changed("someone-else");
    consumer.receive(event(UUID.randomUUID(), subject));
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                assertEquals(
                    2,
                    result.getResponse().getContentAsString().split("data:refresh", -1).length
                        - 1));
  }

  String event(UUID id, String subject) throws Exception {
    return mapper.writeValueAsString(
        new AssignmentEvent(
            id, "audit.mission.assigned.v1", 1, UUID.randomUUID(), subject, 0, Instant.now()));
  }

  @Test
  void redeliveryPersistsOnceEvenUnderConcurrentConsumers() throws Exception {
    UUID id = UUID.randomUUID();
    String body = event(id, "recipient");
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            threads.submit(
                () -> {
                  consumer.receive(body);
                  return null;
                }));
      for (var task : tasks) task.get();
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE event_id=?", Integer.class, id));
  }

  @Test
  void subjectCannotBeOverriddenByQueryAndAnonymousIsDenied() throws Exception {
    var id = UUID.randomUUID();
    consumer.receive(event(id, "private-recipient"));
    mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/notifications?recipientSubject=private-recipient")
                .with(jwt().jwt(j -> j.subject("outsider"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("items.length()").value(0));
    mvc.perform(get("/notifications").with(jwt().jwt(j -> j.subject("private-recipient"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("items[0].id").value(id.toString()));
  }

  @Test
  void paginationAndInvalidBounds() throws Exception {
    for (int i = 0; i < 3; i++) consumer.receive(event(UUID.randomUUID(), "paged"));
    mvc.perform(get("/notifications?size=2").with(jwt().jwt(j -> j.subject("paged"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("items.length()").value(2))
        .andExpect(jsonPath("hasNext").value(true));
    mvc.perform(get("/notifications?size=2&page=1").with(jwt().jwt(j -> j.subject("paged"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("items.length()").value(1))
        .andExpect(jsonPath("hasNext").value(false));
    for (String query : new String[] {"size=101", "size=0", "page=-1", "page=10001"})
      mvc.perform(get("/notifications?" + query).with(jwt())).andExpect(status().isBadRequest());
  }

  @Test
  void invalidEventDoesNotCreateNotification() throws Exception {
    int count = jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class);
    assertThrows(
        Exception.class,
        () ->
            consumer.receive(
                event(UUID.randomUUID(), "recipient")
                    .replace("audit.mission.assigned.v1", "unknown")));
    assertThrows(Exception.class, () -> consumer.receive("{}"));
    assertEquals(count, jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class));
  }

  @Test
  void databaseRollbackAllowsLaterRedelivery() throws Exception {
    String body = event(UUID.randomUUID(), "rollback-recipient");
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    tx.executeWithoutResult(
        status -> {
          try {
            consumer.receive(body);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          status.setRollbackOnly();
        });
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE recipient_subject='rollback-recipient'",
            Integer.class));
    consumer.receive(body);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE recipient_subject='rollback-recipient'",
            Integer.class));
  }

  @Test
  void readStateIsIdempotentSurvivesReplayAndCountsBeyondFirstPage() throws Exception {
    UUID id = UUID.randomUUID();
    String body = event(id, "read-owner");
    consumer.receive(body);
    consumer.receive(event(UUID.randomUUID(), "read-owner"));
    mvc.perform(get("/notifications/unread-count").with(jwt().jwt(j -> j.subject("read-owner"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("count").value(2));
    mvc.perform(get("/notifications?size=1").with(jwt().jwt(j -> j.subject("read-owner"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("items.length()").value(1));
    var first =
        mvc.perform(
                put("/notifications/" + id + "/read").with(jwt().jwt(j -> j.subject("read-owner"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var second =
        mvc.perform(
                put("/notifications/" + id + "/read").with(jwt().jwt(j -> j.subject("read-owner"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertEquals(mapper.readTree(first).get("readAt"), mapper.readTree(second).get("readAt"));
    consumer.receive(body);
    assertNotNull(
        jdbc.queryForObject(
            "SELECT read_at FROM notifications WHERE event_id=?", java.sql.Timestamp.class, id));
    mvc.perform(get("/notifications/unread-count").with(jwt().jwt(j -> j.subject("read-owner"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("count").value(1));
  }

  @Test
  void onlyRecipientCanReadOrCountTheirNotifications() throws Exception {
    UUID id = UUID.randomUUID();
    consumer.receive(event(id, "protected-reader"));
    mvc.perform(put("/notifications/" + id + "/read")).andExpect(status().isUnauthorized());
    mvc.perform(get("/notifications/unread-count")).andExpect(status().isUnauthorized());
    mvc.perform(
            put("/notifications/" + id + "/read").with(jwt().jwt(j -> j.subject("wrong-reader"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/notifications/" + UUID.randomUUID() + "/read")
                .with(jwt().jwt(j -> j.subject("wrong-reader"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/notifications/unread-count?recipientSubject=protected-reader")
                .with(jwt().jwt(j -> j.subject("wrong-reader"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("count").value(0));
    assertNull(
        jdbc.queryForObject(
            "SELECT read_at FROM notifications WHERE event_id=?", java.sql.Timestamp.class, id));
  }

  @Test
  void reviewNotificationKeepsItsTypeRecipientAndReadStateOnReplay() throws Exception {
    UUID id = UUID.randomUUID();
    String payload =
        event(id, "designated-validator")
            .replace("audit.mission.assigned.v1", "audit.mission.review-requested.v1");
    consumer.receive(payload);
    mvc.perform(get("/notifications").with(jwt().jwt(j -> j.subject("designated-validator"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].eventType").value("audit.mission.review-requested.v1"));
    mvc.perform(
            put("/notifications/" + id + "/read")
                .with(jwt().jwt(j -> j.subject("designated-validator"))))
        .andExpect(status().isOk());
    consumer.receive(payload);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE event_id=? AND read_at IS NOT NULL",
            Integer.class,
            id));
    mvc.perform(get("/notifications").with(jwt().jwt(j -> j.subject("other-reviewer"))))
        .andExpect(jsonPath("$.items.length()").value(0));
  }
}
