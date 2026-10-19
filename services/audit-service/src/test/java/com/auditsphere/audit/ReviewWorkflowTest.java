package com.auditsphere.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.auditsphere.audit.workflow.*;
import io.temporal.client.*;
import io.temporal.testing.*;
import org.junit.jupiter.api.Test;

class ReviewWorkflowTest {
  @Test
  void decisionSurvivesHistoryReplayAndDuplicateSignals() throws Exception {
    try (var env = TestWorkflowEnvironment.newInstance()) {
      env.newWorker(ReviewWorkflow.QUEUE)
          .registerWorkflowImplementationTypes(ReviewWorkflowImpl.class);
      var workflow =
          env.getWorkflowClient()
              .newWorkflowStub(
                  ReviewWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("review-test")
                      .setTaskQueue(ReviewWorkflow.QUEUE)
                      .build());
      WorkflowClient.start(workflow::awaitDecision, "mission", "cycle");
      workflow.decide("APPROVED");
      workflow.decide("APPROVED");
      env.start();
      assertEquals("APPROVED", WorkflowStub.fromTyped(workflow).getResult(String.class));
      WorkflowReplayer.replayWorkflowExecution(
          env.getWorkflowClient().fetchHistory("review-test"), ReviewWorkflowImpl.class);
    }
  }
}
