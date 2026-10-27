package com.auditsphere.audit.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReloadingKeyManagerTest {
  @TempDir Path dir;

  private record Identity(X509Certificate certificate, PrivateKey key, KeyStore store) {}

  @Test
  void switchesToRenewedFilesAndKeepsPreviousPair() throws Exception {
    var first = identity("first");
    var second = identity("second");
    var cert = dir.resolve("tls.crt");
    var key = dir.resolve("tls.key");
    write(first, cert, key);
    var manager = new ReloadingKeyManager(cert, key);

    String oldAlias = manager.chooseEngineClientAlias(new String[] {"EC"}, null, null);
    assertEquals(first.certificate(), manager.getCertificateChain(oldAlias)[0]);
    assertEquals(oldAlias, manager.chooseEngineClientAlias(new String[] {"EC"}, null, null));

    write(second, cert, key);
    String newAlias = manager.chooseEngineClientAlias(new String[] {"EC"}, null, null);
    assertNotEquals(oldAlias, newAlias);
    assertEquals(second.certificate(), manager.getCertificateChain(newAlias)[0]);
    assertArrayEquals(second.key().getEncoded(), manager.getPrivateKey(newAlias).getEncoded());
    // A handshake that chose the old alias just before the swap still gets a matching pair.
    assertArrayEquals(first.key().getEncoded(), manager.getPrivateKey(oldAlias).getEncoded());
  }

  @Test
  void keepsCurrentMaterialWhenRenewedFilesAreInvalid() throws Exception {
    var first = identity("first");
    var cert = dir.resolve("tls.crt");
    var key = dir.resolve("tls.key");
    write(first, cert, key);
    var manager = new ReloadingKeyManager(cert, key);
    String alias = manager.chooseClientAlias(new String[] {"EC"}, null, null);

    Files.writeString(key, "not a key");
    assertEquals(alias, manager.chooseClientAlias(new String[] {"EC"}, null, null));
    Files.delete(cert);
    assertEquals(alias, manager.chooseClientAlias(new String[] {"EC"}, null, null));
    assertEquals(first.certificate(), manager.getCertificateChain(alias)[0]);
  }

  @Test
  void invalidInitialFilesFailClosed() throws Exception {
    var cert = dir.resolve("tls.crt");
    var key = dir.resolve("tls.key");
    write(identity("first"), cert, key);
    Files.writeString(key, "not a key");
    assertThrows(Exception.class, () -> new ReloadingKeyManager(cert, key));
  }

  /** Two real TLS handshakes with client authentication, the files renewed between them. */
  @Test
  void nextHandshakePresentsRenewedCertificate() throws Exception {
    var server = identity("server");
    var first = identity("first");
    var second = identity("second");
    var cert = dir.resolve("tls.crt");
    var key = dir.resolve("tls.key");
    write(first, cert, key);

    var trusted = KeyStore.getInstance("PKCS12");
    trusted.load(null, null);
    trusted.setCertificateEntry("first", first.certificate());
    trusted.setCertificateEntry("second", second.certificate());
    var serverKeys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    serverKeys.init(server.store(), "changeit".toCharArray());
    var serverContext = SSLContext.getInstance("TLSv1.3");
    serverContext.init(serverKeys.getKeyManagers(), trust(trusted), null);

    var serverTrust = KeyStore.getInstance("PKCS12");
    serverTrust.load(null, null);
    serverTrust.setCertificateEntry("server", server.certificate());
    var clientContext = SSLContext.getInstance("TLSv1.3");
    clientContext.init(
        new javax.net.ssl.KeyManager[] {new ReloadingKeyManager(cert, key)},
        trust(serverTrust),
        null);

    try (var socket = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0)) {
      socket.setNeedClientAuth(true);
      assertEquals(first.certificate(), handshake(socket, clientContext));
      write(second, cert, key);
      assertEquals(second.certificate(), handshake(socket, clientContext));
    }
  }

  private static X509Certificate handshake(SSLServerSocket server, SSLContext client)
      throws Exception {
    var peer =
        CompletableFuture.supplyAsync(
            () -> {
              try (var accepted = (SSLSocket) server.accept()) {
                accepted.startHandshake();
                return (X509Certificate) accepted.getSession().getPeerCertificates()[0];
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    try (var socket =
        (SSLSocket) client.getSocketFactory().createSocket("localhost", server.getLocalPort())) {
      socket.startHandshake();
      return peer.get(10, TimeUnit.SECONDS);
    }
  }

  private static javax.net.ssl.TrustManager[] trust(KeyStore store) throws Exception {
    var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    return factory.getTrustManagers();
  }

  private static void write(Identity identity, Path cert, Path key) throws Exception {
    Files.writeString(cert, pem("CERTIFICATE", identity.certificate().getEncoded()));
    Files.writeString(key, pem("PRIVATE KEY", identity.key().getEncoded()));
  }

  /** Self-signed EC identity, generated for the test only. */
  private Identity identity(String name) throws Exception {
    var store = dir.resolve(name + ".p12");
    var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    var process =
        new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "id", "-keyalg", "EC", "-groupname",
                "secp256r1", "-dname", "CN=" + name, "-validity", "1", "-keystore",
                store.toString(), "-storetype", "PKCS12", "-storepass", "changeit")
            .redirectErrorStream(true)
            .start();
    assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
    var keys = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(store)) {
      keys.load(in, "changeit".toCharArray());
    }
    return new Identity(
        (X509Certificate) keys.getCertificate("id"),
        (PrivateKey) keys.getKey("id", "changeit".toCharArray()),
        keys);
  }

  private static String pem(String type, byte[] der) {
    return "-----BEGIN " + type + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
        + "\n-----END " + type + "-----\n";
  }
}
