package com.auditsphere.audit;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, org.springframework.jdbc.core.JdbcTemplate jdbc)
      throws Exception {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Map<String, Object> realm = jwt.getClaim("realm_access");
          if (realm == null || !(realm.get("roles") instanceof List<?> roles)) return List.of();
          return roles.stream()
              .filter(String.class::isInstance)
              .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
              .map(a -> (org.springframework.security.core.GrantedAuthority) a)
              .toList();
        });
    return http.addFilterAfter(
            new AccountSecurity(jdbc),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/admin", "/admin/**")
                    .hasRole("SUPER_ADMIN")
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/missions")
                    .hasAnyRole("AUDIT_MANAGER", "SUPER_ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.PUT,
                        "/missions/*/assignments",
                        "/missions/*/schedule",
                        "/missions/*/status",
                        "/missions/*/validator",
                        "/missions/*/submission",
                        "/missions/*/withdrawal")
                    .hasAnyRole("AUDIT_MANAGER", "SUPER_ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/missions/auditors",
                        "/missions/validators")
                    .hasAnyRole("AUDIT_MANAGER", "SUPER_ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/missions/*")
                    .hasAnyRole("AUDIT_MANAGER", "SUPER_ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.PUT, "/missions/*/review")
                    .hasAnyRole("AUDIT_VALIDATOR", "SUPER_ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.PUT, "/missions/*/override")
                    .hasRole("SUPER_ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/missions/*/action-log")
                    .hasRole("SUPER_ADMIN")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .build();
  }
}
