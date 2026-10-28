package com.auditsphere.audit.workflow;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReviewWorkerTest {
  private final List<Duration> sleeps = new ArrayList<>();

  @Test
  void retriesUnavailableWithCappedBackoffUntilReady() throws Exception {
    var calls = new AtomicInteger();
    ReviewWorker.awaitTemporal(
        () -> {
          if (calls.incrementAndGet() <= 7) throw Status.UNAVAILABLE.asRuntimeException();
        },
        Duration.ofMinutes(5),
        sleeps::add);

    assertEquals(8, calls.get());
    assertEquals(
        List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L),
        sleeps.stream().map(Duration::toSeconds).toList());
  }

  @Test
  void throwsLastFailureOnceLimitIsReached() {
    var error =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                ReviewWorker.awaitTemporal(
                    () -> {
                      throw Status.DEADLINE_EXCEEDED.asRuntimeException();
                    },
                    Duration.ofSeconds(10),
                    sleeps::add));

    assertEquals(Status.Code.DEADLINE_EXCEEDED, error.getStatus().getCode());
    assertEquals(List.of(1L, 2L, 4L), sleeps.stream().map(Duration::toSeconds).toList());
  }

  @Test
  void doesNotRetryPermanentErrors() {
    assertThrows(
        StatusRuntimeException.class,
        () ->
            ReviewWorker.awaitTemporal(
                () -> {
                  throw Status.PERMISSION_DENIED.asRuntimeException();
                },
                Duration.ofMinutes(5),
                sleeps::add));

    assertTrue(sleeps.isEmpty());
  }
}
