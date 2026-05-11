package com.childcarewow.calendar.task;

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
 * <p>{@code assigneeUserIds} is a list to match the FE's existing wire shape — multi-assignee
 * fan-out (creating N rows for N assignees) lands in Part 8.2. Part 8.1 rejects size &gt; 1 with a
 * clear validation error so the wire contract stays stable across the two parts.
 *
 * <p>{@code status} and {@code priority} default to {@code TODO} / {@code MEDIUM} respectively when
 * omitted (matches the schema defaults set by V3 migration).
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
    TaskPriority priority) {

  /** Defaults to TODO when the client omits status. */
  public TaskStatus statusOrDefault() {
    return status == null ? TaskStatus.TODO : status;
  }

  /** Defaults to MEDIUM when the client omits priority. */
  public TaskPriority priorityOrDefault() {
    return priority == null ? TaskPriority.MEDIUM : priority;
  }
}
