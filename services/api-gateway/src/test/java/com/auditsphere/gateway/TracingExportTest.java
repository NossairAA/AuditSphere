package com.auditsphere.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same settings as the k3s chart with tracing.enabled: spans reach an OTLP endpoint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracingExportTest {
  private static final BlockingQueue<String> EXPORTS = new LinkedBlockingQueue<>();
  private static final HttpServer COLLECTOR = collector();

  @Value("${local.server.port}")
  int serverPort;

  @DynamicPropertySource
  static void tracing(DynamicPropertyRegistry registry) {
    registry.add("management.tracing.export.enabled", () -> "true");
    registry.add("management.tracing.sampling.probability", () -> "1.0");
    registry.add(
        "management.opentelemetry.tracing.export.otlp.endpoint",
        () -> "http://localhost:" + COLLECTOR.getAddress().getPort() + "/v1/traces");
  }

  @AfterAll
  static void stop() {
    COLLECTOR.stop(0);
  }

  @Test
  void requestSpanIsExportedWithTheServiceName() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/missions")).build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
    assertEquals(401, response.statusCode());

    // The batch processor flushes every few seconds.
    String export = EXPORTS.poll(30, TimeUnit.SECONDS);
    assertNotNull(export, "No span exported");
    assertTrue(export.contains("api-gateway"), "Span without the service name");
  }

  private static HttpServer collector() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/v1/traces",
          exchange -> {
            // OTLP protobuf: the service name appears as plain bytes in the payload.
            byte[] body = exchange.getRequestBody().readAllBytes();
            EXPORTS.add(new String(body, StandardCharsets.ISO_8859_1));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
