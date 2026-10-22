package com.auditsphere.audit;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevents already-issued audit tokens from retaining changed administrative privileges. */
class AccountSecurity extends OncePerRequestFilter {
  private final JdbcTemplate jdbc;

  AccountSecurity(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken auth) {
      var rows =
          jdbc.queryForList(
              "SELECT revoked_before FROM account_revocations WHERE subject=?",
              Long.class,
              auth.getToken().getSubject());
      if (!rows.isEmpty()
          && (auth.getToken().getIssuedAt() == null
              || auth.getToken().getIssuedAt().getEpochSecond() <= rows.getFirst())) {
        response.setStatus(401);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}

@RestController
class AccountSecurityController {
  @GetMapping("/account/security")
  java.util.Map<String, Boolean> check() {
    return java.util.Map.of("active", true);
  }
}
