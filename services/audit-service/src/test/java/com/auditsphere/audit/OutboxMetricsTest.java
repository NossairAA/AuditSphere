package com.auditsphere.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxMetricsTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void registersPendingAndAgeForEachOutbox() {
    new OutboxMetrics(jdbc).bindTo(registry);
    for (String outbox : OutboxMetrics.OUTBOXES) {
      assertNotNull(registry.find("auditsphere.outbox.pending").tag("outbox", outbox).gauge());
      assertNotNull(registry.find("auditsphere.outbox.oldest.age").tag("outbox", outbox).gauge());
    }
  }

  @Test
  void readsUnpublishedEventsOnly() {
    when(jdbc.queryForObject(contains("count(*) FROM review_workflow_outbox"), eq(Number.class)))
        .thenReturn(3L);
    when(jdbc.queryForObject(
            argThat((String sql) -> sql.contains("min(occurred_at)") && sql.contains("review_")),
            eq(Number.class)))
        .thenReturn(42.5);
    new OutboxMetrics(jdbc).bindTo(registry);

    String outbox = "review_workflow_outbox";
    var pending = registry.find("auditsphere.outbox.pending").tag("outbox", outbox);
    var age = registry.find("auditsphere.outbox.oldest.age").tag("outbox", outbox);
    assertEquals(3.0, pending.gauge().value());
    assertEquals(42.5, age.gauge().value());
    verify(jdbc, atLeastOnce())
        .queryForObject(
            argThat(
                (String sql) ->
                    sql.contains("review_workflow_outbox")
                        && sql.endsWith("WHERE published_at IS NULL")),
            eq(Number.class));
  }

  @Test
  void failedReadIsAGapNotAZero() {
    when(jdbc.queryForObject(anyString(), eq(Number.class)))
        .thenThrow(new DataAccessResourceFailureException("down"));
    var metrics = new OutboxMetrics(jdbc);
    assertTrue(Double.isNaN(metrics.pending("assignment_outbox")));
    assertTrue(Double.isNaN(metrics.oldestAge("assignment_outbox")));
  }
}
