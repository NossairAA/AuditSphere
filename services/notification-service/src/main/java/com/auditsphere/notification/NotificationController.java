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
  private final NotificationStream stream;

  NotificationController(JdbcTemplate jdbc, NotificationStream stream) {
    this.jdbc = jdbc;
    this.stream = stream;
  }

  @GetMapping(value = "/stream", produces = "text/event-stream")
  org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(
      @AuthenticationPrincipal Jwt jwt) {
    if (jwt.getExpiresAt() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return stream.subscribe(jwt.getSubject(), jwt.getExpiresAt());
  }

  record Notification(
      UUID id,
      UUID missionId,
      Instant occurredAt,
      Instant receivedAt,
      Instant readAt,
      String eventType) {}

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
        SELECT event_id, mission_id, occurred_at, received_at, read_at, event_type FROM notifications
        WHERE recipient_subject=? ORDER BY received_at DESC, event_id DESC LIMIT ? OFFSET ?
        """,
            (r, n) ->
                new Notification(
                    r.getObject("event_id", UUID.class),
                    r.getObject("mission_id", UUID.class),
                    r.getTimestamp("occurred_at").toInstant(),
                    r.getTimestamp("received_at").toInstant(),
                    r.getTimestamp("read_at") == null
                        ? null
                        : r.getTimestamp("read_at").toInstant(),
                    r.getString("event_type")),
            jwt.getSubject(),
            size + 1,
            (long) page * size);
    return new Page(rows.stream().limit(size).toList(), page, size, rows.size() > size);
  }

  record UnreadCount(long count) {}

  record ReadState(UUID id, Instant readAt) {}

  @GetMapping("/unread-count")
  UnreadCount unread(@AuthenticationPrincipal Jwt jwt) {
    return new UnreadCount(
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE recipient_subject=? AND read_at IS NULL",
            Long.class,
            jwt.getSubject()));
  }

  @PutMapping("/{id}/read")
  ReadState markRead(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    var rows =
        jdbc.query(
            "UPDATE notifications SET read_at=COALESCE(read_at, now()) WHERE event_id=? AND recipient_subject=? RETURNING read_at",
            (r, n) -> new ReadState(id, r.getTimestamp("read_at").toInstant()),
            id,
            jwt.getSubject());
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    stream.changed(jwt.getSubject());
    return rows.getFirst();
  }
}
