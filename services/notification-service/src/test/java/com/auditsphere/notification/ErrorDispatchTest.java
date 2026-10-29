package com.auditsphere.notification;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real Tomcat, unlike MockMvc: a ResponseStatusException is rendered through the /error dispatch,
 * which the security chain must let through so a 400 or 404 does not turn into a 403.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=true", "spring.kafka.listener.auto-startup=false"})
@Testcontainers
class ErrorDispatchTest {
  static final com.sun.net.httpserver.HttpServer auditSecurity;

  static {
    try {
      auditSecurity =
          com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      auditSecurity.createContext(
          "/account/security",
          exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      auditSecurity.start();
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void auditSecurityUrl(org.springframework.test.context.DynamicPropertyRegistry properties) {
    properties.add("AUDIT_URL", () -> "http://127.0.0.1:" + auditSecurity.getAddress().getPort());
  }

  @AfterAll
  static void stopAuditSecurity() {
    auditSecurity.stop(0);
  }

  /** The bearer value is the subject. */
  @TestConfiguration
  static class Tokens {
    @Bean
    @Primary
    JwtDecoder testDecoder() {
      return token -> Jwt.withTokenValue(token).header("alg", "none").subject(token).build();
    }
  }

  @Container @ServiceConnection
  static PostgreSQLContainer db =
      new PostgreSQLContainer(
          "postgres@sha256:2a0d0fe14825b0939f78a8cad5cd4e6aa68bf94d0e5dd96e24b6d23af4315545");

  @LocalServerPort int port;

  int status(HttpMethod method, String path) {
    return RestClient.create("http://127.0.0.1:" + port)
        .method(method)
        .uri(path)
        .headers(h -> h.setBearerAuth("error-dispatch-" + UUID.randomUUID()))
        .exchange((request, response) -> response.getStatusCode().value());
  }

  @Test
  void handledErrorsKeepTheirStatusBehindTheSecurityChain() {
    assertEquals(400, status(HttpMethod.GET, "/notifications?page=-1"));
    assertEquals(400, status(HttpMethod.GET, "/notifications?size=0"));
    assertEquals(404, status(HttpMethod.PUT, "/notifications/" + UUID.randomUUID() + "/read"));
    assertEquals(200, status(HttpMethod.GET, "/notifications"));
  }
}
