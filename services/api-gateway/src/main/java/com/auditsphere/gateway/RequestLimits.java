package com.auditsphere.gateway;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.*;

@Component
class RequestLimits implements GlobalFilter, Ordered {
  record Bucket(AtomicInteger count, long until) {}

  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  boolean allow(String key, int limit) {
    long now = System.currentTimeMillis();
    if (buckets.size() > 10000) {
      buckets.entrySet().removeIf(e -> e.getValue().until() < now);
      if (buckets.size() > 10000) return false;
    }
    Bucket b =
        buckets.compute(
            key,
            (k, old) ->
                old == null || old.until() < now
                    ? new Bucket(new AtomicInteger(), now + 60000)
                    : old);
    return b.count().incrementAndGet() <= limit;
  }

  public int getOrder() {
    return -100;
  }

  private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
    exchange.getResponse().setStatusCode(status);
    if (status == HttpStatus.TOO_MANY_REQUESTS)
      exchange.getResponse().getHeaders().set("Retry-After", "60");
    return exchange.getResponse().setComplete();
  }

  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String address =
        String.valueOf(
            exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress());
    if (!allow("global", 1200) || !allow("ip:" + address, 600))
      return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
    return exchange
        .getPrincipal()
        .map(p -> p.getName())
        .defaultIfEmpty("anonymous")
        .flatMap(
            subject -> {
              if (!allow("user:" + subject, 240))
                return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
              if (exchange.getRequest().getHeaders().getContentLength() > 32768)
                return reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE);
              if (exchange.getRequest().getMethod() != HttpMethod.POST
                  && exchange.getRequest().getMethod() != HttpMethod.PUT)
                return chain.filter(exchange);
              return DataBufferUtils.join(exchange.getRequest().getBody(), 32768)
                  .timeout(Duration.ofSeconds(5))
                  .map(
                      buffer -> {
                        byte[] body = new byte[buffer.readableByteCount()];
                        buffer.read(body);
                        DataBufferUtils.release(buffer);
                        return body;
                      })
                  .defaultIfEmpty(new byte[0])
                  .flatMap(
                      body -> {
                        var request =
                            new ServerHttpRequestDecorator(exchange.getRequest()) {
                              @Override
                              public Flux<DataBuffer> getBody() {
                                return Flux.defer(
                                    () ->
                                        Flux.just(
                                            exchange.getResponse().bufferFactory().wrap(body)));
                              }
                            };
                        return chain.filter(exchange.mutate().request(request).build());
                      })
                  .onErrorResume(
                      DataBufferLimitException.class,
                      e -> reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE))
                  .onErrorResume(
                      java.util.concurrent.TimeoutException.class,
                      e -> reject(exchange, HttpStatus.REQUEST_TIMEOUT));
            });
  }
}
