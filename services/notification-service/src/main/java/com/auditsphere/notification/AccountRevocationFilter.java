package com.auditsphere.notification;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

/** Checks the audit service's revocation ledger for direct notification requests. */
class AccountRevocationFilter extends OncePerRequestFilter {
  private final RestClient audit;

  AccountRevocationFilter(@Value("${AUDIT_URL:http://localhost:8081}") String auditUrl) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1000);
    factory.setReadTimeout(2000);
    this.audit = RestClient.builder().baseUrl(auditUrl).requestFactory(factory).build();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
      try {
        HttpStatusCode status = audit.get().uri("/account/security")
            .headers(headers -> headers.setBearerAuth(jwt.getToken().getTokenValue()))
            .exchange((req, upstream) -> upstream.getStatusCode());
        if (!status.is2xxSuccessful()) {
          response.setStatus(status.value() == 401 ? 401 : 503);
          return;
        }
      } catch (RuntimeException unavailable) {
        response.setStatus(503);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
