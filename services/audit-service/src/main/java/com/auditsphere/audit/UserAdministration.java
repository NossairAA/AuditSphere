package com.auditsphere.audit;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Dedicated adapter. No database transaction spans these network requests. */
@Component
class UserAdministration {
  static final Set<String> ROLES =
      Set.of("SUPER_ADMIN", "AUDIT_MANAGER", "AUDITOR", "AUDIT_VALIDATOR");
  private final RestClient client;
  private final String secret;

  UserAdministration(
      @Value("${DIRECTORY_URL:http://localhost:8180}") String origin,
      @Value("${USER_ADMIN_CLIENT_SECRET:}") String secret) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(3000);
    this.client = RestClient.builder().baseUrl(origin).requestFactory(factory).build();
    this.secret = secret;
  }

  String token() {
    if (secret.isBlank())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Administration non configurée");
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", "auditsphere-user-admin");
    form.add("client_secret", secret);
    var value =
        client
            .post()
            .uri("/realms/auditsphere/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    return Objects.toString(value.get("access_token"));
  }

  private String base(String path) {
    return "/admin/realms/auditsphere" + path;
  }

  Map<?, ?> user(String id, String token) {
    var user =
        client
            .get()
            .uri(base("/users/" + UUID.fromString(id)))
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .body(Map.class);
    if (user == null
        || user.containsKey("serviceAccountClientId")
        || Objects.toString(user.get("username"), "").startsWith("service-account-"))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return user;
  }

  List<Map> getList(String path, String token) {
    Map[] result =
        client
            .get()
            .uri(base(path))
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .body(Map[].class);
    return result == null ? List.of() : Arrays.asList(result);
  }

  List<Map> all(String path, String token) {
    var result = new ArrayList<Map>();
    for (int first = 0; first < 1000; first += 100) {
      var batch =
          getList(path + (path.contains("?") ? "&" : "?") + "first=" + first + "&max=100", token);
      result.addAll(batch);
      if (batch.size() < 100) return result;
    }
    throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Annuaire trop volumineux pour cette vue");
  }

  record Person(
      String id,
      String username,
      String firstName,
      String lastName,
      String email,
      boolean enabled,
      Set<String> roles) {}

  Person person(Map<?, ?> user, Set<String> roles) {
    return new Person(
        Objects.toString(user.get("id")),
        Objects.toString(user.get("username"), ""),
        Objects.toString(user.get("firstName"), ""),
        Objects.toString(user.get("lastName"), ""),
        Objects.toString(user.get("email"), ""),
        Boolean.TRUE.equals(user.get("enabled")),
        roles);
  }

  Set<String> roles(String id, String token) {
    var result = new TreeSet<String>();
    for (var role : getList("/users/" + id + "/role-mappings/realm/composite", token))
      if (ROLES.contains(role.get("name"))) result.add(role.get("name").toString());
    return result;
  }

  List<Person> list(String token) {
    var roles = new HashMap<String, Set<String>>();
    for (String role : ROLES)
      for (var u : all("/roles/" + role + "/users", token))
        roles.computeIfAbsent(u.get("id").toString(), k -> new TreeSet<>()).add(role);
    return all("/users", token).stream()
        .filter(
            u ->
                !u.containsKey("serviceAccountClientId")
                    && !Objects.toString(u.get("username"), "").startsWith("service-account-"))
        .map(u -> person(u, roles.getOrDefault(u.get("id").toString(), Set.of())))
        .sorted(Comparator.comparing(Person::username))
        .toList();
  }

  String create(Map<String, Object> body, String token) {
    var response =
        client
            .post()
            .uri(base("/users"))
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
    String location = Objects.requireNonNull(response.getHeaders().getLocation()).getPath();
    return UUID.fromString(location.substring(location.lastIndexOf('/') + 1)).toString();
  }

  void update(String id, Map<String, Object> body, String token) {
    send(HttpMethod.PUT, "/users/" + id, body, token);
  }

  void reset(String id, String password, String token) {
    send(
        HttpMethod.PUT,
        "/users/" + id + "/reset-password",
        Map.of("type", "password", "value", password, "temporary", true),
        token);
  }

  void logout(String id, String token) {
    send(HttpMethod.POST, "/users/" + id + "/logout", null, token);
  }

  void setRoles(String id, Set<String> desired, String token) {
    var existing = getList("/users/" + id + "/role-mappings/realm", token);
    var remove =
        existing.stream()
            .filter(r -> ROLES.contains(r.get("name")) && !desired.contains(r.get("name")))
            .toList();
    if (!remove.isEmpty())
      send(HttpMethod.DELETE, "/users/" + id + "/role-mappings/realm", remove, token);
    var add = new ArrayList<Map>();
    for (String role : desired)
      if (existing.stream().noneMatch(r -> role.equals(r.get("name"))))
        add.add(
            client
                .get()
                .uri(base("/roles/" + role))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(Map.class));
    if (!add.isEmpty()) send(HttpMethod.POST, "/users/" + id + "/role-mappings/realm", add, token);
    if (!roles(id, token).equals(desired))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Des rôles hérités nécessitent une intervention Keycloak");
  }

  private void send(HttpMethod method, String path, Object body, String token) {
    var request = client.method(method).uri(base(path)).headers(h -> h.setBearerAuth(token));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    request.retrieve().toBodilessEntity();
  }
}
