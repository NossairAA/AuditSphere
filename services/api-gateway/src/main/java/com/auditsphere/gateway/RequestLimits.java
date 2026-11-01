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
  private final java.util.Map<String, Integer> uploads = new java.util.HashMap<>();
  private int activeUploads;

  private synchronized boolean acquireUpload(String subject) {
    if (activeUploads >= 8 || uploads.getOrDefault(subject, 0) >= 2) return false;
    activeUploads++;
    uploads.merge(subject, 1, Integer::sum);
    return true;
  }

  private synchronized void releaseUpload(String subject) {
    activeUploads--;
    uploads.computeIfPresent(subject, (key, count) -> count == 1 ? null : count - 1);
  }

  @org.springframework.beans.factory.annotation.Value("${gateway.rate.ip-limit:600}")
  private int ipLimit = 600;

  @org.springframework.beans.factory.annotation.Value("${gateway.rate.user-limit:240}")
  private int userLimit = 240;

  @org.springframework.beans.factory.annotation.Value("${gateway.rate.global-limit:1200}")
  private int globalLimit = 1200;

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
    boolean evidenceUpload =
        exchange.getRequest().getMethod() == HttpMethod.POST
            && exchange
                .getRequest()
                .getPath()
                .value()
                .matches(
                    "/missions/[0-9a-fA-F-]{36}/controls/[0-9a-fA-F-]{36}/evidence"
                        + "|/remediation/actions/[0-9a-fA-F-]{36}/attachments");
    int maxBody = evidenceUpload ? 11 * 1024 * 1024 : 32768;
    String address =
        String.valueOf(
            exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress());
    if (!allow("global", globalLimit) || !allow("ip:" + address, ipLimit))
      return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
    return exchange
        .getPrincipal()
        .map(p -> p.getName())
        .defaultIfEmpty("anonymous")
        .flatMap(
            subject -> {
              if (!allow("user:" + subject, userLimit))
                return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
              if (exchange.getRequest().getHeaders().getContentLength() > maxBody)
                return reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE);
              if (exchange.getRequest().getMethod() != HttpMethod.POST
                  && exchange.getRequest().getMethod() != HttpMethod.PUT
                  && exchange.getRequest().getMethod() != HttpMethod.DELETE)
                return chain.filter(exchange);
              if (evidenceUpload && !acquireUpload(subject))
                return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
              return DataBufferUtils.join(exchange.getRequest().getBody(), maxBody)
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
                      e -> reject(exchange, HttpStatus.REQUEST_TIMEOUT))
                  .doFinally(signal -> { if (evidenceUpload) releaseUpload(subject); });
            });
  }
}
