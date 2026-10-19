package com.auditsphere.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
class MissionActionLog {
  private final JdbcTemplate jdbc;

  MissionActionLog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(Mission mission, String actor, String action, String reason) {
    jdbc.update(
        "INSERT INTO"
            + " mission_action_log(id,mission_id,actor_subject,action,reason,mission_version,review_cycle_id)"
            + " VALUES (?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        mission.id,
        actor,
        action,
        reason,
        mission.version,
        mission.reviewCycleId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void removeDraftEvents(UUID mission) {
    jdbc.update("DELETE FROM assignment_outbox WHERE mission_id=?", mission);
  }
}
