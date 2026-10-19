package com.auditsphere.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
class ReviewOutbox {
  private final JdbcTemplate jdbc;

  ReviewOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(Mission mission, String type, String decision) {
    if (mission.reviewCycleId == null) throw new IllegalStateException("Missing review cycle");
    jdbc.update(
        "INSERT INTO review_workflow_outbox(event_id,cycle_id,mission_id,event_type,decision) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        mission.reviewCycleId,
        mission.id,
        type,
        decision);
  }
}
