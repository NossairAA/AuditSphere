package com.auditsphere.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
class UserAdminController {
  private final UserAdministration directory;
  private final JdbcTemplate jdbc;

  UserAdminController(UserAdministration directory, JdbcTemplate jdbc) {
    this.directory = directory;
    this.jdbc = jdbc;
  }

  record Input(
      @Pattern(regexp = "[a-zA-Z0-9._-]{3,80}") String username,
      @NotBlank @Size(max = 100) String firstName,
      @NotBlank @Size(max = 100) String lastName,
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull Boolean enabled,
      @NotNull @Size(max = 4) Set<String> roles,
      @NotBlank @Size(max = 2000) String reason) {}

  record Reason(@NotBlank @Size(max = 2000) String reason) {}

  @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  @GetMapping("/summary")
  Map<String, Object> summary() {
    var summary = jdbc.queryForMap(
        "SELECT count(*) AS total, count(*) FILTER (WHERE status='IN_PROGRESS') AS \"inProgress\", count(*) FILTER (WHERE status='PENDING_REVIEW') AS pending, count(*) FILTER (WHERE planned_end_date < (now() AT TIME ZONE 'UTC')::date AND status <> 'CLOSED') AS overdue FROM missions WHERE deleted_at IS NULL");
    var counts = new LinkedHashMap<String, Long>();
    for (String status : List.of("DRAFT", "IN_PROGRESS", "PENDING_REVIEW", "APPROVED", "CLOSED"))
      counts.put(status, 0L);
    jdbc.query("SELECT status, count(*) AS total FROM missions WHERE deleted_at IS NULL GROUP BY status",
        (org.springframework.jdbc.core.RowCallbackHandler) row -> counts.put(row.getString("status"), row.getLong("total")));
    summary.put("byStatus", counts);
    return summary;
  }

  @GetMapping("/users")
  Object users(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String role,
      @RequestParam(defaultValue = "") String enabled) {
    if (page < 0
        || q.length() > 200
        || (!role.isBlank() && !UserAdministration.ROLES.contains(role))
        || !Set.of("", "true", "false").contains(enabled))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    try {
      var all =
          directory.list(directory.token()).stream()
              .filter(
                  u ->
                      (role.isBlank() || u.roles().contains(role))
                          && (enabled.isBlank() || u.enabled() == Boolean.parseBoolean(enabled))
                          && (u.username()
                                  + " "
                                  + u.firstName()
                                  + " "
                                  + u.lastName()
                                  + " "
                                  + u.email())
                              .toLowerCase(Locale.ROOT)
                              .contains(q.strip().toLowerCase(Locale.ROOT)))
              .toList();
      return Map.of(
          "items",
          all.stream().skip((long) page * 20).limit(20).toList(),
          "total",
          all.size(),
          "page",
          page,
          "size",
          20);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Annuaire indisponible");
    }
  }

  @GetMapping("/users/{id}/history")
  Object history(@PathVariable UUID id) {
    return Map.of(
        "events",
        jdbc.queryForList(
            "SELECT id,actor_subject,action,reason,outcome,requested_changes::text AS requested_changes,created_at FROM user_admin_log WHERE target_subject=? ORDER BY created_at DESC LIMIT 50",
            id.toString()),
        "activeMissions",
        jdbc.queryForObject(
            "SELECT count(*) FROM missions m WHERE deleted_at IS NULL AND status <> 'CLOSED' AND (owner_subject=? OR validator_subject=? OR EXISTS(SELECT 1 FROM mission_assignments a WHERE a.mission_id=m.id AND a.subject=?))",
            Integer.class,
            id.toString(),
            id.toString(),
            id.toString()));
  }

