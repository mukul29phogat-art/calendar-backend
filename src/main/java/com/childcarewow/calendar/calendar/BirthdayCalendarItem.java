package com.childcarewow.calendar.calendar;

import java.time.LocalDate;

/**
 * Placeholder for the {@code "kind":"birthday"} branch of the calendar feed. Part 7.3 wires this up
 * to {@code important_dates} rows where {@code kind=BIRTHDAY} (the schema is V5). For Part 7.1 the
 * type exists only to satisfy the {@code CalendarItem} sealed contract.
 */
public record BirthdayCalendarItem(LocalDate date, Object data) implements CalendarItem {}
