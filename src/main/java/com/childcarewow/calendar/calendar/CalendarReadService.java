package com.childcarewow.calendar.calendar;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventView;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.holiday.HolidayRepository;
import com.childcarewow.calendar.holiday.HolidayView;
import com.childcarewow.calendar.importantdate.ImportantDateReadService;
import com.childcarewow.calendar.task.TaskReadService;
import com.childcarewow.calendar.timezone.TimezoneService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link CalendarController}'s {@code GET /api/v1/calendar}. Parts 7.1 (events), 7.2 (tasks
 * with recurrence expansion), and 7.3 (holidays + important_dates + birthdays + {@code filters}
 * wiring) are all in scope here.
 *
 * <p><b>Range gate.</b> Per architecture spec § 6.5, the {@code [from, to]} window may not exceed
 * 366 days inclusive. Wider windows are usually a client bug; rejecting at the boundary stops a
 * malformed request from blowing through the query plan.
 *
 * <p><b>Filters.</b> The optional {@code filters} query param narrows the response to a subset of
 * kinds. We accept the FE-friendly plural forms ({@code events}, {@code tasks}, {@code holidays},
 * {@code birthdays}, {@code important_dates}) and normalize to the singular kind strings used by
 * the {@code "kind"} JSON discriminator. Unknown filter tokens are silently dropped (better than
 * 400 — a future kind added to the FE wouldn't break older backends). {@code filters=null} (or
 * empty) means "all kinds".
 *
 * <p><b>School-local dates.</b> Events are stored as UTC {@code timestamptz} and projected to the
 * school's local date via {@link TimezoneService#toSchoolLocalDate}. Tasks, holidays, and
 * important_dates already store {@code date} as a calendar-day value, so they pass through as-is.
 */
@Service
public class CalendarReadService {

  /** Inclusive day-count cap: from/to spanning more than 366 days is rejected. */
  private static final long MAX_WINDOW_DAYS = 366;

  /**
   * Maps FE-friendly plural filter tokens to the singular kind strings used on the JSON
   * discriminator. {@code important_dates} and {@code important} both map to {@code important} —
   * the FE prototype uses "Important Dates" as the display label.
   */
  private static final Map<String, String> FILTER_TOKEN_TO_KIND =
      Map.of(
          "events", "event",
          "event", "event",
          "tasks", "task",
          "task", "task",
          "holidays", "holiday",
          "holiday", "holiday",
          "birthdays", "birthday",
          "birthday", "birthday",
          "important_dates", "important",
          "important", "important");

  private final EventService eventService;
  private final TaskReadService taskReadService;
  private final HolidayRepository holidayRepo;
  private final ImportantDateReadService importantDateReadService;
  private final TimezoneService timezoneService;

  public CalendarReadService(
      EventService eventService,
      TaskReadService taskReadService,
      HolidayRepository holidayRepo,
      ImportantDateReadService importantDateReadService,
      TimezoneService timezoneService) {
    this.eventService = eventService;
    this.taskReadService = taskReadService;
    this.holidayRepo = holidayRepo;
    this.importantDateReadService = importantDateReadService;
    this.timezoneService = timezoneService;
  }

  /** Convenience overload: no {@code filters} param means "all kinds". */
  @Transactional(readOnly = true)
  public List<CalendarItem> read(UUID schoolId, LocalDate from, LocalDate to, UserPrincipal actor) {
    return read(schoolId, from, to, null, actor);
  }

  @Transactional(readOnly = true)
  public List<CalendarItem> read(
      UUID schoolId, LocalDate from, LocalDate to, Set<String> filters, UserPrincipal actor) {
    if (schoolId == null || from == null || to == null) {
      throw new ValidationException("scope", "schoolId, from, and to are required");
    }
    if (to.isBefore(from)) {
      throw new ValidationException("to", "must be on or after `from`");
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_WINDOW_DAYS) {
      throw new ValidationException("to", "window may not exceed " + MAX_WINDOW_DAYS + " days");
    }

    Set<String> wantedKinds = normalizeFilters(filters);
    boolean wantEvents = wantedKinds == null || wantedKinds.contains("event");
    boolean wantTasks = wantedKinds == null || wantedKinds.contains("task");
    boolean wantHolidays = wantedKinds == null || wantedKinds.contains("holiday");
    boolean wantBirthdays = wantedKinds == null || wantedKinds.contains("birthday");
    boolean wantImportant = wantedKinds == null || wantedKinds.contains("important");

    List<CalendarItem> items = new ArrayList<>();

    if (wantEvents) {
      // Convert school-local [from, to] to a UTC OffsetDateTime envelope. Slightly over-inclusive
      // at school-timezone boundaries — Part 7.6 perf benchmark may tighten this.
      OffsetDateTime fromOdt = from.atStartOfDay().atOffset(ZoneOffset.UTC);
      OffsetDateTime toOdt = to.plusDays(1).atStartOfDay().minusNanos(1).atOffset(ZoneOffset.UTC);
      List<EventView> events = eventService.findInWindow(schoolId, fromOdt, toOdt, null, actor);
      for (EventView e : events) {
        items.add(new EventCalendarItem(schoolLocalDateOf(e), e));
      }
    }

    if (wantTasks) {
      items.addAll(taskReadService.findInWindow(schoolId, from, to, actor));
    }

    if (wantHolidays) {
      holidayRepo.findApprovedInWindow(schoolId, from, to).stream()
          .map(h -> new HolidayCalendarItem(h.getDate(), HolidayView.fromEntity(h)))
          .forEach(items::add);
    }

    if (wantBirthdays || wantImportant) {
      List<CalendarItem> importantItems =
          importantDateReadService.findInWindow(schoolId, from, to, actor);
      for (CalendarItem i : importantItems) {
        if (i instanceof BirthdayCalendarItem && wantBirthdays) {
          items.add(i);
        } else if (i instanceof ImportantCalendarItem && wantImportant) {
          items.add(i);
        }
      }
    }

    return items;
  }

  private LocalDate schoolLocalDateOf(EventView e) {
    return timezoneService.toSchoolLocalDate(e.startDt().toInstant(), e.schoolId());
  }

  /**
   * Normalizes the raw filter set into the singular kind discriminator strings. Returns {@code
   * null} when the input is null or empty — callers treat that as "all kinds". Unknown tokens are
   * silently dropped so a future client filter ("reminders") doesn't 400 against an older backend.
   */
  static Set<String> normalizeFilters(Set<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    Set<String> out = new java.util.HashSet<>();
    for (String token : raw) {
      if (token == null) {
        continue;
      }
      String kind = FILTER_TOKEN_TO_KIND.get(token.toLowerCase(Locale.ROOT));
      if (kind != null) {
        out.add(kind);
      }
    }
    // If every supplied token was unknown, fall back to "none" rather than "all". The client asked
    // for a specific set and we honor the intent; returning everything would surprise them.
    return out;
  }
}
