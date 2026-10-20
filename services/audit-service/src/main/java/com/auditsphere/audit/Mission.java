package com.auditsphere.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "missions")
class Mission {
  @Id UUID id;

  @Version Long version;

  String assignmentRevision;

  @Column(length = 255)
  String validatorSubject;

  java.time.LocalDate plannedStartDate;
  java.time.LocalDate plannedEndDate;
  Instant startedAt;
  Instant closedAt;
  UUID reviewCycleId;
  Instant submittedAt;
  Instant withdrawnAt;

  @Column(length = 2000)
  String withdrawalReason;

  Instant deletedAt;
  UUID adminOverrideId;
  UUID adminApprovalId;
  String adminOverrideAction;

  @jakarta.persistence.Column(length = 2000)
  String adminOverrideReason;

  Instant adminOverrideAt;

  Instant reviewedAt;
  String reviewedBy;
  String reviewDecision;

  @Column(length = 2000)
  String reviewComment;

  @Column(nullable = false, length = 200)
  String title;

  @Column(nullable = false, length = 4000)
  String description;

  @Column(nullable = false, length = 2000)
  String perimeter;

  @Column(nullable = false)
  String ownerSubject;

  @Column(nullable = false)
  Instant createdAt;

  @Column(nullable = false)
  String status;

  @ElementCollection
  @CollectionTable(name = "mission_assignments", joinColumns = @JoinColumn(name = "mission_id"))
  @Column(name = "subject", nullable = false)
  Set<String> assigneeSubjects = new HashSet<>();

  protected Mission() {}
}
