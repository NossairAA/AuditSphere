package com.auditsphere.gateway;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.netty.http.client.HttpClient;

@Configuration
class GatewayConfig {
  @Bean
  HttpClientCustomizer serviceDns() {
    return GatewayConfig::boundedDns;
  }

  /** Docker can change a service address after a restart. Bound both DNS caches and connects. */
  static HttpClient boundedDns(HttpClient client) {
    return client
        .resolver(
            r ->
                r.cacheMinTimeToLive(Duration.ZERO)
                    .cacheMaxTimeToLive(Duration.ofSeconds(5))
                    .cacheNegativeTimeToLive(Duration.ZERO))
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000);
  }

  @Bean
  RouteLocator routes(
      RouteLocatorBuilder builder,
      @Value("${AUDIT_URL:http://localhost:8081}") String url,
      @Value("${NOTIFICATION_URL:http://localhost:8082}") String notificationUrl,
      @Value("${REMEDIATION_URL:http://localhost:8083}") String remediationUrl) {
    return builder
        .routes()
        .route(
            "notifications",
            r -> r.path("/notifications", "/notifications/**").uri(notificationUrl))
        .route("remediation", r -> r.path("/remediation/**").uri(remediationUrl))
        .route(
            "audit",
            r -> r.path("/missions", "/missions/**", "/admin/**", "/account/security").uri(url))
        .build();
  }

  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity http) {
    return http.csrf(c -> c.disable())
        .authorizeExchange(
            a ->
                a.pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> {}))
        .build();
  }
}
