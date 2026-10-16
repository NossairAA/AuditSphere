package com.auditsphere.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
class GatewayConfig {
  @Bean
  RouteLocator routes(
      RouteLocatorBuilder builder, @Value("${AUDIT_URL:http://localhost:8081}") String url) {
    return builder
        .routes()
        .route("audit", r -> r.path("/missions", "/missions/**").uri(url))
        .build();
  }

  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity http) {
    return http.csrf(c -> c.disable())
        .authorizeExchange(
            a ->
                a.pathMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> {}))
        .build();
  }
}
