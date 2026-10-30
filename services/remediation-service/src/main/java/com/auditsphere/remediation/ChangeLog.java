package com.auditsphere.remediation;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Append-only trace in remediation_change_log, written in the caller's transaction. */
@Component
class ChangeLog {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  ChangeLog(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  void write(
      UUID mission,
      UUID followUp,
      UUID action,
      String actor,
      String kind,
      long version,
      String reason,
      boolean override,
      Map<String, ?> previous,
      Map<String, ?> resulting) {
    jdbc.update(
        """
        INSERT INTO remediation_change_log(id, mission_id, follow_up_id, action_id, actor_subject, action,
          entity_version, reason, override, previous_data, resulting_data)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
        """,
        UUID.randomUUID(),
        mission,
        followUp,
        action,
        actor,
        kind,
        version,
        reason,
        override,
        previous == null ? null : mapper.writeValueAsString(previous),
        resulting == null ? null : mapper.writeValueAsString(resulting));
  }
}
