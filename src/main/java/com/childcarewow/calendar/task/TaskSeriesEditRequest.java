package com.childcarewow.calendar.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request body for {@code PUT /api/v1/tasks/{id}/series}. Carries the user's edit choice + the
 * occurrence-date the dialog was opened on + the fields needed by each branch.
 *
 * <p><b>JUST_THIS</b> (Part 9.3): {@code title}, {@code dueTime}, {@code status}, {@code skipped}
 * map straight to a {@link TaskInstanceOverride} row keyed on {@code (taskId, occurrenceDate)}. Any
 * field left null means "no override for this attribute" — the read path ({@code
 * RecurrenceService.projectFor}) falls back to the parent task's value. {@code description}, {@code
 * priority}, {@code recurrence} are ignored on this branch.
 *
 * <p><b>THIS_AND_FOLLOWING</b> (Part 9.4): all of {@code title}, {@code description}, {@code
 * dueTime}, {@code status}, {@code priority}, {@code recurrence} apply to the new task created at
 * the split date ({@code occurrenceDate}). {@code skipped} is ignored.
 *
 * <p><b>ENTIRE_SERIES</b> (Part 9.5) — reuses the same DTO. {@code skipped} is ignored.
 */
public record TaskSeriesEditRequest(
    @NotNull EditChoice choice,
    @NotNull LocalDate occurrenceDate,
    String title,
    String description,
    LocalTime dueTime,
    TaskStatus status,
    TaskPriority priority,
    Boolean skipped,
    @Valid CreateTaskRequest.RecurrenceSpec recurrence) {

  /**
   * Back-compat constructor for Part 9.3 JUST_THIS callers that pre-date 9.4. Defaults {@code
   * description}/{@code priority}/{@code recurrence} to null.
   */
  public TaskSeriesEditRequest(
      EditChoice choice,
      LocalDate occurrenceDate,
      String title,
      LocalTime dueTime,
      TaskStatus status,
      Boolean skipped) {
    this(choice, occurrenceDate, title, null, dueTime, status, null, skipped, null);
  }
}
