package com.childcarewow.calendar.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/tasks}. Mirrors the prototype's {@code
 * tasksService.ts:171-227} validation rules.
 *
 * <p>{@code assigneeUserIds} is a list to match the FE's wire shape (multi-assignee fan-out is Part
 * 8.2). {@code status} and {@code priority} default to {@code TODO} / {@code MEDIUM} when omitted
 * (matches V3 schema defaults).
 *
 * <p>{@code recurrence} is optional. When present, Part 9.1 creates a {@link RecurrenceRule}
 * alongside the task and stamps {@code task.recurrenceId}. Part 9.1 only supports recurrence on
 * single-assignee requests; multi-assignee + recurrence lifts to Part 9.2 with independent rules
 * per row (per locked decision D9).
 */
public record CreateTaskRequest(
    @NotBlank @Size(max = 120) String title,
    String description,
    @NotNull UUID schoolId,
    UUID classroomId,
    @NotEmpty List<UUID> assigneeUserIds,
    @NotNull LocalDate dueDate,
    LocalTime dueTime,
    TaskStatus status,
    TaskPriority priority,
    @Valid RecurrenceSpec recurrence) {

  /**
   * Back-compat constructor for callers that pre-date Part 9.1's recurrence support. Defaults
   * {@code recurrence} to {@code null} (non-recurring task).
   */
  public CreateTaskRequest(
      String title,
      String description,
      UUID schoolId,
      UUID classroomId,
      List<UUID> assigneeUserIds,
      LocalDate dueDate,
      LocalTime dueTime,
      TaskStatus status,
      TaskPriority priority) {
    this(
        title,
        description,
        schoolId,
        classroomId,
        assigneeUserIds,
        dueDate,
        dueTime,
        status,
        priority,
        null);
  }

  /** Defaults to TODO when the client omits status. */
  public TaskStatus statusOrDefault() {
    return status == null ? TaskStatus.TODO : status;
  }

  /** Defaults to MEDIUM when the client omits priority. */
  public TaskPriority priorityOrDefault() {
    return priority == null ? TaskPriority.MEDIUM : priority;
  }

  /**
   * Server-side shape of the FE prototype's "Repeat …" form. The fine-grained validation lives in
   * {@link com.childcarewow.calendar.recurrence.RecurrenceService#create} — cycle-specific required
   * fields ({@code dueDayOfWeek} for WEEKLY, {@code dueDayOfMonth} for MONTHLY) and the {@code
   * untilDate} range check happen there, so the record's bean-validation is intentionally loose.
   */
  public record RecurrenceSpec(
      @NotNull RecurCycle cycle,
      Short dueDayOfWeek,
      Short dueDayOfMonth,
      LocalTime dueTime,
      @NotNull LocalDate untilDate) {}
}
