package com.auditsphere.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.auditsphere.audit.workflow.*;
import io.temporal.client.*;
import io.temporal.testing.*;

class ReviewWorkflowTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"APPROVED", "REJECTED", "WITHDRAWN"})
  void decisionSurvivesHistoryReplayAndDuplicateSignals(String decision) throws Exception {
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
      workflow.decide(decision);
      workflow.decide(decision);
      env.start();
      assertEquals(decision, WorkflowStub.fromTyped(workflow).getResult(String.class));
      WorkflowReplayer.replayWorkflowExecution(
          env.getWorkflowClient().fetchHistory("review-test"), ReviewWorkflowImpl.class);
    }
  }
}
