package com.childcarewow.calendar.calendar;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.LocalDate;

/**
 * Polymorphic calendar entry per architecture spec § 6.5 / § 7.1. Five kinds; Jackson emits a
 * lowercase {@code "kind"} discriminator on every serialized item, matching the frontend's
 * discriminated union (see {@code src/types/index.ts:138} in Events_CCW).
 *
 * <p>Part 7.1 wires only {@link EventCalendarItem}. The remaining four kinds are part of the sealed
 * contract from day one so the type system catches every "kind" that needs to be handled when Parts
 * 7.2 (tasks) and 7.3 (holidays + important_dates + birthdays) extend the read pipeline. Until
 * those parts ship, only {@link EventCalendarItem} instances exist at runtime.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = EventCalendarItem.class, name = "event"),
  @JsonSubTypes.Type(value = TaskCalendarItem.class, name = "task"),
  @JsonSubTypes.Type(value = HolidayCalendarItem.class, name = "holiday"),
  @JsonSubTypes.Type(value = BirthdayCalendarItem.class, name = "birthday"),
  @JsonSubTypes.Type(value = ImportantCalendarItem.class, name = "important"),
})
public sealed interface CalendarItem
    permits EventCalendarItem,
        TaskCalendarItem,
        HolidayCalendarItem,
        BirthdayCalendarItem,
        ImportantCalendarItem {

  /** School-local calendar date this item should render on. */
  LocalDate date();
}
