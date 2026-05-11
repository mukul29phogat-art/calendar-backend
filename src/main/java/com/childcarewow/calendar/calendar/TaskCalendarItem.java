package com.childcarewow.calendar.calendar;

import com.childcarewow.calendar.task.TaskView;
import java.time.LocalDate;

/**
 * Calendar entry wrapping a {@link TaskView}. Wire shape: {@code
 * {"kind":"task","date":"YYYY-MM-DD","data":{...TaskView...}}}.
 *
 * <p>For non-recurring tasks the {@code date} equals {@code data.dueDate} and {@code data} is the
 * task entity projected as-is. For recurring tasks, one item is emitted per expanded occurrence;
 * {@code date} is the occurrence date and {@code data.dueDate} / {@code data.title} / {@code
 * data.dueTime} / {@code data.status} reflect any {@code task_instance_overrides} for that date.
 * The parent task's {@code id} is reused across all its occurrences — the FE keys items by {@code
 * (id, date)} when an occurrence-level identity is needed.
 */
public record TaskCalendarItem(LocalDate date, TaskView data) implements CalendarItem {}
