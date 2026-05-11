package com.childcarewow.calendar.calendar;

import java.time.LocalDate;

/**
 * Placeholder for the {@code "kind":"task"} branch of the calendar feed. Part 7.2 wires this up to
 * a real {@code TaskView} (still TBD — TaskView lands in Series 8). For Part 7.1 the type exists
 * only to satisfy the {@code CalendarItem} sealed contract; no instances are constructed at
 * runtime.
 */
public record TaskCalendarItem(LocalDate date, Object data) implements CalendarItem {}
