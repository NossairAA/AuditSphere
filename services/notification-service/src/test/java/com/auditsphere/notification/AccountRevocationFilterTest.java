package com.auditsphere.notification;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AccountRevocationFilterTest {
  @Test
  void directNotificationRequestRejectsRevokedToken() throws Exception {
    HttpServer audit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    audit.createContext("/account/security", request -> {
      assertEquals("Bearer token", request.getRequestHeaders().getFirst("Authorization"));
      request.sendResponseHeaders(401, -1);
      request.close();
    });
    audit.start();
    try {
      var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user").build();
      SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
      var response = new MockHttpServletResponse();
      var forwarded = new AtomicBoolean();
      new AccountRevocationFilter("http://127.0.0.1:" + audit.getAddress().getPort())
          .doFilter(new MockHttpServletRequest("GET", "/notifications"), response,
              (request, servletResponse) -> forwarded.set(true));
      assertEquals(401, response.getStatus());
      assertFalse(forwarded.get());
    } finally {
      SecurityContextHolder.clearContext();
      audit.stop(0);
    }
  }
}
