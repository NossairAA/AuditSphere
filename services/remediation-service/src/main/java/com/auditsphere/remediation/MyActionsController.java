package com.auditsphere.remediation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Actions owned by the connected auditee. Withdrawn follow-ups are no longer listed. */
@RestController
@RequestMapping("/remediation/me/actions")
class MyActionsController {
  record MyAction(
      UUID id,
      UUID followUpId,
      UUID missionId,
      int findingReference,
      int reference,
      String description,
      String status,
      LocalDate dueDate,
      boolean overdue,
      long version) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final ZoneId zone;

  MyActionsController(
      JdbcTemplate jdbc, Clock clock, @Value("${remediation.time-zone}") String zone) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  /** Optional filters: one status, and overdue=true for open actions past their due date. */
  @GetMapping
  List<MyAction> mine(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean overdue,
      Authentication auth) {
    if (status != null && !status.matches("TODO|IN_PROGRESS|DONE|VERIFIED|CANCELLED"))
      throw Remediation.invalid();
    LocalDate today = LocalDate.now(clock.withZone(zone));
    var actions = jdbc.query(
        """
        SELECT a.id, a.follow_up_id, a.mission_id, f.reference AS finding_reference,
               a.reference, a.description, a.status, a.due_date, a.version
          FROM corrective_actions a
          JOIN finding_follow_ups f ON f.id = a.follow_up_id
          JOIN mission_scopes m ON m.mission_id = a.mission_id
         WHERE a.owner_subject = ? AND m.state <> 'WITHDRAWN' AND f.status <> 'WITHDRAWN'
           AND (CAST(? AS varchar) IS NULL OR a.status = ?)
         ORDER BY a.due_date, f.reference, a.reference
         LIMIT 500
        """,
        (rs, i) -> {
          String current = rs.getString("status");
          LocalDate due = rs.getObject("due_date", LocalDate.class);
          return new MyAction(
              rs.getObject("id", UUID.class),
              rs.getObject("follow_up_id", UUID.class),
              rs.getObject("mission_id", UUID.class),
              rs.getInt("finding_reference"),
              rs.getInt("reference"),
              rs.getString("description"),
              current,
              due,
              isOverdue(current, due, today),
              rs.getLong("version"));
        },
        auth.getName(),
        status,
        status);
    return overdue ? actions.stream().filter(MyAction::overdue).toList() : actions;
  }

  /** Overdue is derived on read: only open work past its date counts, never a stored status. */
  static boolean isOverdue(String status, LocalDate due, LocalDate today) {
    return ("TODO".equals(status) || "IN_PROGRESS".equals(status)) && due.isBefore(today);
  }
}
