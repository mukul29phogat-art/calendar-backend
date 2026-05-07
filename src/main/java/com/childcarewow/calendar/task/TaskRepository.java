package com.childcarewow.calendar.task;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID> {

  /**
   * Same-school + same-assignee + same-due-date + non-DONE + non-deleted candidate tasks for the
   * task-overlap soft-flag rule (architecture spec § 7.3). The dueTime ±120 min check happens in
   * the service layer because expressing it cleanly in JPQL is awkward (cross-day shifts, null
   * handling). Result sets are normally tiny (one assignee per day).
   */
  @Query(
      "SELECT t FROM Task t "
          + "WHERE t.id <> :excludeId "
          + "AND t.schoolId = :schoolId "
          + "AND t.assigneeUserId = :assigneeUserId "
          + "AND t.dueDate = :dueDate "
          + "AND t.status <> com.childcarewow.calendar.task.TaskStatus.DONE "
          + "AND t.deletedAt IS NULL")
  List<Task> findOverlapCandidates(
      @Param("excludeId") UUID excludeId,
      @Param("schoolId") UUID schoolId,
      @Param("assigneeUserId") UUID assigneeUserId,
      @Param("dueDate") LocalDate dueDate);

  /** Lookup for the holiday-paint hook: every non-deleted task on a given (school, date). */
  List<Task> findBySchoolIdAndDueDateAndDeletedAtIsNull(UUID schoolId, LocalDate dueDate);
}
