package com.childcarewow.calendar.recurrence;

import java.time.LocalDate;
import java.util.List;

/**
 * Output of {@link RecurrenceService#expand}. {@code occurrences} are the resolved instance dates
 * within the requested window, sorted ascending. {@code truncated} is {@code true} iff the rule +
 * window combination would have yielded more than {@link RecurrenceService#MAX_OCCURRENCES} dates;
 * the list is then capped at exactly that many. Callers must surface the {@code truncated} flag to
 * the API response (architecture spec §15) so the FE can warn the user and offer pagination once
 * Series 4 implements the calendar window endpoint.
 */
public record ExpansionResult(List<LocalDate> occurrences, boolean truncated) {}
