package com.auditsphere.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Seule lecture des constats depuis le cycle de mission, documentée par ADR-0021.
@Component
class PendingFindings {
  private final JdbcTemplate jdbc;

  PendingFindings(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  static class Blocked extends RuntimeException {
    final int pending;

    Blocked(int pending) {
      super(null, null, false, false);
      this.pending = pending;
    }
  }

  // Appelé sous verrou de mission : les écritures de constats prennent le même verrou.
  void requireNone(UUID missionId) {
    Integer pending =
        jdbc.queryForObject(
            "SELECT count(*) FROM mission_findings WHERE mission_id=? AND status IN ('DRAFT','SUBMITTED')",
            Integer.class,
            missionId);
    if (pending != null && pending > 0) throw new Blocked(pending);
  }
}
