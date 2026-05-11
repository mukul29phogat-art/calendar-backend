package com.childcarewow.calendar.calendar;

import com.childcarewow.calendar.importantdate.ImportantDateView;
import java.time.LocalDate;

/**
 * Calendar entry wrapping an {@code important_dates} row where {@code kind=BIRTHDAY}. Wire shape:
 * {@code {"kind":"birthday","date":"YYYY-MM-DD","data":{...ImportantDateView...}}}. The {@code
 * data.studentId} identifies whose birthday this is.
 *
 * <p>Parent visibility: an admin must have created the row with {@code visible_to_parents=true},
 * AND the parent must own the linked student (i.e. {@code data.studentId} is in the parent's {@code
 * childStudentIds}). Both checks live in {@link
 * com.childcarewow.calendar.importantdate.ImportantDateReadService}.
 */
public record BirthdayCalendarItem(LocalDate date, ImportantDateView data)
    implements CalendarItem {}
