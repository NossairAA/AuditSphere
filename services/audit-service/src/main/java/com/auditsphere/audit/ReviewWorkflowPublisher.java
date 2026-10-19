package com.auditsphere.audit;

import com.auditsphere.audit.workflow.ReviewWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "temporal.enabled", havingValue = "true")
class ReviewWorkflowPublisher {
  private final JdbcTemplate jdbc;
  private final WorkflowServiceStubs service;
  private final WorkflowClient client;

  @org.springframework.beans.factory.annotation.Autowired
  ReviewWorkflowPublisher(
      JdbcTemplate jdbc,
      @Value("${temporal.target:localhost:7233}") String target,
      @Value("${temporal.namespace:default}") String namespace) {
    this.jdbc = jdbc;
    service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(target)
                .setRpcTimeout(Duration.ofSeconds(3))
                .setRpcRetryOptions(RpcRetryOptions.newBuilder().setMaximumAttempts(1).build())
                .setRpcLongPollTimeout(Duration.ofSeconds(5))
                .build());
    client =
        WorkflowClient.newInstance(
            service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
  }

  ReviewWorkflowPublisher(JdbcTemplate jdbc, WorkflowClient client) {
    this.jdbc = jdbc;
    this.client = client;
    this.service = null;
  }

  @jakarta.annotation.PreDestroy
  void close() {
    if (service != null) service.shutdownNow();
  }

  @Scheduled(fixedDelayString = "${temporal.outbox-delay-ms:500}")
  void publishNext() {
    UUID lease = UUID.randomUUID();
    var rows =
        jdbc.queryForList(
            """
      WITH candidate AS (
        SELECT event_id FROM review_workflow_outbox o
        WHERE published_at IS NULL AND available_at <= now()
          AND (lease_until IS NULL OR lease_until < now())
          AND (event_type='START' OR EXISTS (
            SELECT 1 FROM review_workflow_outbox s WHERE s.cycle_id=o.cycle_id AND s.event_type='START' AND s.published_at IS NOT NULL))
        ORDER BY occurred_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
      )
      UPDATE review_workflow_outbox o SET lease_token=?, lease_until=now()+interval '30 seconds', attempts=attempts+1
      FROM candidate c WHERE o.event_id=c.event_id RETURNING o.*
      """,
            lease);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    var id = row.get("event_id");
    String workflowId = "mission-review-" + row.get("cycle_id");
    try {
      if ("START".equals(row.get("event_type"))) {
        var stub =
            client.newUntypedWorkflowStub(
                "MissionReviewV1",
                WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(ReviewWorkflow.QUEUE)
                    .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                    .build());
        try {
          stub.start(row.get("mission_id").toString(), row.get("cycle_id").toString());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
          /* Retry after a lost acknowledgement. */
        }
      } else {
        var stub = client.newUntypedWorkflowStub(workflowId);
        try {
          stub.signal("decision", row.get("decision"));
        } catch (WorkflowNotFoundException completed) {
          // Signals cannot be delivered to a closed execution. Only acknowledge an identical
          // result.
          String result = stub.getResult(3, TimeUnit.SECONDS, String.class);
          if (!row.get("decision").equals(result)) throw completed;
        }
      }
      jdbc.update(
          "UPDATE review_workflow_outbox SET published_at=now(),lease_until=NULL,lease_token=NULL WHERE event_id=? AND lease_token=?",
          id,
          lease);
    } catch (Exception e) {
      jdbc.update(
          "UPDATE review_workflow_outbox SET lease_until=NULL,lease_token=NULL,available_at=now()+least(attempts*2,60)*interval '1 second' WHERE event_id=? AND lease_token=?",
          id,
          lease);
      LoggerFactory.getLogger(getClass())
          .warn("Review delivery deferred, event {} ({})", id, e.getClass().getSimpleName());
    }
  }
}
