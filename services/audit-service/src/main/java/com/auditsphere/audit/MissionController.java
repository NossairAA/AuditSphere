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

  MissionController(MissionRepository repository) {
    this.repository = repository;
  }

  record CreateMission(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 4000) String description,
      @NotBlank @Size(max = 2000) String perimeter,
      @NotNull @Size(max = 100) Set<@NotBlank @Size(max = 255) String> assigneeSubjects) {}

  record MissionView(
      UUID id,
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
  @Transactional
  ResponseEntity<MissionView> create(
      @Valid @RequestBody CreateMission body, @AuthenticationPrincipal Jwt jwt) {
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
  }

  record MissionPage(List<MissionView> items, int page, int size, long total) {}

  @GetMapping
  @Transactional(readOnly = true)
  MissionPage list(@RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
    if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var result = repository.findVisible(jwt.getSubject(), org.springframework.data.domain.PageRequest.of(
        page, size, org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Order.desc("createdAt"),
            org.springframework.data.domain.Sort.Order.asc("id"))));
    return new MissionPage(result.getContent().stream().map(MissionView::of).toList(), page, size, result.getTotalElements());
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
}
