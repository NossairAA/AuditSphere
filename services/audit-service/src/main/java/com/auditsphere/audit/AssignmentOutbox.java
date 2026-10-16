package com.auditsphere.audit;

import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class AssignmentOutbox {
  private final JdbcTemplate jdbc;

  AssignmentOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(Mission mission, Set<String> recipients) {
    for (String recipient : recipients) {
      jdbc.update(
          "INSERT INTO assignment_outbox(event_id, mission_id, recipient_subject, aggregate_version) VALUES (?, ?, ?, ?)",
          UUID.randomUUID(),
          mission.id,
          recipient,
          mission.version);
    }
  }
}
