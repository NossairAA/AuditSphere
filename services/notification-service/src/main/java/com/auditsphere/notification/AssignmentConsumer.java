package com.auditsphere.notification;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AssignmentConsumer {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final NotificationStream stream;

  AssignmentConsumer(JdbcTemplate jdbc, ObjectMapper mapper, NotificationStream stream) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.stream = stream;
  }

  // RECORD acknowledgment happens after this database transaction has committed.
  @Transactional
  @KafkaListener(
      topics = {"audit.mission.assigned.v1", "audit.mission.review-requested.v1"},
      groupId = "notification-service-v1")
  public void receive(String payload) throws Exception {
    if (payload == null || payload.length() > 32768) throw new IllegalArgumentException("Event too large");
    var event = mapper.readValue(payload, AssignmentEvent.class);
    if (event == null) throw new IllegalArgumentException("Null assignment event");
    event.validate();
    int inserted =
        jdbc.update(
            """
        INSERT INTO notifications(event_id, mission_id, recipient_subject, aggregate_version, occurred_at, event_type)
        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
        """,
            event.eventId(),
            event.missionId(),
            event.recipientSubject(),
            event.aggregateVersion(),
            java.sql.Timestamp.from(event.occurredAt()),
            event.eventType());
    if (inserted > 0) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  stream.changed(event.recipientSubject());
                }
              });
    }
  }
}
