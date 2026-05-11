package com.childcarewow.calendar.calendar;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventView;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.task.TaskReadService;
import com.childcarewow.calendar.timezone.TimezoneService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link CalendarController}'s {@code GET /api/v1/calendar}. Part 7.1 emits events only;
 * Parts 7.2 / 7.3 layer in tasks, holidays, important_dates, and birthdays into the same response.
 *
 * <p><b>Range gate.</b> Per architecture spec § 6.5, the {@code [from, to]} window may not exceed
 * 366 days inclusive. Wider windows are usually a client bug (typo, infinite scroll regression);
 * rejecting at the boundary stops a malformed request from blowing through the query plan and
 * lighting up the event-table scan.
 *
 * <p><b>Visibility.</b> Delegated to {@link EventService#findInWindow} (Part 5.4), which applies
 * the role-aware {@code isVisibleTo} filter. PARENT additionally requires {@code
 * inviteParents=true}, child-in-scope, and not in {@code excludedParticipantIds}.
 *
 * <p><b>School-local dates.</b> The {@code date} field on each {@link CalendarItem} is the
 * <em>school</em>'s local date for the event's {@code startDt} instant — not the UTC date, not the
 * offset embedded in {@code EventView.startDt}. The FE renders by school timezone per CLAUDE.md §
 * 8.
 */
@Service
public class CalendarReadService {

  /** Inclusive day-count cap: from/to spanning more than 366 days is rejected. */
  private static final long MAX_WINDOW_DAYS = 366;

  private final EventService eventService;
  private final TaskReadService taskReadService;
  private final TimezoneService timezoneService;

  public CalendarReadService(
      EventService eventService, TaskReadService taskReadService, TimezoneService timezoneService) {
    this.eventService = eventService;
    this.taskReadService = taskReadService;
    this.timezoneService = timezoneService;
  }

  @Transactional(readOnly = true)
  public List<CalendarItem> read(UUID schoolId, LocalDate from, LocalDate to, UserPrincipal actor) {
    if (schoolId == null || from == null || to == null) {
      throw new ValidationException("scope", "schoolId, from, and to are required");
    }
    if (to.isBefore(from)) {
      throw new ValidationException("to", "must be on or after `from`");
    }
    // Inclusive day count = DAYS.between(from, to) + 1. Cap at 366.
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_WINDOW_DAYS) {
      throw new ValidationException("to", "window may not exceed " + MAX_WINDOW_DAYS + " days");
    }

    // Convert school-local [from, to] to a UTC OffsetDateTime envelope. This may include events
    // whose UTC instant is on the boundary day of an adjacent school-local date — acceptable for
    // v1 (the FE date-bucket re-checks via the per-item school-local date below). Tightening to a
    // strict school-local boundary requires loading the school timezone before querying; that's
    // a 7.6 perf concern.
    OffsetDateTime fromOdt = from.atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime toOdt = to.plusDays(1).atStartOfDay().minusNanos(1).atOffset(ZoneOffset.UTC);

    List<EventView> events = eventService.findInWindow(schoolId, fromOdt, toOdt, null, actor);
    List<CalendarItem> items = new ArrayList<>(events.size());
    for (EventView e : events) {
      items.add(new EventCalendarItem(schoolLocalDateOf(e), e));
    }
    items.addAll(taskReadService.findInWindow(schoolId, from, to, actor));
    return items;
  }

  /** School-local date for the event's {@code startDt} instant. */
  private LocalDate schoolLocalDateOf(EventView e) {
    return timezoneService.toSchoolLocalDate(e.startDt().toInstant(), e.schoolId());
  }
}
