package com.auditsphere.audit.workflow;

import io.temporal.workflow.Workflow;

public class ReviewWorkflowImpl implements ReviewWorkflow {
  private String decision;

  @Override
  public String awaitDecision(String missionId, String cycleId) {
    Workflow.await(() -> decision != null);
    return decision;
  }

  @Override
  public void decide(String value) {
    // First valid decision wins. The authenticated API is the sole business authority.
    if (decision == null && ("APPROVED".equals(value) || "REJECTED".equals(value)))
      decision = value;
  }
}
