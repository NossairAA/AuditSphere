package com.auditsphere.remediation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only Keycloak adapter of the remediation-directory client, on the model of ADR-0005. It
 * holds view-users and view-realm only.
 */
@Component
class AuditeeDirectory {
  record Person(String subject, String name, boolean active) {}

  private record Cached(Person person, long until) {}

  static final String ROLE = "AUDITEE";
  private static final String REALM = "/admin/realms/auditsphere";

  private final RestClient client;
  private final String secret;
  private volatile String accessToken;
  private volatile long tokenUntil;
  private final Map<String, Cached> people = new ConcurrentHashMap<>();

  AuditeeDirectory(
      @Value("${KEYCLOAK_URL:http://localhost:8180}") String base,
      @Value("${REMEDIATION_DIRECTORY_CLIENT_SECRET:}") String secret) {
    this.secret = secret;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1000);
    factory.setReadTimeout(2000);
    client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
  }

  /** Active AUDITEE accounts whose name, username or email contains the query, 20 at most. */
  List<Person> search(String query) {
    String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    try {
      var users =
          Objects.requireNonNull(
              get(REALM + "/roles/" + ROLE + "/users?first=0&max=500", Map[].class));
      return Arrays.stream(users)
          .filter(u -> Boolean.TRUE.equals(u.get("enabled")))
          .filter(
              u ->
                  q.isEmpty()
                      || ("%s %s %s %s"
                              .formatted(
                                  u.get("firstName"), u.get("lastName"), u.get("username"), u.get("email"))
                              .toLowerCase(Locale.ROOT)
                              .contains(q)))
          .map(this::person)
          .sorted(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER))
          .limit(20)
          .toList();
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  /** 400 unless the subject is an enabled account holding AUDITEE, 503 if Keycloak is down. */
  Person requireAuditee(String subject) {
    String path = userPath(subject);
    try {
      var user = get(path, Map.class);
      if (user == null || !Boolean.TRUE.equals(user.get("enabled")))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
      var roles = get(path + "/role-mappings/realm/composite", Map[].class);
      if (roles == null || Arrays.stream(roles).noneMatch(r -> ROLE.equals(r.get("name"))))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
      return person(user);
    } catch (HttpClientErrorException.NotFound e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  /** Display names for a read. Never fails: an unknown or unreachable account has no name. */
  Map<String, Person> names(Collection<String> subjects) {
    var result = new HashMap<String, Person>();
    for (String subject : new TreeSet<>(subjects)) {
      var cached = people.get(subject);
      if (cached != null && cached.until() > System.currentTimeMillis()) {
        result.put(subject, cached.person());
        continue;
      }
      try {
        var person = person(Objects.requireNonNull(get(userPath(subject), Map.class)));
        if (people.size() >= 5000) people.clear();
        people.put(subject, new Cached(person, System.currentTimeMillis() + 60000));
        result.put(subject, person);
      } catch (HttpClientErrorException.NotFound e) {
        result.put(subject, new Person(subject, null, false));
      } catch (RuntimeException e) {
        // Keycloak unavailable: the read still answers, without the remaining names.
        break;
      }
    }
    return result;
  }

  private String userPath(String subject) {
    if (subject == null || !subject.matches("[a-zA-Z0-9_-]{1,255}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return REALM + "/users/" + subject;
  }

  private Person person(Map<?, ?> user) {
    String name =
        (Objects.toString(user.get("firstName"), "") + " " + Objects.toString(user.get("lastName"), ""))
            .strip();
    return new Person(
        user.get("id").toString(),
        name.isBlank() ? Objects.toString(user.get("username"), "Utilisateur") : name,
        Boolean.TRUE.equals(user.get("enabled")));
  }

  private <T> T get(String path, Class<T> type) {
    try {
      return client.get().uri(path).headers(h -> h.setBearerAuth(token())).retrieve().body(type);
    } catch (HttpClientErrorException.Unauthorized e) {
      tokenUntil = 0;
      throw e;
    }
  }

  private synchronized String token() {
    if (accessToken != null && System.currentTimeMillis() < tokenUntil) return accessToken;
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", "remediation-directory");
    form.add("client_secret", secret);
    var body =
        Objects.requireNonNull(
            client
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
