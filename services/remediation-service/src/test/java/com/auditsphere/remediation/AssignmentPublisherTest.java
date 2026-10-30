package com.auditsphere.remediation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** The publisher is built by hand: no scheduled run interferes with the assertions. */
@SpringBootTest(
    properties = {"spring.flyway.enabled=true", "spring.kafka.listener.auto-startup=false"})
@Testcontainers
class AssignmentPublisherTest {
  @Container @ServiceConnection
  static PostgreSQLContainer db =
      new PostgreSQLContainer(
          "postgres@sha256:2a0d0fe14825b0939f78a8cad5cd4e6aa68bf94d0e5dd96e24b6d23af4315545");

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  UUID mission, followUp, action, event;

  @BeforeEach
  void pendingAssignment() {
    jdbc.update("UPDATE remediation_outbox SET published_at=now() WHERE published_at IS NULL");
    mission = UUID.randomUUID();
    followUp = UUID.randomUUID();
    action = UUID.randomUUID();
    event = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission_scopes(mission_id,title,owner_subject,validator_subject,issue_cycle,state) VALUES (?,'Mission','owner','validator',1,'ACTIVE')",
        mission);
    jdbc.update(
        "INSERT INTO finding_follow_ups(id,mission_id,reference,finding_version,title,severity,condition_text,cause,recommendation,status) VALUES (?,?,1,1,'Constat confidentiel','HIGH','Condition','Cause','Recommandation','ACTION_PLAN')",
        followUp, mission);
    jdbc.update(
        "INSERT INTO corrective_actions(id,follow_up_id,mission_id,reference,description,owner_subject,due_date,status,created_by,updated_by) VALUES (?,?,?,1,'Texte confidentiel','auditee',DATE '2030-01-01','TODO','owner','owner')",
        action, followUp, mission);
    jdbc.update(
        "INSERT INTO remediation_outbox(event_id,event_type,action_id,follow_up_id,mission_id,recipient_subject,aggregate_version) VALUES (?,'audit.action.assigned.v1',?,?,?,'auditee',3)",
        event, action, followUp, mission);
  }

  @SuppressWarnings("unchecked")
  @Test
  void publishesIdentifiersOnlyKeyedByActionThenMarksPublished() throws Exception {
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    new AssignmentPublisher(jdbc, kafka, mapper).publishNext();

    var payload = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(kafka).send(eq("audit.action.assigned.v1"), eq(action.toString()), payload.capture());
    Map<String, Object> message = mapper.readValue(payload.getValue(), Map.class);
    assertEquals(event.toString(), message.get("eventId"));
    assertEquals(action.toString(), message.get("actionId"));
    assertEquals(followUp.toString(), message.get("followUpId"));
    assertEquals("auditee", message.get("recipientSubject"));
    assertEquals(3, ((Number) message.get("aggregateVersion")).intValue());
    assertFalse(payload.getValue().contains("confidentiel"));
    // Exactly the fields of the published contract.
    Map<String, Object> schema =
        mapper.readValue(
            Files.readString(Path.of("../../contracts/action-assigned-v1.schema.json")), Map.class);
    assertEquals(((Map<String, Object>) schema.get("properties")).keySet(), message.keySet());
    assertEquals(Set.copyOf((List<String>) schema.get("required")), message.keySet());
    assertNotNull(
        jdbc.queryForObject("SELECT published_at FROM remediation_outbox WHERE event_id=?", Object.class, event));

    clearInvocations(kafka);
    new AssignmentPublisher(jdbc, kafka, mapper).publishNext();
    verify(kafka, never()).send(anyString(), anyString(), anyString());
  }

  @SuppressWarnings("unchecked")
  @Test
  void brokerFailureReleasesTheLeaseAndDefersTheEvent() {
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    new AssignmentPublisher(jdbc, kafka, mapper).publishNext();
    var row =
        jdbc.queryForMap(
            "SELECT published_at, lease_token, attempts, available_at > now() AS deferred FROM remediation_outbox WHERE event_id=?",
            event);
    assertNull(row.get("published_at"));
    assertNull(row.get("lease_token"));
    assertEquals(1, row.get("attempts"));
    assertEquals(true, row.get("deferred"));
  }
}
