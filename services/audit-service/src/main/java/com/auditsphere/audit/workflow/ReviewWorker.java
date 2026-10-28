package com.auditsphere.audit.workflow;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

public final class ReviewWorker {
  static final Duration FIRST_DELAY = Duration.ofSeconds(1);
  static final Duration MAX_DELAY = Duration.ofSeconds(30);
  static final Duration STARTUP_LIMIT = Duration.ofMinutes(5);

  interface Sleeper {
    void sleep(Duration delay) throws InterruptedException;
  }

  private ReviewWorker() {}

  public static void run() {
    var namespace = System.getenv().getOrDefault("TEMPORAL_NAMESPACE", "default");
    var service =
        WorkflowServiceStubs.newServiceStubs(
            TemporalConnection.options(
                    System.getenv().getOrDefault("TEMPORAL_TARGET", "temporal:7233"), System.getenv())
                .build());
    var client =
        WorkflowClient.newInstance(
            service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
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
    try {
      // After a host reboot the worker can start before Temporal: wait instead of crash looping.
      awaitTemporal(
          () ->
              service
                  .blockingStub()
                  .withDeadlineAfter(5, TimeUnit.SECONDS)
                  .describeNamespace(
                      DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build()),
          STARTUP_LIMIT,
          delay -> Thread.sleep(delay.toMillis()));
      factory.start();
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      factory.shutdownNow();
      service.shutdownNow();
    }
  }

  /**
   * Calls the probe until it succeeds, with a doubling delay capped at MAX_DELAY. Only an
   * unreachable server is retried. Past the limit, the last failure is thrown.
   */
  static void awaitTemporal(Runnable probe, Duration limit, Sleeper sleeper)
      throws InterruptedException {
    var delay = FIRST_DELAY;
    var waited = Duration.ZERO;
    while (true) {
      try {
        probe.run();
        return;
      } catch (StatusRuntimeException e) {
        var code = e.getStatus().getCode();
        boolean transientFailure =
            code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        if (!transientFailure || waited.plus(delay).compareTo(limit) > 0) throw e;
        LoggerFactory.getLogger(ReviewWorker.class)
            .warn("Temporal not ready ({}), retrying in {} s", code, delay.toSeconds());
        sleeper.sleep(delay);
        waited = waited.plus(delay);
        delay = delay.multipliedBy(2).compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay.multipliedBy(2);
      }
    }
  }
}
