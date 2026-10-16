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
  @Container @ServiceConnection
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17.9");

  @Autowired AssignmentConsumer consumer;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

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
}
