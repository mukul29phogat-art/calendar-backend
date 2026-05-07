package com.childcarewow.calendar.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

  /**
   * Finds events that double-book against the candidate. The conflict rule (architecture spec §
   * 7.3) is "same school + time overlap + (same classroom OR same organizer)".
   *
   * <p>Boundary: time overlap uses strict {@code <} on both sides ({@code e.startDt < endDt AND
   * startDt < e.endDt}), so events sharing an endpoint (A ends at 10:00, B starts at 10:00) do NOT
   * overlap.
   *
   * <p>Soft-deleted rows ({@code deletedAt IS NOT NULL}) are excluded.
   */
  @Query(
      "SELECT e FROM Event e "
          + "WHERE e.id <> :excludeId "
          + "AND e.schoolId = :schoolId "
          + "AND e.deletedAt IS NULL "
          + "AND e.startDt < :endDt "
          + "AND :startDt < e.endDt "
          + "AND ("
          + "  (:classroomId IS NOT NULL AND e.classroomId = :classroomId) "
          + "  OR (:organizerUserId IS NOT NULL AND e.organizerUserId = :organizerUserId)"
          + ")")
  List<Event> findOverlapping(
      @Param("excludeId") UUID excludeId,
      @Param("schoolId") UUID schoolId,
      @Param("startDt") OffsetDateTime startDt,
      @Param("endDt") OffsetDateTime endDt,
      @Param("classroomId") UUID classroomId,
      @Param("organizerUserId") UUID organizerUserId);
}
