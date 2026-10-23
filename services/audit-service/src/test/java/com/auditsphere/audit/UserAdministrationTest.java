package com.auditsphere.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserAdministrationTest {
  @Test
  void localSmtpFailureKeepsRequiredActionWithoutReturningPassword() throws Exception {
    var requests = new ArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/admin/realms/auditsphere/users/", exchange -> {
      var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
      exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("execute-actions-email") ? 500 : 204, -1);
      exchange.close();
    });
    server.start();
    try {
      var id = UUID.randomUUID().toString();
      var directory = new UserAdministration("http://127.0.0.1:" + server.getAddress().getPort(), "test", true);
      assertEquals("LOCAL_ACTION_REQUIRED", directory.sendPasswordAction(id, "test-token"));
      assertEquals(2, requests.size());
      assertTrue(requests.get(0).contains("/users/" + id + "/execute-actions-email [\"UPDATE_PASSWORD\"]"));
      assertTrue(requests.get(1).contains("/users/" + id + " {\"requiredActions\":[\"UPDATE_PASSWORD\"]}"));
      assertFalse(String.join(" ", requests).contains("password\""));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void productionSmtpFailureDoesNotApplyLocalFallback() throws Exception {
    var paths = new ArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/admin/realms/auditsphere/users/", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
    });
    server.start();
    try {
      var directory = new UserAdministration("http://127.0.0.1:" + server.getAddress().getPort(), "test", false);
      assertThrows(RuntimeException.class, () -> directory.sendPasswordAction(UUID.randomUUID().toString(), "test-token"));
      assertEquals(1, paths.size());
    } finally {
      server.stop(0);
    }
  }
}
