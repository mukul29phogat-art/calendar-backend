package com.childcarewow.calendar.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.holiday.CreateHolidayRequest;
import com.childcarewow.calendar.holiday.HolidayService;
import com.childcarewow.calendar.importantdate.ImportantKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-DB IT for Part 7.3 — holidays + important_dates + birthdays + the {@code filters} param.
 * Pending federals never appear on the calendar feed (they live in the approval queue); parent
 * visibility on important_dates is gated on {@code visible_to_parents=true} plus, for birthdays,
 * the parent owning the linked student.
 */
@SpringBootTest
class CalendarHolidayImportantReadIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  private static final UUID AANYA = UUID.fromString("55555555-0000-0000-0000-000000000001");
  private static final UUID JORDAN = UUID.fromString("55555555-0000-0000-0000-000000000002");

  @Autowired CalendarReadService calendarReadService;
  @Autowired HolidayService holidayService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM conflict_flags WHERE conflicting_entity_id IN "
            + "(SELECT id FROM holidays WHERE name LIKE 'IT-chi-%')");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-chi-%'");
    calendarJdbc.update("DELETE FROM important_dates WHERE label LIKE 'IT-chi-%'");
  }

  // -- holidays --------------------------------------------------------------

  @Test
  void approvedHolidayAppearsAsHolidayCalendarItem() {
    LocalDate date = LocalDate.of(2026, 7, 4);
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-chi-Independence", null), admin());

    List<HolidayCalendarItem> holidays =
        onlyHolidays(read(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)));
    assertThat(holidays).hasSize(1);
    HolidayCalendarItem h = holidays.get(0);
    assertThat(h.date()).isEqualTo(date);
    assertThat(h.data().name()).isEqualTo("IT-chi-Independence");
    assertThat(h.data().approved()).isTrue();
  }

  @Test
  void pendingFederalHolidayNotInCalendarFeed() {
    // Raw insert because the supported path for federal-pending rows is the Nager.Date sync job
    // (Part 6.7), not the create endpoint. Approved=false → must NOT appear on the calendar feed.
    UUID pending = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved) "
            + "VALUES (?, ?, ?, ?, ?, 'FEDERAL', false)",
        pending,
        ORG,
        SUNRISE,
        LocalDate.of(2026, 11, 11),
        "IT-chi-Veterans-Pending");

    List<HolidayCalendarItem> holidays =
        onlyHolidays(read(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
    assertThat(holidays).isEmpty();
  }

  // -- important_dates -------------------------------------------------------

  @Test
  void adminSeesBothBirthdayAndImportantKinds() {
    insertImportantDate(
        "IT-chi-aanya-bday", LocalDate.of(2026, 4, 12), ImportantKind.BIRTHDAY, AANYA, true);
    insertImportantDate(
        "IT-chi-conferences", LocalDate.of(2026, 4, 20), ImportantKind.IMPORTANT, null, false);

    List<CalendarItem> all = read(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
    assertThat(all.stream().filter(BirthdayCalendarItem.class::isInstance)).hasSize(1);
    assertThat(all.stream().filter(ImportantCalendarItem.class::isInstance)).hasSize(1);
  }

  @Test
  void parentSeesOwnChildBirthdayWhenVisibleToParents() {
    insertImportantDate(
        "IT-chi-aanya-bday", LocalDate.of(2026, 4, 12), ImportantKind.BIRTHDAY, AANYA, true);

    List<CalendarItem> all =
        calendarReadService.read(
            SUNRISE, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), parent());
    List<BirthdayCalendarItem> birthdays =
        all.stream()
            .filter(BirthdayCalendarItem.class::isInstance)
            .map(BirthdayCalendarItem.class::cast)
            .toList();
    assertThat(birthdays).hasSize(1);
    assertThat(birthdays.get(0).data().studentId()).isEqualTo(AANYA);
  }

  @Test
  void parentDoesNotSeeOtherChildsBirthday() {
    // Jordan is at Sunrise but not Priya's child (Priya is Aanya's parent only).
    insertImportantDate(
        "IT-chi-jordan-bday", LocalDate.of(2026, 11, 3), ImportantKind.BIRTHDAY, JORDAN, true);

    List<CalendarItem> all =
        calendarReadService.read(
            SUNRISE, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), parent());
    assertThat(all.stream().filter(BirthdayCalendarItem.class::isInstance)).isEmpty();
  }

  @Test
  void parentDoesNotSeeBirthdayMarkedNotVisibleToParents() {
    insertImportantDate(
        "IT-chi-aanya-bday-private",
        LocalDate.of(2026, 4, 12),
        ImportantKind.BIRTHDAY,
        AANYA,
        false);

    List<CalendarItem> all =
        calendarReadService.read(
            SUNRISE, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), parent());
    assertThat(all.stream().filter(BirthdayCalendarItem.class::isInstance)).isEmpty();
  }

  @Test
  void parentVisibilityOnImportantHonoursVisibleToParentsGate() {
    insertImportantDate(
        "IT-chi-newsletter-public", LocalDate.of(2026, 4, 5), ImportantKind.IMPORTANT, null, true);
    insertImportantDate(
        "IT-chi-internal-memo", LocalDate.of(2026, 4, 6), ImportantKind.IMPORTANT, null, false);

    List<CalendarItem> all =
        calendarReadService.read(
            SUNRISE, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), parent());
    List<ImportantCalendarItem> visible =
        all.stream()
            .filter(ImportantCalendarItem.class::isInstance)
            .map(ImportantCalendarItem.class::cast)
            .toList();
    assertThat(visible).hasSize(1);
    assertThat(visible.get(0).data().label()).isEqualTo("IT-chi-newsletter-public");
  }

  // -- filters param --------------------------------------------------------

  @Test
  void filtersNarrowResponseToOnlyRequestedKinds() {
    LocalDate date = LocalDate.of(2026, 4, 15);
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-chi-April-Day", null), admin());
    insertImportantDate("IT-chi-bday-filtered-out", date, ImportantKind.BIRTHDAY, AANYA, true);

    List<CalendarItem> filtered =
        calendarReadService.read(
            SUNRISE,
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 30),
            Set.of("holidays"),
            admin());

    // Only the holiday survives; the birthday is filtered out.
    assertThat(filtered).hasSize(1);
    assertThat(filtered.get(0)).isInstanceOf(HolidayCalendarItem.class);
  }

  @Test
  void filtersAcceptBothPluralAndSingularTokens() {
    LocalDate date = LocalDate.of(2026, 4, 15);
    holidayService.create(new CreateHolidayRequest(SUNRISE, date, "IT-chi-Plural", null), admin());
    insertImportantDate("IT-chi-Important-Plural", date, ImportantKind.IMPORTANT, null, false);

    // Plural form (FE-friendly).
    List<CalendarItem> pluralResult =
        calendarReadService.read(
            SUNRISE,
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 30),
            Set.of("holidays", "important_dates"),
            admin());
    assertThat(pluralResult).hasSize(2);

    // Singular form (matches the JSON discriminator).
    List<CalendarItem> singularResult =
        calendarReadService.read(
            SUNRISE,
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 30),
            Set.of("holiday", "important"),
            admin());
    assertThat(singularResult).hasSize(2);
  }

  @Test
  void filtersUnknownTokensSilentlyDropAndReturnEmptyIfNoneRecognized() {
    LocalDate date = LocalDate.of(2026, 4, 15);
    holidayService.create(new CreateHolidayRequest(SUNRISE, date, "IT-chi-Unknown", null), admin());

    // Unknown token only → no kinds match → empty (not "all").
    List<CalendarItem> result =
        calendarReadService.read(
            SUNRISE,
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 30),
            Set.of("reminders"),
            admin());
    assertThat(result).isEmpty();
  }

  // -- helpers ---------------------------------------------------------------

  private void insertImportantDate(
      String label, LocalDate date, ImportantKind kind, UUID studentId, boolean visibleToParents) {
    calendarJdbc.update(
        "INSERT INTO important_dates "
            + "(id, org_id, school_id, date, label, kind, student_id, visible_to_parents) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        ORG,
        SUNRISE,
        date,
        label,
        kind.name(),
        studentId,
        visibleToParents);
  }

  private List<CalendarItem> read(LocalDate from, LocalDate to) {
    return calendarReadService.read(SUNRISE, from, to, admin());
  }

  private static List<HolidayCalendarItem> onlyHolidays(List<CalendarItem> items) {
    return items.stream()
        .filter(HolidayCalendarItem.class::isInstance)
        .map(HolidayCalendarItem.class::cast)
        .toList();
  }

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES),
        Set.of(),
        "Owner");
  }

  private static UserPrincipal parent() {
    return new UserPrincipal(
        PRIYA,
        "Priya",
        "priya@parent.test",
        Role.PARENT,
        ORG,
        Set.of(SUNRISE),
        Set.of(),
        Set.of(AANYA),
        null);
  }
}
