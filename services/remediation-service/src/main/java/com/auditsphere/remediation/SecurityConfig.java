package com.auditsphere.remediation;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
  /** Human roles only. Service accounts such as REMEDIATION_SYNC never reach this API. */
  static final String[] USER_ROLES = {
    "AUDIT_MANAGER", "AUDITOR", "AUDIT_VALIDATOR", "AUDITEE", "SUPER_ADMIN"
  };

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, @Value("${AUDIT_URL:http://localhost:8081}") String auditUrl)
      throws Exception {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Map<String, Object> realm = jwt.getClaim("realm_access");
          if (realm == null || !(realm.get("roles") instanceof List<?> roles)) return List.of();
          return roles.stream()
              .filter(String.class::isInstance)
              .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
              .toList();
        });
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                // A ResponseStatusException is rendered by an ERROR dispatch to /error. Denying it
                // would turn every 400, 404 or 409 into a 403.
                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/remediation/me/actions")
                    .hasRole("AUDITEE")
                    .requestMatchers("/remediation/**")
                    .hasAnyRole(USER_ROLES)
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .addFilterAfter(new AccountRevocationFilter(auditUrl), BearerTokenAuthenticationFilter.class)
        .build();
  }
}
