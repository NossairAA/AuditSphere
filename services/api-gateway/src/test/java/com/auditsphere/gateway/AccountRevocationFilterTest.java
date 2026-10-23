package com.auditsphere.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchangeDecorator;
import reactor.core.publisher.Mono;

class AccountRevocationFilterTest {
  @Test
  void revokedTokenCannotReachUpstream() throws Exception {
    HttpServer audit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    audit.createContext("/account/security", request -> {
      assertEquals("Bearer token", request.getRequestHeaders().getFirst("Authorization"));
      request.sendResponseHeaders(401, -1);
      request.close();
    });
    audit.start();
    try {
      var filter = new AccountRevocationFilter("http://127.0.0.1:" + audit.getAddress().getPort());
      var exchange = authenticatedExchange();
      var forwarded = new AtomicBoolean();
      filter.filter(exchange, downstream -> {
        forwarded.set(true);
        return Mono.empty();
      }).block(Duration.ofSeconds(4));
      assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
      assertFalse(forwarded.get());
    } finally {
      audit.stop(0);
    }
  }

  @Test
  void successfulCheckDoesNotTimeOutDownstreamResponse() throws Exception {
    HttpServer audit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    audit.createContext("/account/security", request -> {
      request.sendResponseHeaders(200, -1);
      request.close();
    });
    audit.start();
    try {
      var filter = new AccountRevocationFilter("http://127.0.0.1:" + audit.getAddress().getPort());
      var forwarded = new AtomicBoolean();
      var exchange = authenticatedExchange();
      filter.filter(exchange, downstream -> Mono.delay(Duration.ofMillis(2200))
          .doOnNext(value -> forwarded.set(true)).then()).block(Duration.ofSeconds(4));
      assertTrue(forwarded.get());
      assertNull(exchange.getResponse().getStatusCode());
    } finally {
      audit.stop(0);
    }
  }

  @Test
  void rateLimitsProtectRevocationLedgerForValidAndRevokedTokens() throws Exception {
    for (int ledgerStatus : new int[] {200, 401}) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      HttpServer audit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      audit.createContext("/account/security", request -> {
        calls.incrementAndGet();
        request.sendResponseHeaders(ledgerStatus, -1);
        request.close();
      });
      audit.start();
      try {
        var revocations = new AccountRevocationFilter("http://127.0.0.1:" + audit.getAddress().getPort());
        var limits = new RequestLimits();
        var filters = new java.util.ArrayList<org.springframework.cloud.gateway.filter.GlobalFilter>(
            java.util.List.of(revocations, limits));
        filters.sort(java.util.Comparator.comparingInt(filter -> ((org.springframework.core.Ordered) filter).getOrder()));
        var forwarded = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.cloud.gateway.filter.GatewayFilterChain chain = downstream -> {
          forwarded.incrementAndGet();
          return Mono.empty();
        };
        for (int index = filters.size() - 1; index >= 0; index--) {
          var next = chain;
          var filter = filters.get(index);
          chain = exchange -> filter.filter(exchange, next);
        }
        for (int request = 0; request < 245; request++) {
          var exchange = authenticatedExchange();
          chain.filter(exchange).block(Duration.ofSeconds(4));
          assertEquals(request >= 240 ? HttpStatus.TOO_MANY_REQUESTS
              : ledgerStatus == 401 ? HttpStatus.UNAUTHORIZED : null, exchange.getResponse().getStatusCode());
        }
        assertEquals(240, calls.get());
        assertEquals(ledgerStatus == 200 ? 240 : 0, forwarded.get());
      } finally {
        audit.stop(0);
      }
    }
  }

  private ServerWebExchangeDecorator authenticatedExchange() {
    var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user")
        .claim("realm_access", Map.of("roles", java.util.List.of("AUDITOR"))).build();
    var delegate = MockServerWebExchange.from(MockServerHttpRequest.get("/notifications").build());
    return new ServerWebExchangeDecorator(delegate) {
      @Override public Mono<Principal> getPrincipal() {
        return Mono.just(new JwtAuthenticationToken(jwt));
      }
    };
  }
}
