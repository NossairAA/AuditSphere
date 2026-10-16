package com.auditsphere.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/missions")
class MissionController {
  private final MissionRepository repository;

  private final AuditorDirectory directory;
  private final org.springframework.transaction.support.TransactionTemplate tx;

  MissionController(
      MissionRepository repository,
      AuditorDirectory directory,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.directory = directory;
    this.tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    this.tx.setTimeout(5);
    this.repository = repository;
  }

  record CreateMission(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 4000) String description,
      @NotBlank @Size(max = 2000) String perimeter,
      @NotNull @Size(max = 100) Set<@NotBlank @Size(max = 255) String> assigneeSubjects) {}

  record MissionView(
      UUID id,
      long version,
      String title,
      String description,
      String perimeter,
      String status,
      String ownerSubject,
      Set<String> assigneeSubjects,
      Instant createdAt) {
    static MissionView of(Mission m) {
      return new MissionView(
          m.id,
          m.version,
          m.title,
          m.description,
          m.perimeter,
          m.status,
          m.ownerSubject,
          Set.copyOf(m.assigneeSubjects),
          m.createdAt);
    }
  }

  @PostMapping
  ResponseEntity<MissionView> create(
      @Valid @RequestBody CreateMission body, @AuthenticationPrincipal Jwt jwt) {
    directory.validate(body.assigneeSubjects());
    return tx.execute(
        status -> {
          Mission m = new Mission();
          m.id = UUID.randomUUID();
          m.title = body.title().strip();
          m.description = body.description().strip();
          m.perimeter = body.perimeter().strip();
          m.ownerSubject = jwt.getSubject();
          m.createdAt = Instant.now();
          m.status = "DRAFT";
          m.assigneeSubjects.addAll(body.assigneeSubjects());
          repository.saveAndFlush(m);
          return ResponseEntity.created(URI.create("/missions/" + m.id)).body(MissionView.of(m));
        });
  }

  record MissionPage(List<MissionView> items, int page, int size, long total) {}

  @GetMapping
  @Transactional(readOnly = true)
  MissionPage list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal Jwt jwt) {
    if (page < 0 || size < 1 || size > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var result =
        repository.findVisible(
            jwt.getSubject(),
            org.springframework.data.domain.PageRequest.of(
                page,
                size,
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.desc("createdAt"),
                    org.springframework.data.domain.Sort.Order.asc("id"))));
    return new MissionPage(
        result.getContent().stream().map(MissionView::of).toList(),
        page,
        size,
        result.getTotalElements());
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  MissionView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    Mission m =
        repository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!m.ownerSubject.equals(jwt.getSubject()) && !m.assigneeSubjects.contains(jwt.getSubject()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return MissionView.of(m);
  }

  record Assignments(
      @NotNull @PositiveOrZero Long version,
      @NotNull @Size(max = 100) Set<@NotBlank @Size(max = 255) String> assigneeSubjects) {}

  @GetMapping("/auditors")
  List<AuditorDirectory.Person> auditors(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return directory.auditors(page);
  }

  @PutMapping("/{id}/assignments")
  MissionView assign(
      @PathVariable UUID id,
      @Valid @RequestBody Assignments body,
      @AuthenticationPrincipal Jwt jwt) {
    // Authorize before accessing the directory, without retaining a database connection.
    tx.executeWithoutResult(
        status -> {
          Mission m =
              repository
                  .findById(id)
                  .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
          checkOwner(m, jwt, body.version());
        });
    directory.validate(body.assigneeSubjects());
    return tx.execute(
        status -> {
          Mission m =
              repository
                  .findForUpdate(id)
                  .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
          checkOwner(m, jwt, body.version());
          m.assigneeSubjects.clear();
          m.assigneeSubjects.addAll(body.assigneeSubjects());
          // Force a version increment even when the replacement set is unchanged.
          m.assignmentRevision = UUID.randomUUID().toString();
          repository.saveAndFlush(m);

          return MissionView.of(m);
        });
  }

  private void checkOwner(Mission m, Jwt jwt, long version) {
    if (!m.ownerSubject.equals(jwt.getSubject()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (m.version != version) throw new ResponseStatusException(HttpStatus.CONFLICT);
  }

  @GetMapping("/{id}/participants")
  List<AuditorDirectory.Person> participants(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    MissionView mission = tx.execute(status -> get(id, jwt));
    var subjects = new HashSet<>(mission.assigneeSubjects());
    subjects.add(mission.ownerSubject());
    return directory.participants(subjects);
  }
}
