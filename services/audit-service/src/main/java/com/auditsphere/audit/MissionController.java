package com.auditsphere.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
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
  private final AssignmentOutbox outbox;
  private final ReviewOutbox reviewOutbox;
  private final org.springframework.transaction.support.TransactionTemplate tx;

  MissionController(
      MissionRepository repository,
      AuditorDirectory directory,
      AssignmentOutbox outbox,
      ReviewOutbox reviewOutbox,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.directory = directory;
    this.outbox = outbox;
    this.reviewOutbox = reviewOutbox;
    this.tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    this.tx.setTimeout(5);
    this.repository = repository;
  }

  record CreateMission(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 4000) String description,
      @NotBlank @Size(max = 2000) String perimeter,
      @NotNull @Size(max = 100) Set<@NotBlank @Size(max = 255) String> assigneeSubjects,
      LocalDate plannedStartDate,
      LocalDate plannedEndDate) {}

  record MissionView(
      UUID id,
      long version,
      String title,
      String description,
      String perimeter,
      String status,
      String ownerSubject,
      Set<String> assigneeSubjects,
      Instant createdAt,
      LocalDate plannedStartDate,
      LocalDate plannedEndDate,
      Instant startedAt,
      Instant closedAt,
      String validatorSubject,
      Instant submittedAt,
      Instant reviewedAt,
      String reviewedBy,
      String reviewDecision,
      String reviewComment) {
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
          m.createdAt,
          m.plannedStartDate,
          m.plannedEndDate,
          m.startedAt,
          m.closedAt,
          m.validatorSubject,
          m.submittedAt,
          m.reviewedAt,
          m.reviewedBy,
          m.reviewDecision,
          m.reviewComment);
    }
  }

  @PostMapping
  ResponseEntity<MissionView> create(
      @Valid @RequestBody CreateMission body, @AuthenticationPrincipal Jwt jwt) {
    validateDates(body.plannedStartDate(), body.plannedEndDate(), false);
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
          m.plannedStartDate = body.plannedStartDate();
          m.plannedEndDate = body.plannedEndDate();
          m.assigneeSubjects.addAll(body.assigneeSubjects());
          repository.saveAndFlush(m);
          outbox.append(m, m.assigneeSubjects);
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
            isValidator(jwt),
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
    if (!m.ownerSubject.equals(jwt.getSubject())
        && !m.assigneeSubjects.contains(jwt.getSubject())
        && !(isValidator(jwt) && jwt.getSubject().equals(m.validatorSubject)))
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
          if (Set.of("PENDING_REVIEW", "APPROVED").contains(m.status))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
          var added = new HashSet<>(body.assigneeSubjects());
          added.removeAll(m.assigneeSubjects);
          m.assigneeSubjects.clear();
          m.assigneeSubjects.addAll(body.assigneeSubjects());
          // Force a version increment even when the replacement set is unchanged.
          m.assignmentRevision = UUID.randomUUID().toString();
          repository.saveAndFlush(m);
          outbox.append(m, added);

          return MissionView.of(m);
        });
  }

  private void checkOwner(Mission m, Jwt jwt, long version) {
    if (!m.ownerSubject.equals(jwt.getSubject()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (m.version != version) throw new ResponseStatusException(HttpStatus.CONFLICT);
  }

  record Schedule(
      @NotNull @PositiveOrZero Long version,
      @NotNull LocalDate plannedStartDate,
      @NotNull LocalDate plannedEndDate) {}

  record Transition(
      @NotNull @PositiveOrZero Long version,
      @NotNull @Pattern(regexp = "IN_PROGRESS|CLOSED") String status) {}

  private void validateDates(LocalDate start, LocalDate end, boolean required) {
    if (!required && start == null && end == null) return;
    if (start == null
        || end == null
        || end.isBefore(start)
        || start.getYear() < 1
        || end.getYear() > 9999) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }

  @PutMapping("/{id}/schedule")
  MissionView schedule(
      @PathVariable UUID id, @Valid @RequestBody Schedule body, @AuthenticationPrincipal Jwt jwt) {
    return tx.execute(
        state -> {
          Mission m =
              repository
                  .findForUpdate(id)
                  .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
          checkOwner(m, jwt, body.version());
          if (!Set.of("DRAFT", "IN_PROGRESS").contains(m.status))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
          validateDates(body.plannedStartDate(), body.plannedEndDate(), true);
          m.plannedStartDate = body.plannedStartDate();
          m.plannedEndDate = body.plannedEndDate();
          repository.saveAndFlush(m);
          return MissionView.of(m);
        });
  }

  @PutMapping("/{id}/status")
  MissionView transition(
      @PathVariable UUID id,
      @Valid @RequestBody Transition body,
      @AuthenticationPrincipal Jwt jwt) {
    return tx.execute(
        state -> {
          Mission m =
              repository
                  .findForUpdate(id)
                  .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
          checkOwner(m, jwt, body.version());
          if (m.status.equals("DRAFT") && body.status().equals("IN_PROGRESS")) {
            if (m.plannedStartDate == null || m.plannedEndDate == null)
              throw new ResponseStatusException(HttpStatus.CONFLICT);
            m.status = "IN_PROGRESS";
            m.startedAt = Instant.now();
          } else if (m.status.equals("APPROVED") && body.status().equals("CLOSED")) {
            m.status = "CLOSED";
            m.closedAt = Instant.now();
          } else throw new ResponseStatusException(HttpStatus.CONFLICT);
          repository.saveAndFlush(m);
          return MissionView.of(m);
        });
  }

  record SubmitReview(@NotNull @PositiveOrZero Long version) {}

  record Review(
      @NotNull @PositiveOrZero Long version,
      @NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision,
      @NotBlank @Size(max = 2000) String comment) {}

  private Mission reviewMission(UUID id) {
    return repository
        .findForUpdate(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private void checkSubmission(Mission m, Jwt jwt, long version) {
    checkOwner(m, jwt, version);
    if (!m.status.equals("IN_PROGRESS")
        || m.validatorSubject == null
        || m.ownerSubject.equals(m.validatorSubject))
      throw new ResponseStatusException(HttpStatus.CONFLICT);
  }

  @PutMapping("/{id}/submission")
  MissionView submit(
      @PathVariable UUID id,
      @Valid @RequestBody SubmitReview body,
      @AuthenticationPrincipal Jwt jwt) {
    String validator =
        tx.execute(
            state -> {
              Mission m =
                  repository
                      .findById(id)
                      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
              checkSubmission(m, jwt, body.version());
              return m.validatorSubject;
            });
    directory.validateValidator(validator);
    return tx.execute(
        state -> {
          Mission m = reviewMission(id);
          checkSubmission(m, jwt, body.version());
          m.status = "PENDING_REVIEW";
          m.reviewCycleId = UUID.randomUUID();
          m.submittedAt = Instant.now();
          m.reviewedAt = null;
          m.reviewedBy = null;
          m.reviewDecision = null;
          m.reviewComment = null;
          repository.saveAndFlush(m);
          reviewOutbox.append(m, "START", null);
          return MissionView.of(m);
        });
  }

  @PutMapping("/{id}/review")
  MissionView review(
      @PathVariable UUID id, @Valid @RequestBody Review body, @AuthenticationPrincipal Jwt jwt) {
    return tx.execute(
        state -> {
          Mission m = reviewMission(id);
          if (!jwt.getSubject().equals(m.validatorSubject)
              || jwt.getSubject().equals(m.ownerSubject))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
          if (m.version != body.version() || !m.status.equals("PENDING_REVIEW"))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
          m.reviewDecision = body.decision();
          m.reviewComment = body.comment().strip();
          m.reviewedBy = jwt.getSubject();
          m.reviewedAt = Instant.now();
          m.status = body.decision().equals("APPROVED") ? "APPROVED" : "IN_PROGRESS";
          repository.saveAndFlush(m);
          reviewOutbox.append(m, "DECISION", m.reviewDecision);
          return MissionView.of(m);
        });
  }

  private boolean isValidator(Jwt jwt) {
    Map<String, Object> realm = jwt.getClaim("realm_access");
    return realm != null
        && realm.get("roles") instanceof List<?> roles
        && roles.contains("AUDIT_VALIDATOR");
  }

  @GetMapping("/validators")
  List<AuditorDirectory.Person> validators(
      @RequestParam(defaultValue = "0") int page, @AuthenticationPrincipal Jwt jwt) {
    if (page < 0 || page > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return directory.validators(page).stream()
        .filter(p -> !p.subject().equals(jwt.getSubject()))
        .toList();
  }

  record ValidatorAssignment(
      @NotNull @PositiveOrZero Long version, @Size(min = 1, max = 255) String validatorSubject) {}

  private void checkValidatorAssignment(Mission m, Jwt jwt, ValidatorAssignment body) {
    checkOwner(m, jwt, body.version());
    if (!Set.of("DRAFT", "IN_PROGRESS").contains(m.status))
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    if (m.ownerSubject.equals(body.validatorSubject()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }

  @PutMapping("/{id}/validator")
  MissionView designateValidator(
      @PathVariable UUID id,
      @Valid @RequestBody ValidatorAssignment body,
      @AuthenticationPrincipal Jwt jwt) {
    tx.executeWithoutResult(
        state ->
            checkValidatorAssignment(
                repository
                    .findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)),
                jwt,
                body));
    if (body.validatorSubject() != null) directory.validateValidator(body.validatorSubject());
    return tx.execute(
        state -> {
          Mission m =
              repository
                  .findForUpdate(id)
                  .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
          checkValidatorAssignment(m, jwt, body);
          m.validatorSubject = body.validatorSubject();
          repository.saveAndFlush(m);
          return MissionView.of(m);
        });
  }

  @GetMapping("/{id}/participants")
  List<AuditorDirectory.Person> participants(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    MissionView mission = tx.execute(status -> get(id, jwt));
    var subjects = new HashSet<>(mission.assigneeSubjects());
    subjects.add(mission.ownerSubject());
    if (mission.validatorSubject() != null) subjects.add(mission.validatorSubject());
    return directory.participants(subjects);
  }
}
