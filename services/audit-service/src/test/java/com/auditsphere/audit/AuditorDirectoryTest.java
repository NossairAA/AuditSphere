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

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/realms/auditsphere/protocol/openid-connect/token",
        e -> {
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
    server.start();
    directory =
        new AuditorDirectory("http://127.0.0.1:" + server.getAddress().getPort(), "fixture");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void onlyEnabledAuditorsAreAssignable() {
    var people = directory.auditors();
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
}
