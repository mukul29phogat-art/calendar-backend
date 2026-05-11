package com.childcarewow.calendar.calendar;

import com.childcarewow.calendar.holiday.HolidayView;
import java.time.LocalDate;

/**
 * Placeholder for the {@code "kind":"holiday"} branch. The {@link HolidayView} type already exists
 * (Part 6.1) but the calendar feed doesn't yet emit holidays — Part 7.3 wires the query path. For
 * Part 7.1 the type exists only to satisfy the {@code CalendarItem} sealed contract.
 */
public record HolidayCalendarItem(LocalDate date, HolidayView data) implements CalendarItem {}
