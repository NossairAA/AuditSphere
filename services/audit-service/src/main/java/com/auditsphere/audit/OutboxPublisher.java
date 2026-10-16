package com.auditsphere.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.enabled", havingValue = "true")
class OutboxPublisher {
  static final String TOPIC = "audit.mission.assigned.v1";
  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;

  OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.mapper = mapper;
  }

  // The atomic claim commits before Kafka I/O. A crash releases the lease after 30 seconds.
  @Scheduled(fixedDelayString = "${outbox.delay-ms:500}")
  void publishNext() {
    var lease = UUID.randomUUID();
    var rows =
        jdbc.queryForList(
            """
        WITH candidate AS (
          SELECT event_id FROM assignment_outbox
          WHERE published_at IS NULL AND available_at <= now()
            AND (lease_until IS NULL OR lease_until < now())
          ORDER BY occurred_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
        )
        UPDATE assignment_outbox o SET lease_token = ?, lease_until = now() + interval '30 seconds', attempts = attempts + 1
        FROM candidate c WHERE o.event_id = c.event_id
        RETURNING o.event_id, o.mission_id, o.recipient_subject, o.aggregate_version, o.occurred_at
        """,
            lease);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    var eventId = row.get("event_id");
    try {
      var event = new java.util.LinkedHashMap<String, Object>();
      event.put("eventId", eventId.toString());
      event.put("eventType", TOPIC);
      event.put("eventVersion", 1);
      event.put("missionId", row.get("mission_id").toString());
      event.put("recipientSubject", row.get("recipient_subject"));
      event.put("aggregateVersion", row.get("aggregate_version"));
      event.put("occurredAt", ((java.sql.Timestamp) row.get("occurred_at")).toInstant().toString());
      kafka
          .send(TOPIC, row.get("mission_id").toString(), mapper.writeValueAsString(event))
          .get(5, TimeUnit.SECONDS);
      jdbc.update(
          "UPDATE assignment_outbox SET published_at=now(), lease_until=NULL, lease_token=NULL WHERE event_id=? AND lease_token=?",
          eventId,
          lease);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      jdbc.update(
          "UPDATE assignment_outbox SET lease_until=NULL, lease_token=NULL, available_at=now() + least(attempts * 2, 60) * interval '1 second' WHERE event_id=? AND lease_token=?",
          eventId,
          lease);
      LoggerFactory.getLogger(getClass())
          .warn(
              "Outbox publication deferred, event {} ({})", eventId, e.getClass().getSimpleName());
    }
  }
}
