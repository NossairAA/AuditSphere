package com.auditsphere.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/notifications")
class NotificationController {
  private final JdbcTemplate jdbc;

  NotificationController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record Notification(UUID id, UUID missionId, Instant occurredAt, Instant receivedAt) {}

  record Page(List<Notification> items, int page, int size, boolean hasNext) {}

  @GetMapping
  Page list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var rows =
        jdbc.query(
            """
        SELECT event_id, mission_id, occurred_at, received_at FROM notifications
        WHERE recipient_subject=? ORDER BY received_at DESC, event_id DESC LIMIT ? OFFSET ?
        """,
            (r, n) ->
                new Notification(
                    r.getObject("event_id", UUID.class),
                    r.getObject("mission_id", UUID.class),
                    r.getTimestamp("occurred_at").toInstant(),
                    r.getTimestamp("received_at").toInstant()),
            jwt.getSubject(),
            size + 1,
            (long) page * size);
    return new Page(rows.stream().limit(size).toList(), page, size, rows.size() > size);
  }
}
