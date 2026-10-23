package com.auditsphere.gateway;

import java.time.Duration;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Checks the audit service's revocation ledger before forwarding an authenticated request. */
@Component
class AccountRevocationFilter implements GlobalFilter, Ordered {
  private final WebClient audit;

  AccountRevocationFilter(@Value("${AUDIT_URL:http://localhost:8081}") String auditUrl) {
    this.audit = WebClient.builder().baseUrl(auditUrl).build();
  }

  @Override
  public int getOrder() { return -110; }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return exchange.getPrincipal().defaultIfEmpty((Principal) () -> "anonymous").flatMap(principal -> {
      if (!(principal instanceof JwtAuthenticationToken jwt)) return chain.filter(exchange);
      return audit.get().uri("/account/security")
          .headers(headers -> headers.setBearerAuth(jwt.getToken().getTokenValue()))
          .exchangeToMono(response -> {
            HttpStatus status = response.statusCode().is2xxSuccessful() ? HttpStatus.OK
                : response.statusCode().value() == 401 ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE;
            return response.releaseBody().thenReturn(status);
          })
          .timeout(Duration.ofSeconds(2))
          .onErrorReturn(HttpStatus.SERVICE_UNAVAILABLE)
          .flatMap(status -> status == HttpStatus.OK ? chain.filter(exchange) : reject(exchange, status));
    });
  }

  private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
    exchange.getResponse().setStatusCode(status);
    return exchange.getResponse().setComplete();
  }
}
