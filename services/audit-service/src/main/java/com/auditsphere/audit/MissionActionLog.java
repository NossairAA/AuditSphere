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
  public UUID admin(
      Mission mission,
      String actor,
      String action,
      String operation,
      String reason,
      String before,
      String after) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " mission_action_log(id,mission_id,actor_subject,action,operation,reason,mission_version,review_cycle_id,previous_status,resulting_status)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        mission.id,
        actor,
        action,
        operation,
        reason,
        mission.version,
        mission.reviewCycleId,
        before,
        after);
    return id;
  }

  public java.util.List<java.util.Map<String, Object>> list(UUID mission) {
    return jdbc.queryForList(
        "SELECT"
            + " actor_subject,action,operation,reason,mission_version,previous_status,resulting_status,occurred_at"
            + " FROM mission_action_log WHERE mission_id=? ORDER BY occurred_at DESC,id LIMIT 100",
        mission);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void removeDraftEvents(UUID mission) {
    jdbc.update("DELETE FROM assignment_outbox WHERE mission_id=?", mission);
  }

  // mission_control_risks has no cascade towards mission_risks: delete links before risks.
  @Transactional(propagation = Propagation.MANDATORY)
  public void removeDraftProgram(UUID mission) {
    jdbc.update("DELETE FROM mission_control_risks WHERE mission_id=?", mission);
    jdbc.update("DELETE FROM mission_controls WHERE mission_id=?", mission);
    jdbc.update("DELETE FROM mission_risks WHERE mission_id=?", mission);
  }
}
