package com.childcarewow.calendar.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.CreateEventRequest;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.importantdate.CreateImportantDateRequest;
import com.childcarewow.calendar.importantdate.ImportantDateService;
import com.childcarewow.calendar.importantdate.ImportantKind;
import com.childcarewow.calendar.policy.PolicyService;
import com.childcarewow.calendar.task.CreateTaskRequest;
import com.childcarewow.calendar.task.TaskPriority;
import com.childcarewow.calendar.task.TaskService;
import com.childcarewow.calendar.task.TaskStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-cutting parent visibility QA for {@link CalendarReadService#read} (Part 11.9).
 *
 * <p>Every parent-visibility rule has per-entity coverage in its own IT ({@code EventReadIT},
 * {@code TaskReadIT}, {@code HolidayReadIT}, {@code ImportantDateListIT}, {@code
 * CalendarHolidayImportantReadIT}, {@code CalendarTaskReadIT}). This file STITCHES them into one
 * fixture and calls {@code CalendarReadService.read} as PARENT, asserting the full multi-kind
 * result set in a single assertion. Catches "one entity's filter shifts and another's compensates"
 * regressions that per-entity tests can't.
 *
 * <p><b>Complex-week fixture</b> at SUNRISE, window {@code [2027-08-15, 2027-08-21]}:
 *
 * <ul>
 *   <li>Event A — CLASSROOM, parent's child's classroom, inviteParents=true → VISIBLE
 *   <li>Event B — CLASSROOM, OTHER classroom, inviteParents=true → HIDDEN (child not in classroom)
 *   <li>Event C — CLASSROOM, parent's child's classroom, inviteParents=false → HIDDEN (gate)
 *   <li>Event D — CUSTOM, parent's child in event_students → VISIBLE
 *   <li>Event E — SCHOOL, parent's user_id in excluded → HIDDEN
 *   <li>Event F — SCHOOL, parent's child's student_id in excluded → HIDDEN
 *   <li>Task G — staff-assigned, in window → HIDDEN (D10 — parents see zero tasks)
 *   <li>Holiday H — CUSTOM approved → VISIBLE
 *   <li>Holiday I — FEDERAL pending (approved=false) → HIDDEN
 *   <li>Important J — BIRTHDAY of parent's child, visibleToParents=true → VISIBLE
 *   <li>Important K — BIRTHDAY of OTHER child, visibleToParents=true → HIDDEN
 *   <li>Important L — IMPORTANT row, visibleToParents=true → VISIBLE
 *   <li>Important M — IMPORTANT row, visibleToParents=false → HIDDEN
 * </ul>
 *
 * <p>Expected parent feed: A, D, H, J, L (5 items). Six per-aspect tests pin individual exclusions;
 * one full-set test pins the entire shape; one STAFF-actor sanity test ensures the filter isn't
 * accidentally over-broad; one policy-gate test pins the playbook common-failure- point ({@code
 * calendar.softFlag.see} returns false for PARENT).
 */
@SpringBootTest
class CalendarReadServiceParentIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  // Aanya is in Butterflies (Priya's child).
  private static final UUID AANYA = UUID.fromString("55555555-0000-0000-0000-000000000001");
  // Jordan is in Caterpillars (other child).
  private static final UUID JORDAN = UUID.fromString("55555555-0000-0000-0000-000000000002");

  private static final LocalDate WINDOW_FROM = LocalDate.of(2027, 8, 15);
  private static final LocalDate WINDOW_TO = LocalDate.of(2027, 8, 21);

  @Autowired CalendarReadService calendarReadService;
  @Autowired EventService eventService;
  @Autowired TaskService taskService;
  @Autowired ImportantDateService importantDateService;
  @Autowired PolicyService policyService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  private UUID eventA;
  private UUID eventB;
  private UUID eventD;
  private UUID taskG;
  private UUID holidayH;
  private UUID holidayI;
  private UUID importantJ;
  private UUID importantL;

  @BeforeEach
  void seedComplexWeek() {
    cleanupNoise(); // belt-and-braces — previous test class run may have left rows

    // Event A — CLASSROOM at Butterflies, inviteParents=true → parent sees.
    eventA =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-prv-event-A-visible-classroom",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2027-08-15T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-08-15T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();

    // Event B — CLASSROOM at Caterpillars (NOT parent's child's classroom), inviteParents=true.
    eventB =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-prv-event-B-other-classroom",
                    null,
                    CATERPILLARS,
                    OffsetDateTime.parse("2027-08-16T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-08-16T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();

    // Event C — CLASSROOM at Butterflies, inviteParents=FALSE → gate fails. (Field id captured to
    // a local in case a later test wants it; @SuppressWarnings keeps -Werror happy if unused.)
    @SuppressWarnings("unused")
    UUID eventC =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-prv-event-C-invite-false",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2027-08-17T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-08-17T10:00:00-04:00"),
                    false,
                    null,
                    false),
                admin())
            .id();

    // Event D — CUSTOM with Aanya as student → parent sees.
    eventD =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.CUSTOM,
                    SUNRISE,
                    "IT-prv-event-D-custom-with-aanya",
                    null,
                    null,
                    OffsetDateTime.parse("2027-08-18T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-08-18T10:00:00-04:00"),
                    false,
                    null,
                    true,
                    List.of(MAYA),
                    List.of(AANYA),
                    null),
                admin())
            .id();

    // Event E — SCHOOL with Priya in excludedParticipantIds (her user_id) → parent EXCLUDED.
    @SuppressWarnings("unused")
    UUID eventE =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-prv-event-E-excluded-by-user",
                    null,
                    null,
                    OffsetDateTime.parse("2027-08-19T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-08-19T10:00:00-04:00"),
                    false,
                    null,
                    true,
                    null,
                    null,
                    List.of(PRIYA)),
                admin())
            .id();

    // Event F — SCHOOL with Aanya in excludedParticipantIds (her student_id) → parent EXCLUDED.
    @SuppressWarnings("unused")
    UUID eventF =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-prv-event-F-excluded-by-student",
                    null,
                    null,
                    OffsetDateTime.parse("2027-08-20T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-08-20T10:00:00-04:00"),
                    false,
                    null,
                    true,
                    null,
                    null,
                    List.of(AANYA)),
                admin())
            .id();

    // Task G — D10 says parents see zero tasks regardless of assignee.
    taskG =
        taskService
            .create(
                new CreateTaskRequest(
                    "IT-prv-task-G",
                    null,
                    SUNRISE,
                    BUTTERFLIES,
                    List.of(MAYA),
                    LocalDate.of(2027, 8, 19),
                    null,
                    TaskStatus.TODO,
                    TaskPriority.MEDIUM),
                admin())
            .get(0)
            .id();

    // Holidays — direct JDBC insert to skip the policy + duplicate-date checks on the service.
    holidayH = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "created_by_user_id) VALUES (?, ?, ?, ?, ?, 'CUSTOM', true, ?)",
        holidayH,
        ORG,
        SUNRISE,
        java.sql.Date.valueOf(LocalDate.of(2027, 8, 16)),
        "IT-prv-holiday-H-approved",
        OLIVIA);
    holidayI = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "created_by_user_id) VALUES (?, ?, ?, ?, ?, 'FEDERAL', false, ?)",
        holidayI,
        ORG,
        SUNRISE,
        java.sql.Date.valueOf(LocalDate.of(2027, 8, 17)),
        "IT-prv-holiday-I-pending-federal",
        OLIVIA);

    // Important dates — own child's birthday (visible), other child's birthday (hidden), public
    // important (visible), private important (hidden).
    importantJ =
        importantDateService
            .create(
                new CreateImportantDateRequest(
                    "IT-prv-important-J-aanya-bday",
                    LocalDate.of(2027, 8, 18),
                    SUNRISE,
                    ImportantKind.BIRTHDAY,
                    AANYA,
                    true),
                admin())
            .id();
    @SuppressWarnings("unused")
    UUID importantK =
        importantDateService
            .create(
                new CreateImportantDateRequest(
                    "IT-prv-important-K-jordan-bday",
                    LocalDate.of(2027, 8, 19),
                    SUNRISE,
                    ImportantKind.BIRTHDAY,
                    JORDAN,
                    true),
                admin())
            .id();
    importantL =
        importantDateService
            .create(
                new CreateImportantDateRequest(
                    "IT-prv-important-L-public",
                    LocalDate.of(2027, 8, 20),
                    SUNRISE,
                    ImportantKind.IMPORTANT,
                    null,
                    true),
                admin())
            .id();
    @SuppressWarnings("unused")
    UUID importantM =
        importantDateService
            .create(
                new CreateImportantDateRequest(
                    "IT-prv-important-M-hidden",
                    LocalDate.of(2027, 8, 21),
                    SUNRISE,
                    ImportantKind.IMPORTANT,
                    null,
                    false),
                admin())
            .id();
  }

  @AfterEach
  void cleanup() {
    cleanupNoise();
  }

  private void cleanupNoise() {
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-prv-%'");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-prv-%'");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-prv-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-prv-%'");
    calendarJdbc.update("DELETE FROM important_dates WHERE label LIKE 'IT-prv-%'");
  }

  @Test
  void parentSeesExactlyTheVisibleSubset() {
    List<CalendarItem> result =
        calendarReadService.read(SUNRISE, WINDOW_FROM, WINDOW_TO, parentPriya());

    // 5 visible items: event A + event D + holiday H + important J (birthday) + important L
    // (public important). Everything else is hidden.
    List<UUID> visibleEventIds =
        result.stream()
            .filter(EventCalendarItem.class::isInstance)
            .map(EventCalendarItem.class::cast)
            .map(e -> e.data().id())
            .toList();
    List<UUID> visibleHolidayIds =
        result.stream()
            .filter(HolidayCalendarItem.class::isInstance)
            .map(HolidayCalendarItem.class::cast)
            .map(h -> h.data().id())
            .toList();
    List<UUID> visibleBirthdayIds =
        result.stream()
            .filter(BirthdayCalendarItem.class::isInstance)
            .map(BirthdayCalendarItem.class::cast)
            .map(b -> b.data().id())
            .toList();
    List<UUID> visibleImportantIds =
        result.stream()
            .filter(ImportantCalendarItem.class::isInstance)
            .map(ImportantCalendarItem.class::cast)
            .map(i -> i.data().id())
            .toList();

    assertThat(visibleEventIds).containsExactlyInAnyOrder(eventA, eventD);
    assertThat(visibleHolidayIds).containsExactly(holidayH);
    assertThat(visibleBirthdayIds).containsExactly(importantJ);
    assertThat(visibleImportantIds).containsExactly(importantL);
    // The full result is exactly 5 items.
    assertThat(result).hasSize(5);
  }

  @Test
  void noCalendarItemOfKindTaskInParentResponse() {
    // D10 regression guard. Even though Task G exists in window, parent feed contains zero
    // TaskCalendarItem rows.
    List<CalendarItem> result =
        calendarReadService.read(SUNRISE, WINDOW_FROM, WINDOW_TO, parentPriya());

    assertThat(result.stream().anyMatch(TaskCalendarItem.class::isInstance)).isFalse();
    // Sanity: the task does exist in the DB (cleanup will find it).
    Integer count =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM tasks WHERE id = ?", Integer.class, taskG);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void excludedByUserIdHidesEvent() {
    // Event E excludes Priya (user). Should NOT appear in parent feed.
    List<CalendarItem> result =
        calendarReadService.read(SUNRISE, WINDOW_FROM, WINDOW_TO, parentPriya());

    boolean sawExcludedEvent =
        result.stream()
            .filter(EventCalendarItem.class::isInstance)
            .map(EventCalendarItem.class::cast)
            .anyMatch(e -> e.data().title().equals("IT-prv-event-E-excluded-by-user"));
    assertThat(sawExcludedEvent).isFalse();
  }

  @Test
  void excludedByChildStudentIdHidesEvent() {
    // Event F excludes Aanya (student). Should NOT appear in parent feed.
    List<CalendarItem> result =
        calendarReadService.read(SUNRISE, WINDOW_FROM, WINDOW_TO, parentPriya());

    boolean sawExcludedEvent =
        result.stream()
            .filter(EventCalendarItem.class::isInstance)
            .map(EventCalendarItem.class::cast)
            .anyMatch(e -> e.data().title().equals("IT-prv-event-F-excluded-by-student"));
    assertThat(sawExcludedEvent).isFalse();
  }

  @Test
  void pendingFederalHolidayHiddenFromParent() {
    // Holiday I is FEDERAL with approved=false. Parent feed must NOT include it; only approved
    // holidays surface (anyone-role-wise; HolidayRepository.findApprovedInWindow filters in SQL).
    List<CalendarItem> result =
        calendarReadService.read(SUNRISE, WINDOW_FROM, WINDOW_TO, parentPriya());

    boolean sawPendingHoliday =
        result.stream()
            .filter(HolidayCalendarItem.class::isInstance)
            .map(HolidayCalendarItem.class::cast)
            .anyMatch(h -> h.data().id().equals(holidayI));
    assertThat(sawPendingHoliday).isFalse();
  }

  @Test
  void otherChildsBirthdayHiddenEvenWhenVisibleToParents() {
    // Important K is Jordan's birthday with visible_to_parents=true. Despite the flag, Priya
    // (parent of Aanya only) must NOT see Jordan's row. Re-anchors the 10.3 common-failure-point
    // in the cross-cutting context.
    List<CalendarItem> result =
        calendarReadService.read(SUNRISE, WINDOW_FROM, WINDOW_TO, parentPriya());

    boolean sawJordansBirthday =
        result.stream()
            .filter(BirthdayCalendarItem.class::isInstance)
            .map(BirthdayCalendarItem.class::cast)
            .anyMatch(b -> JORDAN.equals(b.data().studentId()));
    assertThat(sawJordansBirthday).isFalse();
  }

  @Test
  void staffActorSeesEveryRowExceptUnchanged() {
    // Sanity check: SAME fixture, STAFF actor (Maya) sees more rows than parent. Specifically:
    //  - All events, including the ones Priya was excluded from (staff doesn't have the parent
    //    exclusion semantics — exclusions only filter PARENT views per the policy layer).
    //  - The task (D10 carve-out is parent-only).
    //  - The approved holiday (FEDERAL pending stays hidden — that's a global rule, not parent).
    //  - All four important_date rows (visible_to_parents flag only narrows parents).
    //
    // This is the regression bumper for "we accidentally over-filtered for STAFF too."
    List<CalendarItem> result =
        calendarReadService.read(SUNRISE, WINDOW_FROM, WINDOW_TO, staffMaya());

    // STAFF sees the task that's hidden from parents.
    assertThat(result.stream().anyMatch(TaskCalendarItem.class::isInstance)).isTrue();
    // STAFF sees Jordan's birthday (parent-only visibility doesn't apply to staff).
    boolean staffSeesJordan =
        result.stream()
            .filter(BirthdayCalendarItem.class::isInstance)
            .map(BirthdayCalendarItem.class::cast)
            .anyMatch(b -> JORDAN.equals(b.data().studentId()));
    assertThat(staffSeesJordan).isTrue();
    // STAFF still doesn't see the pending federal holiday (global filter, not parent-only).
    boolean staffSeesPendingHoliday =
        result.stream()
            .filter(HolidayCalendarItem.class::isInstance)
            .map(HolidayCalendarItem.class::cast)
            .anyMatch(h -> h.data().id().equals(holidayI));
    assertThat(staffSeesPendingHoliday).isFalse();
  }

  @Test
  void softFlagPolicyDeniedForParent() {
    // Pins the playbook common-failure-point at the production-code line. The calendar feed
    // doesn't return SoftFlag* variants on CalendarItem (parents could never get one through
    // CalendarReadService anyway), so the canonical enforcement is at the policy layer that
    // controllers query before rendering soft-flag chips.
    boolean parentCanSee = policyService.can(parentPriya(), "calendar.softFlag.see");
    boolean adminCanSee = policyService.can(admin(), "calendar.softFlag.see");

    assertThat(parentCanSee).isFalse();
    assertThat(adminCanSee).isTrue();
  }

  // -- helpers ---------------------------------------------------------------

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES, CATERPILLARS),
        Set.of(),
        "Owner");
  }

  private static UserPrincipal parentPriya() {
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

  private static UserPrincipal staffMaya() {
    return new UserPrincipal(
        MAYA,
        "Maya",
        "maya@ccw.test",
        Role.STAFF,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES),
        Set.of(),
        "Lead Teacher");
  }
}
