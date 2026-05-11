package com.childcarewow.calendar.task;

import com.childcarewow.calendar.recurrence.OccurrenceSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response shape for tasks on the calendar feed (Part 7.2). Matches the frontend's {@code Task}
 * type at {@code src/types/index.ts:76}. The full task write surface (POST / PUT / DELETE) lands in
 * Series 8 and may extend this record with derived fields (overdue flag, soft-flag list) — for 7.2
 * the read-side shape is sufficient.
 *
 * <p>Optional fields are dropped from the JSON via {@code @JsonInclude(NON_EMPTY)} to match the
 * prototype's optional-property convention.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record TaskView(
    UUID id,
    UUID schoolId,
    UUID orgId,
    UUID classroomId,
    String title,
    String description,
    UUID assigneeUserId,
    LocalDate dueDate,
    LocalTime dueTime,
    TaskStatus status,
    TaskPriority priority,
    UUID recurrenceId,
    UUID parentTaskGroupId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  /** Direct projection for a non-recurring task (or the parent of a recurring series). */
  public static TaskView fromEntity(Task t) {
    return new TaskView(
        t.getId(),
        t.getSchoolId(),
        t.getOrgId(),
        t.getClassroomId(),
        t.getTitle(),
        t.getDescription(),
        t.getAssigneeUserId(),
        t.getDueDate(),
        t.getDueTime(),
        t.getStatus(),
        t.getPriority(),
        t.getRecurrenceId(),
        t.getParentTaskGroupId(),
        t.getCreatedAt(),
        t.getUpdatedAt());
  }

  /**
   * Per-occurrence projection for a recurring task. The parent task's {@code id} is reused (the
   * occurrence is not a separate row), but {@code dueDate}, {@code title}, {@code dueTime}, and
   * {@code status} come from the {@link OccurrenceSnapshot} so per-occurrence overrides apply.
   * Everything else (description, assignee, priority, recurrenceId, audit columns) inherits from
   * the parent task.
   */
  public static TaskView fromOccurrence(Task t, OccurrenceSnapshot snap) {
    return new TaskView(
        t.getId(),
        t.getSchoolId(),
        t.getOrgId(),
        t.getClassroomId(),
        snap.title(),
        t.getDescription(),
        t.getAssigneeUserId(),
        snap.date(),
        snap.dueTime(),
        snap.status(),
        t.getPriority(),
        t.getRecurrenceId(),
        t.getParentTaskGroupId(),
        t.getCreatedAt(),
        t.getUpdatedAt());
  }
}
