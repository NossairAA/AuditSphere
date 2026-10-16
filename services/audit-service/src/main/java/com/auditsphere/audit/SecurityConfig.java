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
  SecurityFilterChain security(HttpSecurity http) throws Exception {
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
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/missions")
                    .hasRole("AUDIT_MANAGER")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.PUT, "/missions/*/assignments")
                    .hasRole("AUDIT_MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/missions/auditors")
                    .hasRole("AUDIT_MANAGER")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .build();
  }
}
