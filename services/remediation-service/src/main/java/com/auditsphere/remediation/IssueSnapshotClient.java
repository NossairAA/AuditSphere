package com.auditsphere.remediation;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Reads the frozen snapshot of an issue cycle from audit-service with the REMEDIATION_SYNC role. */
@Component
class IssueSnapshotClient {
  record IssuedFinding(
      UUID id,
      long version,
      int reference,
      String title,
      String severity,
      String conditionText,
      String cause,
      String recommendation) {}

  record IssueSnapshot(
      UUID missionId,
      int issueCycle,
      String title,
      String ownerSubject,
      String validatorSubject,
      List<String> auditorSubjects,
      List<IssuedFinding> findings) {}

  /** Network error, 5xx or rejected token: the record stays unacknowledged and is retried. */
  static class SnapshotUnavailable extends RuntimeException {
    SnapshotUnavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final RestClient audit;
  private final RestClient keycloak;
  private final String secret;
  private volatile String accessToken;
  private volatile long tokenUntil;

  IssueSnapshotClient(
      @Value("${AUDIT_URL:http://localhost:8081}") String auditUrl,
      @Value("${KEYCLOAK_URL:http://localhost:8180}") String keycloakUrl,
      @Value("${REMEDIATION_SYNC_CLIENT_SECRET:}") String secret) {
    this.secret = secret;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1000);
    factory.setReadTimeout(3000);
    audit = RestClient.builder().baseUrl(auditUrl).requestFactory(factory).build();
    keycloak = RestClient.builder().baseUrl(keycloakUrl).requestFactory(factory).build();
  }

  /** Empty when the cycle is no longer current (409) or the mission is unknown (404). */
  Optional<IssueSnapshot> read(UUID missionId, int cycle) {
    try {
      return audit
          .get()
          .uri("/internal/missions/{id}/issues/{cycle}", missionId, cycle)
          .headers(h -> h.setBearerAuth(token()))
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404 || status == 409) return Optional.<IssueSnapshot>empty();
                if (status == 401) tokenUntil = 0;
                if (status != 200) throw new IllegalStateException("Snapshot read returned " + status);
                return Optional.of(Objects.requireNonNull(response.bodyTo(IssueSnapshot.class)));
              });
    } catch (RuntimeException e) {
      throw new SnapshotUnavailable("Issue snapshot unavailable", e);
    }
  }

  private synchronized String token() {
    if (accessToken != null && System.currentTimeMillis() < tokenUntil) return accessToken;
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", "remediation-service");
    form.add("client_secret", secret);
    var body =
        Objects.requireNonNull(
            keycloak
                .post()
                .uri("/realms/auditsphere/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class));
    accessToken = (String) body.get("access_token");
    tokenUntil =
        System.currentTimeMillis()
            + Math.max(0, ((Number) body.getOrDefault("expires_in", 30)).longValue() - 15) * 1000;
    return accessToken;
  }
}
