package com.auditsphere.gateway;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

/** A restarted service may get a new Docker address: no gateway client may keep the old one. */
class GatewayDnsTest {
  @Test
  void revocationLookupUsesTheBoundedDnsOfTheRoutes() {
    assertBounded(new AccountRevocationFilter("http://audit-service:8081").client);
    assertBounded(new GatewayConfig().serviceDns().customize(HttpClient.create()));
  }

  @Test
  void defaultClientWouldKeepAStaleAddress() {
    var resolver = HttpClient.create().configuration().getNameResolverProvider();
    assertTrue(resolver == null || resolver.cacheMaxTimeToLive().compareTo(Duration.ofSeconds(5)) > 0);
  }

  private static void assertBounded(HttpClient client) {
    var resolver = client.configuration().getNameResolverProvider();
    assertNotNull(resolver);
    assertEquals(Duration.ofSeconds(5), resolver.cacheMaxTimeToLive());
    assertEquals(Duration.ZERO, resolver.cacheMinTimeToLive());
    assertEquals(Duration.ZERO, resolver.cacheNegativeTimeToLive());
    assertEquals(3000, client.configuration().options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS));
  }
}
