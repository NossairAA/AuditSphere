package com.auditsphere.audit.workflow;

import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.util.concurrent.CountDownLatch;

public final class ReviewWorker {
  private ReviewWorker() {}

  public static void run() {
    var service =
        WorkflowServiceStubs.newServiceStubs(
            TemporalConnection.options(
                    System.getenv().getOrDefault("TEMPORAL_TARGET", "temporal:7233"), System.getenv())
                .build());
    var client =
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder()
                .setNamespace(System.getenv().getOrDefault("TEMPORAL_NAMESPACE", "default"))
                .build());
    var factory = WorkerFactory.newInstance(client);
    factory
        .newWorker(ReviewWorkflow.QUEUE)
        .registerWorkflowImplementationTypes(ReviewWorkflowImpl.class);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  factory.shutdownNow();
                  service.shutdownNow();
                }));
    factory.start();
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      factory.shutdownNow();
      service.shutdownNow();
    }
  }
}
