package com.auditsphere.audit.workflow;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.File;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;

/** Temporal connection options, with mutual TLS when the three certificate files are configured. */
public final class TemporalConnection {
  static final String CA = "TEMPORAL_TLS_CA_FILE";
  static final String CERT = "TEMPORAL_TLS_CERT_FILE";
  static final String KEY = "TEMPORAL_TLS_KEY_FILE";

  private TemporalConnection() {}

  public static WorkflowServiceStubsOptions.Builder options(String target, Map<String, String> env) {
    var builder = WorkflowServiceStubsOptions.newBuilder().setTarget(target);
    var files = List.of(CA, CERT, KEY).stream().map(name -> env.getOrDefault(name, "")).toList();
    if (files.stream().allMatch(String::isBlank)) return builder;
    // A partial configuration must never fall back to plaintext.
    if (files.stream().anyMatch(String::isBlank))
      throw new IllegalStateException(CA + ", " + CERT + " and " + KEY + " must be set together");
    for (String file : files)
      if (!new File(file).canRead()) throw new IllegalStateException("Unreadable TLS file: " + file);
    try {
      // The key must be PKCS#8. The server name is checked against the target host.
      // The client certificate is reread at each handshake, so a renewal needs no restart.
      return builder.setSslContext(
          GrpcSslContexts.forClient()
              .trustManager(new File(files.get(0)))
              .keyManager(new ReloadingKeyManager(Path.of(files.get(1)), Path.of(files.get(2))))
              .build());
    } catch (SSLException | GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Invalid Temporal TLS material", e);
    }
  }
}
