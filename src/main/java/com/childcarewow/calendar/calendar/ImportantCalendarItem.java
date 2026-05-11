package com.childcarewow.calendar.calendar;

import java.time.LocalDate;

/**
 * Placeholder for the {@code "kind":"important"} branch of the calendar feed. Part 7.3 wires this
 * up to {@code important_dates} rows where {@code kind=IMPORTANT}. For Part 7.1 the type exists
 * only to satisfy the {@code CalendarItem} sealed contract.
 */
public record ImportantCalendarItem(LocalDate date, Object data) implements CalendarItem {}
