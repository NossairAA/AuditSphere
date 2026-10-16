package com.auditsphere.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class AuditorDirectoryTest {
  HttpServer server;
  AuditorDirectory directory;
  AtomicInteger status = new AtomicInteger(200);
  AtomicInteger tokenCalls = new AtomicInteger(), userCalls = new AtomicInteger();

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/realms/auditsphere/protocol/openid-connect/token",
        e -> {
          tokenCalls.incrementAndGet();
          byte[] body = "{\"access_token\":\"fixture-token\"}".getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, body.length);
          try (var out = e.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/admin/realms/auditsphere/roles/AUDITOR/users",
        e -> {
          byte[] body =
              "[{\"id\":\"active\",\"firstName\":\"Alice\",\"lastName\":\"Audit\",\"enabled\":true},{\"id\":\"disabled\",\"username\":\"Disabled\",\"enabled\":false}]"
                  .getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(status.get(), body.length);
          try (var out = e.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/admin/realms/auditsphere/users/",
        e -> {
          String path = e.getRequestURI().getPath();
          if (!path.endsWith("/composite")) userCalls.incrementAndGet();
          String body =
              path.endsWith("/composite")
                  ? "[{\"name\":\"AUDITOR\"}]"
                  : "{\"id\":\"active\",\"firstName\":\"Alice\",\"enabled\":"
                      + (!path.contains("disabled"))
                      + "}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          int code = path.contains("unknown") ? 404 : status.get();
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(code, bytes.length);
          try (var output = e.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    directory =
        new AuditorDirectory("http://127.0.0.1:" + server.getAddress().getPort(), "fixture");
  }

  @AfterEach
  void stop() {
    directory.close();
    server.stop(0);
  }

  @Test
  void onlyEnabledAuditorsAreAssignable() {
    var people = directory.auditors(0);
    assertEquals(1, people.size());
    assertEquals("Alice Audit", people.get(0).name());
    directory.validate(Set.of("active"));
    for (String id : new String[] {"disabled", "unknown"})
      assertEquals(
          400,
          assertThrows(ResponseStatusException.class, () -> directory.validate(Set.of(id)))
              .getStatusCode()
              .value());
  }

  @Test
  void remoteFailureFailsClosed() {
    status.set(403);
    assertEquals(
        503,
        assertThrows(ResponseStatusException.class, () -> directory.validate(Set.of("active")))
            .getStatusCode()
            .value());
    assertDoesNotThrow(() -> directory.validate(Set.of()));
  }

  @Test
  void tokenAndNamesAreCachedButEligibilityIsAlwaysRevalidated() {
    directory.participants(Set.of("active"));
    directory.participants(Set.of("active"));
    assertEquals(1, userCalls.get());
    assertEquals(1, tokenCalls.get());
    directory.validate(Set.of("active"));
    directory.validate(Set.of("active"));
    assertEquals(3, userCalls.get());
    assertEquals(1, tokenCalls.get());
    status.set(503);
    assertThrows(ResponseStatusException.class, () -> directory.validate(Set.of("active")));
  }
}
