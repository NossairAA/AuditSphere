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
}
