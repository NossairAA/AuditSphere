package com.auditsphere.audit.workflow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client key manager that rereads the PEM certificate and PKCS#8 key at each TLS handshake and
 * switches to them when their content changes. A renewed certificate is used by the next
 * connection, without restarting the process.
 */
final class ReloadingKeyManager extends X509ExtendedKeyManager {
  private static final Logger log = LoggerFactory.getLogger(ReloadingKeyManager.class);
  private static final String PKCS8_LABEL = "PRIVATE KEY";

  private record Material(String alias, X509Certificate[] chain, PrivateKey key) {}

  private final Path certFile;
  private final Path keyFile;
  private volatile Material current;
  // Kept so that a handshake that chose the previous alias still gets a matching pair.
  private volatile Material previous;

  ReloadingKeyManager(Path certFile, Path keyFile) throws GeneralSecurityException {
    this.certFile = certFile;
    this.keyFile = keyFile;
    try {
      current = load();
    } catch (IOException e) {
      throw new GeneralSecurityException("Unreadable TLS file", e);
    }
  }

  /** Rereads the files. On any error, the material already loaded stays in use. */
  private Material refresh() {
    try {
      var material = load();
      if (!material.alias().equals(current.alias())) {
        previous = current;
        current = material;
        log.info(
            "Temporal client certificate reloaded, serial {} valid until {}",
            material.chain()[0].getSerialNumber().toString(16),
            material.chain()[0].getNotAfter().toInstant());
      }
    } catch (Exception e) {
      log.warn("Temporal client certificate not reloaded, keeping the current one: {}", e.toString());
    }
    return current;
  }

  private Material load() throws IOException, GeneralSecurityException {
    byte[] cert = Files.readAllBytes(certFile);
    byte[] key = Files.readAllBytes(keyFile);
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(cert);
    digest.update(key);
    // The alias names the content, so a change of files gives a new alias.
    String alias = "client-" + HexFormat.of().formatHex(digest.digest(), 0, 8);
    Material loaded = current;
    if (loaded != null && loaded.alias().equals(alias)) return loaded;
    var chain =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(new ByteArrayInputStream(cert))
            .stream()
            .map(X509Certificate.class::cast)
            .toArray(X509Certificate[]::new);
    if (chain.length == 0) throw new GeneralSecurityException("No certificate in " + certFile);
    var privateKey = privateKey(key, chain[0].getPublicKey().getAlgorithm());
    return new Material(alias, chain, privateKey);
  }

  private static PrivateKey privateKey(byte[] pem, String algorithm) throws GeneralSecurityException {
    String text = new String(pem, StandardCharsets.US_ASCII);
    String header = "-----BEGIN " + PKCS8_LABEL + "-----";
    int begin = text.indexOf(header);
    int end = text.indexOf("-----END " + PKCS8_LABEL + "-----");
    if (begin < 0 || end < begin) throw new GeneralSecurityException("Key is not PEM PKCS#8");
    String body = text.substring(begin + header.length(), end);
    byte[] der;
    try {
      der = Base64.getMimeDecoder().decode(body);
    } catch (IllegalArgumentException e) {
      throw new GeneralSecurityException("Key is not PEM PKCS#8", e);
    }
    try {
      return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
    } finally {
      Arrays.fill(der, (byte) 0);
    }
  }

  private Material byAlias(String alias) {
    for (Material material : new Material[] {current, previous})
      if (material != null && material.alias().equals(alias)) return material;
    return null;
  }

  @Override
  public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
    return refresh().alias();
  }

  @Override
  public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
    return refresh().alias();
  }

  @Override
  public X509Certificate[] getCertificateChain(String alias) {
    Material material = byAlias(alias);
    return material == null ? null : material.chain().clone();
  }

  @Override
  public PrivateKey getPrivateKey(String alias) {
    Material material = byAlias(alias);
    return material == null ? null : material.key();
  }

  @Override
  public String[] getClientAliases(String keyType, Principal[] issuers) {
    return new String[] {current.alias()};
  }

  @Override
  public String[] getServerAliases(String keyType, Principal[] issuers) {
    return null;
  }

  @Override
  public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
    return null;
  }
}
