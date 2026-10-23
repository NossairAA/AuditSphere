package com.auditsphere.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class NotificationStreamLimitsTest {
  @Test
  void fourthStreamForSameUserIsRejectedButOtherUserCanSubscribe() {
    var stream = new NotificationStream();
    try {
      for (int i = 0; i < 3; i++) stream.subscribe("one", Instant.now().plusSeconds(60));
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
          () -> stream.subscribe("one", Instant.now().plusSeconds(60))).getStatusCode());
      assertNotNull(stream.subscribe("two", Instant.now().plusSeconds(60)));
    } finally { stream.close(); }
  }
  @Test
  void globalLimitIsConfigurable() {
    var stream = new NotificationStream();
    org.springframework.test.util.ReflectionTestUtils.setField(stream, "maxConnections", 2);
    try {
      stream.subscribe("one", Instant.now().plusSeconds(60));
      stream.subscribe("two", Instant.now().plusSeconds(60));
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
          () -> stream.subscribe("three", Instant.now().plusSeconds(60))).getStatusCode());
    } finally { stream.close(); }
  }
}
