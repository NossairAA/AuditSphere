package com.auditsphere.gateway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RequestLimitsTest {
  @Test
  void rejectsChunkedOversizeBodyBeforeUpstream() {
    var filter = new RequestLimits();
    var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/missions").body("x".repeat(32769)));
    filter
        .filter(
            exchange,
            e -> {
              fail("Must not forward oversized body");
              return Mono.empty();
            })
        .block();
    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
  }

  @Test
  void rateLimitReturns429() {
    var filter = new RequestLimits();
    for (int i = 0; i < 241; i++) {
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/missions"));
      filter.filter(exchange, e -> Mono.empty()).block();
      if (i == 240) {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("60", exchange.getResponse().getHeaders().getFirst("Retry-After"));
      }
    }
  }

  @Test
  void forwardsSmallBodyExactly() {
    var filter = new RequestLimits();
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/missions").body("{\"title\":\"OK\"}"));
    filter
        .filter(
            exchange,
            e ->
                org.springframework.core.io.buffer.DataBufferUtils.join(e.getRequest().getBody())
                    .doOnNext(
                        b -> {
                          assertEquals(
                              "{\"title\":\"OK\"}",
                              b.toString(java.nio.charset.StandardCharsets.UTF_8));
                          org.springframework.core.io.buffer.DataBufferUtils.release(b);
                        })
                    .then())
        .block();
  }

  @Test
  void evidenceHasBoundedDedicatedUploadLimit() {
    var filter = new RequestLimits();
    var path =
        "/missions/00000000-0000-0000-0000-000000000001/controls/00000000-0000-0000-0000-000000000002/evidence";
    var allowed =
        MockServerWebExchange.from(MockServerHttpRequest.post(path).body("x".repeat(40000)));
    var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
    filter
        .filter(
            allowed,
            e -> {
              forwarded.set(true);
              return Mono.empty();
            })
        .block();
    assertTrue(forwarded.get());
    var rejected =
        MockServerWebExchange.from(
            MockServerHttpRequest.post(path)
                .header("Content-Length", String.valueOf(12 * 1024 * 1024)));
    filter
        .filter(
            rejected,
            e -> {
              fail("oversized upload");
              return Mono.empty();
            })
        .block();
    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, rejected.getResponse().getStatusCode());
  }
  @Test
  void concurrentUploadsAreBoundedAndCancellationReleasesSlot() {
    var filter = new RequestLimits();
    var path = "/missions/00000000-0000-0000-0000-000000000001/controls/00000000-0000-0000-0000-000000000002/evidence";
    var first = filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post(path).body("a")), e -> Mono.never()).subscribe();
    var second = filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post(path).body("b")), e -> Mono.never()).subscribe();
    try {
      var third = MockServerWebExchange.from(MockServerHttpRequest.post(path).body("c"));
      filter.filter(third, e -> Mono.empty()).block();
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
      first.dispose();
      var next = MockServerWebExchange.from(MockServerHttpRequest.post(path).body("d"));
      filter.filter(next, e -> Mono.empty()).block();
      assertNull(next.getResponse().getStatusCode());
    } finally { first.dispose(); second.dispose(); }
  }

  @Test
  void configuredBffIpLimitKeepsDifferentUsersIndependent() {
    var filter = new RequestLimits();
    org.springframework.test.util.ReflectionTestUtils.setField(filter, "ipLimit", 1200);
    for (int i = 0; i < 601; i++) {
      final String subject = "user-" + i;
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/missions"))
          .mutate().principal(Mono.just((java.security.Principal) () -> subject)).build();
      filter.filter(exchange, e -> Mono.empty()).block();
      assertNull(exchange.getResponse().getStatusCode());
    }
  }
}
