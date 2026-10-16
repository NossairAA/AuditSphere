package com.auditsphere.audit;

import java.util.*;
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

  private final RestClient client;
  private final String secret;

  AuditorDirectory(
      @Value("${DIRECTORY_URL:http://localhost:8180}") String base,
      @Value("${DIRECTORY_CLIENT_SECRET:}") String secret) {
    this.secret = secret;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
  }

  private String token() {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", "auditsphere-directory");
    form.add("client_secret", secret);
    var body =
        client
            .post()
            .uri("/realms/auditsphere/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    return (String) Objects.requireNonNull(body).get("access_token");
  }

  List<Person> auditors() {
    try {
      String token = token();
      var result = new ArrayList<Person>();
      for (int first = 0; first < 5000; first += 100) {
        var users =
            client
                .get()
                .uri("/admin/realms/auditsphere/roles/AUDITOR/users?first=" + first + "&max=100")
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(Map[].class);
        Objects.requireNonNull(users);
        for (var user : users)
          if (Boolean.TRUE.equals(user.get("enabled"))) result.add(person(user));
        if (users.length < 100)
          return result.stream()
              .sorted(Comparator.comparing(Person::name).thenComparing(Person::subject))
              .toList();
      }
      throw new IllegalStateException("Directory limit reached");
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  List<Person> participants(Set<String> subjects) {
    try {
      String token = token();
      var result = new ArrayList<Person>();
      for (String subject : new TreeSet<>(subjects)) {
        try {
          var user =
              client
                  .get()
                  .uri("/admin/realms/auditsphere/users/{id}", subject)
                  .headers(h -> h.setBearerAuth(token))
                  .retrieve()
                  .body(Map.class);
          result.add(person(Objects.requireNonNull(user)));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
          result.add(new Person(subject, "Compte indisponible"));
        }
      }
      return result;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  void validate(Set<String> subjects) {
    if (subjects.isEmpty()) return;
    var eligible =
        auditors().stream().map(Person::subject).collect(java.util.stream.Collectors.toSet());
    if (!eligible.containsAll(subjects)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
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
