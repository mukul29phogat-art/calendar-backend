package com.childcarewow.calendar.task;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request body for {@code PUT /api/v1/tasks/{id}/series}. Carries the user's edit choice + the
 * occurrence-date the dialog was opened on + the optional per-occurrence override fields.
 *
 * <p>Part 9.3 implements {@link EditChoice#JUST_THIS}: the {@code title} / {@code dueTime} / {@code
 * status} / {@code skipped} fields all map straight to a {@link TaskInstanceOverride} row keyed on
 * {@code (taskId, occurrenceDate)}. Any field left null means "no override for this attribute" —
 * the read path ({@code RecurrenceService.projectFor}) falls back to the parent task's value.
 *
 * <p>{@code THIS_AND_FOLLOWING} (Part 9.4) and {@code ENTIRE_SERIES} (Part 9.5) reuse this DTO and
 * lift the corresponding handlers in {@code TaskService.applySeriesEdit}.
 */
public record TaskSeriesEditRequest(
    @NotNull EditChoice choice,
    @NotNull LocalDate occurrenceDate,
    String title,
    LocalTime dueTime,
    TaskStatus status,
    Boolean skipped) {}
