package com.childcarewow.calendar.calendar;

import com.childcarewow.calendar.event.EventView;
import java.time.LocalDate;

/**
 * Calendar entry wrapping an {@link EventView}. Wire shape: {@code
 * {"kind":"event","date":"YYYY-MM-DD","data":{...EventView...}}}.
 *
 * <p>The {@code date} field is the event's <em>school-local</em> date (computed via {@link
 * com.childcarewow.calendar.timezone.TimezoneService}), not the UTC date of {@code data.startDt} —
 * the FE calendar grid renders by school timezone per CLAUDE.md §8.
 */
public record EventCalendarItem(LocalDate date, EventView data) implements CalendarItem {}