  @PostMapping("/users")
  ResponseEntity<?> create(@Valid @RequestBody Input input, @AuthenticationPrincipal Jwt jwt) {
    check(input);
    if (input.username() == null
        || input.username().toLowerCase(Locale.ROOT).startsWith("service-account-"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    UUID operation =
        begin(
            jwt, "username:" + input.username().toLowerCase(Locale.ROOT), "CREATE", input.reason());
    String id = null;
    try {
      recordChanges(operation, input);
      String token = directory.token(), password = password();
      var body = new HashMap<String, Object>(profile(input));
      body.put("username", input.username());
      body.put(
          "credentials", List.of(Map.of("type", "password", "value", password, "temporary", true)));
      id = directory.create(body, token);
      jdbc.update("UPDATE user_admin_log SET target_subject=? WHERE id=?", id, operation);
      directory.setRoles(id, input.roles(), token);
      finish(operation, "SUCCESS");
      return ResponseEntity.status(201)
          .cacheControl(CacheControl.noStore())
          .body(Map.of("id", id, "temporaryPassword", password));
    } catch (RuntimeException e) {
      finish(operation, "UNCERTAIN");
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Création non confirmée. Consultez l’annuaire et le journal avant de réessayer.");
    }
  }

  @PutMapping("/users/{id}")
  Object update(
      @PathVariable UUID id, @Valid @RequestBody Input input, @AuthenticationPrincipal Jwt jwt) {
    check(input);
    if (id.toString().equals(jwt.getSubject())
        && (!input.enabled() || !input.roles().contains("SUPER_ADMIN")))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Votre propre accès superadmin doit être conservé");
    UUID operation = begin(jwt, id.toString(), "UPDATE", input.reason());
    try {
      recordChanges(operation, input);
      String token = directory.token();
      directory.user(id.toString(), token);
      revoke(id);
      directory.logout(id.toString(), token);
      directory.update(id.toString(), profile(input), token);
      directory.setRoles(id.toString(), input.roles(), token);
      finish(operation, "SUCCESS");
      return Map.of("id", id, "status", "SUCCESS");
    } catch (RuntimeException e) {
      finish(operation, "UNCERTAIN");
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Modification non confirmée. Rechargez le compte et consultez son journal.");
    }
  }

  @PostMapping("/users/{id}/reset-password")
  Object reset(
      @PathVariable UUID id, @Valid @RequestBody Reason input, @AuthenticationPrincipal Jwt jwt) {
    UUID operation = begin(jwt, id.toString(), "RESET_PASSWORD", input.reason());
    try {
      String token = directory.token(), password = password();
      directory.user(id.toString(), token);
      revoke(id);
      directory.logout(id.toString(), token);
      directory.reset(id.toString(), password, token);
      finish(operation, "SUCCESS");
      return Map.of("id", id, "temporaryPassword", password);
    } catch (RuntimeException e) {
      finish(operation, "UNCERTAIN");
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Réinitialisation non confirmée. Vérifiez le journal avant de réessayer.");
    }
  }

  void revoke(UUID id) {
    jdbc.update(
        "INSERT INTO account_revocations(subject,revoked_before) VALUES (?,?) ON CONFLICT(subject) DO UPDATE SET revoked_before=EXCLUDED.revoked_before",
        id.toString(),
        java.time.Instant.now().getEpochSecond());
  }

  private Map<String, Object> profile(Input in) {
    return Map.of(
        "firstName",
        in.firstName().strip(),
        "lastName",
        in.lastName().strip(),
        "email",
        in.email().strip(),
        "emailVerified",
        false,
        "enabled",
        in.enabled());
  }

  private void check(Input in) {
    if (!UserAdministration.ROLES.containsAll(in.roles()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rôle applicatif invalide");
  }

  private void recordChanges(UUID operation, Input input) {
    var fields = new HashMap<String,Object>(profile(input));
    fields.put("roles", new TreeSet<>(input.roles()));
    if(input.username()!=null) fields.put("username",input.username());
    try {
      jdbc.update("UPDATE user_admin_log SET requested_changes=?::jsonb WHERE id=?",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fields),operation);
    } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid administrative profile"); }
  }

  private UUID begin(Jwt jwt, String target, String action, String reason) {
    UUID id = UUID.randomUUID();
    try {
      jdbc.update(
          "INSERT INTO user_admin_log(id,actor_subject,target_subject,action,reason,outcome) VALUES (?,?,?,?,?,'PENDING')",
          id,
          jwt.getSubject(),
          target,
          action,
          reason.strip());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Une opération est déjà en cours sur ce compte");
    }
    return id;
  }

  private void finish(UUID id, String outcome) {
    jdbc.update("UPDATE user_admin_log SET outcome=?,completed_at=now() WHERE id=?", outcome, id);
  }

  private String password() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return "Aa1!" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
