package com.childcarewow.calendar.calendar;

import com.childcarewow.calendar.importantdate.ImportantDateView;
import java.time.LocalDate;

/**
 * Calendar entry wrapping an {@code important_dates} row where {@code kind=IMPORTANT}. Wire shape:
 * {@code {"kind":"important","date":"YYYY-MM-DD","data":{...ImportantDateView...}}}. Use cases:
 * back-to-school night, parent-teacher conferences, school-wide announcements that need a calendar
 * marker but aren't full events.
 *
 * <p>Parent visibility is gated on {@code data.visibleToParents}; unlike birthdays, important
 * entries have no per-student narrowing — once {@code visibleToParents=true}, every parent at the
 * school sees it.
 */
public record ImportantCalendarItem(LocalDate date, ImportantDateView data)
    implements CalendarItem {}
