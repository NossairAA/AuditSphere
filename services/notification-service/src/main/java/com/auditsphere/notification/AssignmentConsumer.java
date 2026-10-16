package com.auditsphere.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AssignmentConsumer {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  AssignmentConsumer(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  // RECORD acknowledgment happens after this database transaction has committed.
  @Transactional
  @KafkaListener(topics = "audit.mission.assigned.v1", groupId = "notification-service-v1")
  public void receive(String payload) throws Exception {
    if (payload.length() > 32768) throw new IllegalArgumentException("Event too large");
    var event = mapper.readValue(payload, AssignmentEvent.class);
    event.validate();
    jdbc.update(
        """
        INSERT INTO notifications(event_id, mission_id, recipient_subject, aggregate_version, occurred_at)
        VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
        """,
        event.eventId(),
        event.missionId(),
        event.recipientSubject(),
        event.aggregateVersion(),
        java.sql.Timestamp.from(event.occurredAt()));
  }
}
