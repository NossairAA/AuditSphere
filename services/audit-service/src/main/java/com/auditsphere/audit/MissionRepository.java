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
      "select m from Mission m where m.ownerSubject = :subject or :subject member of m.assigneeSubjects or (:validator = true and m.validatorSubject = :subject)")
  Page<Mission> findVisible(
      @Param("subject") String subject, @Param("validator") boolean validator, Pageable pageable);

  interface Title {
    UUID getId();

    String getTitle();
  }

  @Query(
      "select m.id as id, m.title as title from Mission m where m.id in :ids and (m.ownerSubject = :subject or :subject member of m.assigneeSubjects or (:validator = true and m.validatorSubject = :subject))")
  java.util.List<Title> visibleTitles(
      @Param("ids") java.util.Set<UUID> ids,
      @Param("subject") String subject,
      @Param("validator") boolean validator);
}
