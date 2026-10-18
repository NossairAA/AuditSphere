package com.auditsphere.audit;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
class AuditorDirectory {
  record Person(String subject, String name) {}

  record CachedPerson(Person person, long until) {}

  private final RestClient client;
  private final String secret;
  private volatile String accessToken;
  private volatile long tokenUntil;
  private final Map<String, CachedPerson> names = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          8,
          8,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(256),
          r -> {
            Thread t = new Thread(r, "directory-reader");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  AuditorDirectory(
      @Value("${DIRECTORY_URL:http://localhost:8180}") String base,
      @Value("${DIRECTORY_CLIENT_SECRET:}") String secret) {
    this.secret = secret;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1000);
    factory.setReadTimeout(2000);
    client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
  }

  @jakarta.annotation.PreDestroy
  void close() {
    workers.shutdownNow();
  }

  private synchronized String token() {
    if (accessToken != null && System.currentTimeMillis() < tokenUntil) return accessToken;
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", "auditsphere-directory");
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

  private <T> T get(String path, Class<T> type, String token) {
    try {
      return client.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().body(type);
    } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
      tokenUntil = 0;
      throw e;
    }
  }

  List<Person> validators(int page) {
    return people(page, "AUDIT_VALIDATOR");
  }

  List<Person> auditors(int page) {
    return people(page, "AUDITOR");
  }

  private List<Person> people(int page, String role) {
    try {
      var users =
          Objects.requireNonNull(
              get(
                  "/admin/realms/auditsphere/roles/"
                      + role
                      + "/users?first="
                      + (page * 50)
                      + "&max=50",
                  Map[].class,
                  token()));
      // Keep page boundaries stable even if disabled accounts occur in a page.
      return Arrays.stream(users)
          .filter(u -> Boolean.TRUE.equals(u.get("enabled")))
          .map(this::person)
          .toList();
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  private String userPath(String subject) {
    if (!subject.matches("[a-zA-Z0-9_-]{1,255}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return "/admin/realms/auditsphere/users/" + subject;
  }

  void validateValidator(String subject) {
    validateRole(Set.of(subject), "AUDIT_VALIDATOR");
  }

  void validate(Set<String> subjects) {
    validateRole(subjects, "AUDITOR");
  }

  private void validateRole(Set<String> subjects, String role) {
    if (subjects.isEmpty()) return;
    parallel(
        subjects,
        subject -> {
          try {
            String path = userPath(subject), token = token();
            var user = get(path, Map.class, token);
            if (user == null || !Boolean.TRUE.equals(user.get("enabled")))
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            var roles = get(path + "/role-mappings/realm/composite", Map[].class, token);
            if (roles == null || Arrays.stream(roles).noneMatch(r -> role.equals(r.get("name"))))
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            return subject;
          } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
          }
        });
  }

  List<Person> participants(Set<String> subjects) {
    return parallel(
        new TreeSet<>(subjects),
        subject -> {
          var cached = names.get(subject);
          if (cached != null && cached.until() > System.currentTimeMillis()) return cached.person();
          Person person;
          try {
            person = person(Objects.requireNonNull(get(userPath(subject), Map.class, token())));
          } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            person = new Person(subject, "Compte indisponible");
          }
          if (names.size() >= 5000) names.clear();
          names.put(subject, new CachedPerson(person, System.currentTimeMillis() + 60000));
          return person;
        });
  }

  private <T> List<T> parallel(Set<String> subjects, Function<String, T> operation) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    var tasks = new ArrayList<Future<T>>();
    try {
      for (String subject : subjects) tasks.add(workers.submit(() -> operation.apply(subject)));
      var results = new ArrayList<T>();
      for (var task : tasks)
        results.add(task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      return results;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof ResponseStatusException status) throw status;
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    } finally {
      for (var task : tasks) if (!task.isDone()) task.cancel(true);
      workers.purge();
    }
  }

  private Person person(Map<?, ?> user) {
    String name =
        (Objects.toString(user.get("firstName"), "")
                + " "
                + Objects.toString(user.get("lastName"), ""))
            .strip();
    return new Person(
        user.get("id").toString(),
        name.isBlank() ? Objects.toString(user.get("username"), "Utilisateur") : name);
  }
}
