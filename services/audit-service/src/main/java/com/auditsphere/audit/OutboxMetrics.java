package com.auditsphere.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Backlog of each outbox, read at scrape time. A growing age means events no longer reach Kafka
 * or Temporal, so notifications or review workflows are stuck.
 */
@Component
class OutboxMetrics implements MeterBinder {
  static final List<String> OUTBOXES = List.of("assignment_outbox", "review_workflow_outbox");
  private final JdbcTemplate jdbc;

  OutboxMetrics(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (String outbox : OUTBOXES) {
      Gauge.builder("auditsphere.outbox.pending", () -> pending(outbox))
          .description("Events not yet published")
          .tag("outbox", outbox)
          .register(registry);
      Gauge.builder("auditsphere.outbox.oldest.age", () -> oldestAge(outbox))
          .description("Age of the oldest unpublished event, 0 when empty")
          .baseUnit("seconds")
          .tag("outbox", outbox)
          .register(registry);
    }
  }

  // Table names come from the constant list above, never from input.
  double pending(String outbox) {
    return query("SELECT count(*) FROM " + outbox + " WHERE published_at IS NULL");
  }

  double oldestAge(String outbox) {
    return query(
        "SELECT coalesce(extract(epoch FROM now() - min(occurred_at)), 0) FROM "
            + outbox
            + " WHERE published_at IS NULL");
  }

  // A failed read gives NaN: Prometheus then shows a gap instead of a false zero.
  private double query(String sql) {
    try {
      Number value = jdbc.queryForObject(sql, Number.class);
      return value == null ? Double.NaN : value.doubleValue();
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }
}
