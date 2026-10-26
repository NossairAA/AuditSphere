package com.auditsphere.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.auditsphere.audit.workflow.TemporalConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporalConnectionTest {
  @TempDir Path dir;

  @Test
  void plaintextWithoutTlsSettings() {
    assertNull(TemporalConnection.options("temporal:7233", Map.of()).build().getSslContext());
  }

  @Test
  void partialTlsSettingsFailClosed() {
    var env = Map.of("TEMPORAL_TLS_CA_FILE", "/ca.crt", "TEMPORAL_TLS_CERT_FILE", "/tls.crt");
    assertThrows(IllegalStateException.class, () -> TemporalConnection.options("t:7233", env));
  }

  @Test
  void unreadableFilesFailClosed() {
    var env =
        Map.of(
            "TEMPORAL_TLS_CA_FILE", "/missing/ca.crt",
            "TEMPORAL_TLS_CERT_FILE", "/missing/tls.crt",
            "TEMPORAL_TLS_KEY_FILE", "/missing/tls.key");
    assertThrows(IllegalStateException.class, () -> TemporalConnection.options("t:7233", env));
  }

  @Test
  void mutualTlsWithPkcs8Key() throws Exception {
    var env = generatedMaterial();
    var options = TemporalConnection.options("temporal:7233", env).build();
    assertNotNull(options.getSslContext());
    assertTrue(options.getSslContext().isClient());
  }

  @Test
  void invalidKeyFailsClosed() throws Exception {
    var env = generatedMaterial();
    Files.writeString(Path.of(env.get("TEMPORAL_TLS_KEY_FILE")), "not a key");
    assertThrows(IllegalStateException.class, () -> TemporalConnection.options("t:7233", env));
  }

  /** Self-signed EC certificate and PKCS#8 key, generated for the test only. */
  private Map<String, String> generatedMaterial() throws Exception {
    var store = dir.resolve("test.p12");
    var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    var process =
        new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "client", "-keyalg", "EC", "-groupname",
                "secp256r1", "-dname", "CN=temporal-test", "-validity", "1", "-keystore",
                store.toString(), "-storetype", "PKCS12", "-storepass", "changeit")
            .redirectErrorStream(true)
            .start();
    assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
    var keys = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(store)) {
      keys.load(in, "changeit".toCharArray());
    }
    var certificate = pem("CERTIFICATE", keys.getCertificate("client").getEncoded());
    var key = pem("PRIVATE KEY", ((PrivateKey) keys.getKey("client", "changeit".toCharArray())).getEncoded());
    var cert = Files.writeString(dir.resolve("tls.crt"), certificate);
    var ca = Files.writeString(dir.resolve("ca.crt"), certificate);
    var pkcs8 = Files.writeString(dir.resolve("tls.key"), key);
    return Map.of(
        "TEMPORAL_TLS_CA_FILE", ca.toString(),
        "TEMPORAL_TLS_CERT_FILE", cert.toString(),
        "TEMPORAL_TLS_KEY_FILE", pkcs8.toString());
  }

  private static String pem(String type, byte[] der) {
    return "-----BEGIN " + type + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
        + "\n-----END " + type + "-----\n";
  }
}
