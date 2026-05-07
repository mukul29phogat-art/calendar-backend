package com.childcarewow.calendar.recurrence;

import com.childcarewow.calendar.task.TaskStatus;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Read-only projection of one occurrence of a recurring task with any per-occurrence override
 * applied. {@code title}, {@code dueTime}, and {@code status} fall through to the parent task's
 * values if the override doesn't set them. Skipped occurrences are filtered out before projection
 * runs.
 */
public record OccurrenceSnapshot(
    LocalDate date, String title, LocalTime dueTime, TaskStatus status) {}
