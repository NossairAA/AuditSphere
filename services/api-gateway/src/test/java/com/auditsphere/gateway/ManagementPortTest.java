package com.auditsphere.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** Same settings as the k3s chart with managementPort: metrics on their own port only. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.endpoints.web.exposure.include=health,info,prometheus",
      "management.metrics.tags.application=api-gateway"
    })
class ManagementPortTest {
  private final HttpClient client = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  int serverPort;

  @Value("${local.management.port}")
  int managementPort;

  @Test
  void metricsAreServedWithoutTokenOnTheManagementPort() throws Exception {
    var response = get(managementPort, "/actuator/prometheus");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("jvm_memory_used_bytes"));
    assertTrue(response.body().contains("application=\"api-gateway\""));
  }

  @Test
  void metricsAreNotServedOnTheRoutedPort() throws Exception {
    assertNotEquals(200, get(serverPort, "/actuator/prometheus").statusCode());
  }

  @Test
  void healthMovesToTheManagementPort() throws Exception {
    assertEquals(200, get(managementPort, "/actuator/health/readiness").statusCode());
  }

  private HttpResponse<String> get(int port, String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
