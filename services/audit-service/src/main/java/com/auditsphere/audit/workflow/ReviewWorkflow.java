package com.auditsphere.audit.workflow;

import io.temporal.workflow.*;

@WorkflowInterface
public interface ReviewWorkflow {
  String QUEUE = "audit-review-v1";

  @WorkflowMethod(name = "MissionReviewV1")
  String awaitDecision(String missionId, String cycleId);

  @SignalMethod(name = "decision")
  void decide(String decision);
}
