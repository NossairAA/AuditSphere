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
  private final MissionActionLog actionLog;
  private final org.springframework.transaction.support.TransactionTemplate tx;

  MissionController(
      MissionRepository repository,
      AuditorDirectory directory,
      AssignmentOutbox outbox,
      ReviewOutbox reviewOutbox,
      MissionActionLog actionLog,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.directory = directory;
    this.outbox = outbox;
    this.reviewOutbox = reviewOutbox;
    this.actionLog = actionLog;
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
      String reviewComment,
      Instant withdrawnAt,
      String withdrawalReason,
      String adminOverrideAction,
      String adminOverrideReason,
      Instant adminOverrideAt) {
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
          m.reviewComment,
          m.withdrawnAt,
          m.withdrawalReason,
          m.adminOverrideAction,
          m.adminOverrideReason,
          m.adminOverrideAt);
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
          if (isAdmin(jwt))
            actionLog.admin(
                m,
                jwt.getSubject(),
                "ADMIN_CHANGE",
                "CREATE",
                "Modification administrative",
                null,
                m.status);
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
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String owner,
      @RequestParam(defaultValue = "false") boolean overdue,
      @RequestParam(defaultValue = "created") String sort,
      @AuthenticationPrincipal Jwt jwt) {
    if (page < 0
        || size < 1
        || size > 100
        || q.length() > 200
        || owner.length() > 255
        || !Set.of("", "DRAFT", "IN_PROGRESS", "PENDING_REVIEW", "APPROVED", "CLOSED")
            .contains(status)
        || !Set.of("created", "title", "end").contains(sort))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var result =
        repository.findVisible(
            jwt.getSubject(),
            isValidator(jwt),
            isAdmin(jwt),
            q.isBlank()
                ? ""
                : "%"
                    + q.strip()
                        .toLowerCase(java.util.Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_")
                    + "%",
            status,
            owner,
            overdue,
            java.time.LocalDate.now(java.time.ZoneOffset.UTC),
            org.springframework.data.domain.PageRequest.of(
                page,
                size,
                org.springframework.data.domain.Sort.by(
                    sort.equals("created")
                        ? org.springframework.data.domain.Sort.Order.desc("createdAt")
                        : org.springframework.data.domain.Sort.Order.asc(
                            sort.equals("title") ? "title" : "plannedEndDate"),
                    org.springframework.data.domain.Sort.Order.asc("id"))));
    return new MissionPage(
        result.getContent().stream().map(MissionView::of).toList(),
        page,
        size,
        result.getTotalElements());
  }

  @GetMapping("/owners")
  List<AuditorDirectory.Person> owners(@AuthenticationPrincipal Jwt jwt) {
    return directory.participants(
        repository.visibleOwners(jwt.getSubject(), isValidator(jwt), isAdmin(jwt)));
  }

  @GetMapping("/titles")
  @Transactional(readOnly = true)
  Map<UUID, String> titles(@RequestParam Set<UUID> ids, @AuthenticationPrincipal Jwt jwt) {
    if (ids.isEmpty() || ids.size() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return repository.visibleTitles(ids, jwt.getSubject(), isValidator(jwt), isAdmin(jwt)).stream()
        .collect(
            java.util.stream.Collectors.toMap(
                MissionRepository.Title::getId, MissionRepository.Title::getTitle));
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  MissionView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    Mission m =
        repository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (m.deletedAt != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (!isAdmin(jwt)
        && !m.ownerSubject.equals(jwt.getSubject())
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
          if (isAdmin(jwt))
            actionLog.admin(
                m,
                jwt.getSubject(),
                "ADMIN_CHANGE",
                "ASSIGNMENTS",
                "Modification administrative",
                null,
                m.status);
          outbox.append(m, added);

          return MissionView.of(m);
        });
  }

  private void checkOwner(Mission m, Jwt jwt, long version) {
    if (m.deletedAt != null || (!isAdmin(jwt) && !m.ownerSubject.equals(jwt.getSubject())))
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
          if (isAdmin(jwt))
            actionLog.admin(
                m,
                jwt.getSubject(),
                "ADMIN_CHANGE",
                "SCHEDULE",
                "Modification administrative",
                null,
                m.status);
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
          if (isAdmin(jwt))
            actionLog.admin(
                m,
                jwt.getSubject(),
                "ADMIN_CHANGE",
                "STATUS",
                "Modification administrative",
                null,
                m.status);
          return MissionView.of(m);
        });
  }

  record ReasonedAction(
      @NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 2000) String reason) {}

  @DeleteMapping("/{id}")
  ResponseEntity<Void> deleteDraft(
      @PathVariable UUID id,
      @Valid @RequestBody ReasonedAction body,
      @AuthenticationPrincipal Jwt jwt) {
    return tx.execute(
        state -> {
          Mission m = reviewMission(id);
          checkOwner(m, jwt, body.version());
          if (!m.status.equals("DRAFT")) throw new ResponseStatusException(HttpStatus.CONFLICT);
          actionLog.append(m, jwt.getSubject(), "DRAFT_DELETED", body.reason().strip());
          actionLog.removeDraftEvents(id);
          repository.delete(m);
          repository.flush();
          return ResponseEntity.noContent().build();
        });
  }

  @PutMapping("/{id}/withdrawal")
  MissionView withdraw(
      @PathVariable UUID id,
      @Valid @RequestBody ReasonedAction body,
      @AuthenticationPrincipal Jwt jwt) {
    return tx.execute(
        state -> {
          Mission m = reviewMission(id);
          checkOwner(m, jwt, body.version());
          if (!m.status.equals("PENDING_REVIEW"))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
          actionLog.append(m, jwt.getSubject(), "REVIEW_WITHDRAWN", body.reason().strip());
          reviewOutbox.append(m, "DECISION", "WITHDRAWN");
          m.status = "IN_PROGRESS";
          m.withdrawnAt = Instant.now();
          m.withdrawalReason = body.reason().strip();
          repository.saveAndFlush(m);
          if (isAdmin(jwt))
            actionLog.admin(
                m,
                jwt.getSubject(),
                "ADMIN_CHANGE",
                "WITHDRAWAL",
                "Modification administrative",
                null,
                m.status);
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
        .filter(m -> m.deletedAt == null)
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
          if (isAdmin(jwt))
            actionLog.admin(
                m,
                jwt.getSubject(),
                "ADMIN_CHANGE",
                "SUBMISSION",
                "Modification administrative",
                null,
                m.status);
          reviewOutbox.append(m, "START", null);
          outbox.requestReview(m);
          return MissionView.of(m);
        });
  }

  @PutMapping("/{id}/review")
  MissionView review(
      @PathVariable UUID id, @Valid @RequestBody Review body, @AuthenticationPrincipal Jwt jwt) {
    return tx.execute(
        state -> {
          Mission m = reviewMission(id);
          if ((!jwt.getSubject().equals(m.validatorSubject))
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
          if (isAdmin(jwt))
            actionLog.admin(
                m,
                jwt.getSubject(),
                "ADMIN_CHANGE",
                "REVIEW",
                "Modification administrative",
                null,
                m.status);
          reviewOutbox.append(m, "DECISION", m.reviewDecision);
          return MissionView.of(m);
        });
  }

  private boolean isAdmin(Jwt jwt) {
    Map<String, Object> realm = jwt.getClaim("realm_access");
    return realm != null
        && realm.get("roles") instanceof List<?> roles
        && roles.contains("SUPER_ADMIN");
  }

  record AdminOverride(
      @NotNull @PositiveOrZero Long version,
      @NotBlank @Pattern(regexp = "APPROVE|CLOSE|REOPEN|DELETE") String action,
      @NotBlank @Size(max = 2000) String reason) {}

  @PutMapping("/{id}/override")
  ResponseEntity<?> override(
      @PathVariable UUID id,
      @Valid @RequestBody AdminOverride body,
      @AuthenticationPrincipal Jwt jwt) {
    return tx.execute(
        state -> {
          Mission m = reviewMission(id);
          if (m.deletedAt != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
          if (m.version != body.version()) throw new ResponseStatusException(HttpStatus.CONFLICT);
          String before = m.status;
          boolean pending = before.equals("PENDING_REVIEW");
          String after =
              switch (body.action()) {
                case "APPROVE" -> "APPROVED";
                case "CLOSE" -> "CLOSED";
                case "REOPEN" -> "IN_PROGRESS";
                default -> "DELETED";
              };
          if (!body.action().equals("DELETE") && (before.equals("DRAFT") || before.equals(after)))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
          if (body.action().equals("APPROVE") && before.equals("CLOSED"))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
          m.adminOverrideId =
              actionLog.admin(
                  m,
                  jwt.getSubject(),
                  "ADMIN_OVERRIDE",
                  body.action(),
                  body.reason().strip(),
                  before,
                  after);
          m.adminOverrideAction = body.action();
          m.adminOverrideReason = body.reason().strip();
          m.adminOverrideAt = Instant.now();
          if (pending)
            reviewOutbox.append(
                m, "DECISION", body.action().equals("APPROVE") ? "APPROVED" : "WITHDRAWN");
          switch (body.action()) {
            case "APPROVE" -> {
              m.adminApprovalId = m.adminOverrideId;
              m.status = "APPROVED";
              m.reviewDecision = "APPROVED";
              m.reviewComment = body.reason().strip();
              m.reviewedAt = Instant.now();
              m.reviewedBy = jwt.getSubject();
            }
            case "CLOSE" -> {
              m.status = "CLOSED";
              m.closedAt = Instant.now();
            }
            case "REOPEN" -> {
              m.adminApprovalId = null;
              m.status = "IN_PROGRESS";
              m.closedAt = null;
              m.reviewDecision = null;
              m.reviewComment = null;
              m.reviewedAt = null;
              m.reviewedBy = null;
            }
            case "DELETE" -> {
              m.deletedAt = Instant.now();
              actionLog.removeDraftEvents(m.id);
            }
          }
          repository.saveAndFlush(m);
          return body.action().equals("DELETE")
              ? ResponseEntity.noContent().build()
              : ResponseEntity.ok(MissionView.of(m));
        });
  }

  @GetMapping("/{id}/action-log")
  java.util.List<java.util.Map<String, Object>> actionLog(@PathVariable UUID id) {
    return actionLog.list(id);
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
          if (isAdmin(jwt))
            actionLog.admin(
                m,
                jwt.getSubject(),
                "ADMIN_CHANGE",
                "VALIDATOR",
                "Modification administrative",
                null,
                m.status);
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
