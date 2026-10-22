package com.auditsphere.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MissionRepository extends JpaRepository<Mission, UUID> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Mission m where m.id = :id")
  java.util.Optional<Mission> findForUpdate(@Param("id") UUID id);

  @Query(
      "select m from Mission m where m.deletedAt is null and (:admin = true or m.ownerSubject ="
          + " :subject or :subject member of m.assigneeSubjects or (:validator = true and"
          + " m.validatorSubject = :subject))"
          + " and (:search = '' or lower(m.title) like :search escape '!' or lower(m.perimeter) like :search escape '!')"
          + " and (:status = '' or m.status = :status) and (:owner = '' or m.ownerSubject = :owner)"
          + " and (:late = false or (m.plannedEndDate < :today and m.status <> 'CLOSED'))")
  Page<Mission> findVisible(
      @Param("subject") String subject,
      @Param("validator") boolean validator,
      @Param("admin") boolean admin,
      @Param("search") String search,
      @Param("status") String status,
      @Param("owner") String owner,
      @Param("late") boolean late,
      @Param("today") java.time.LocalDate today,
      Pageable pageable);

  @Query(
      "select distinct m.ownerSubject from Mission m where m.deletedAt is null and (:admin = true or m.ownerSubject = :subject or :subject member of m.assigneeSubjects or (:validator = true and m.validatorSubject = :subject))")
  java.util.Set<String> visibleOwners(
      @Param("subject") String subject,
      @Param("validator") boolean validator,
      @Param("admin") boolean admin);

  interface Title {
    UUID getId();

    String getTitle();
  }

  @Query(
      "select m.id as id, m.title as title from Mission m where m.deletedAt is null and m.id in"
          + " :ids and (:admin = true or m.ownerSubject = :subject or :subject member of"
          + " m.assigneeSubjects or (:validator = true and m.validatorSubject = :subject))")
  java.util.List<Title> visibleTitles(
      @Param("ids") java.util.Set<UUID> ids,
      @Param("subject") String subject,
      @Param("validator") boolean validator,
      @Param("admin") boolean admin);
}
