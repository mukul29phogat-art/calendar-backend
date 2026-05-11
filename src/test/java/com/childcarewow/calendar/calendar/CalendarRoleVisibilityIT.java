package com.childcarewow.calendar.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.CreateEventRequest;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.importantdate.ImportantKind;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * Part 7.4 — role-based visibility matrix.
 *
 * <p>Most per-role narrowing was already in place from earlier parts: events via {@code
 * EventService.isVisibleTo} (Part 5.4), tasks via the D10 short-circuit (Part 7.2), holidays via
 * the approved-only repo query (Part 7.3), and important_dates via {@code ImportantDateReadService}
 * (Part 7.3). The two net-new responsibilities of 7.4:
 *
 * <ol>
 *   <li><b>STAFF narrowing on tasks</b> — a staff member sees only tasks where {@code
 *       assigneeUserId = actor.id()}, not every task at their school.
 *   <li><b>STUDENT_VIEW audit</b> — every calendar response containing student references writes
 *       one audit row (action="STUDENT_VIEW") with the consolidated subject UUIDs in metadata.
 * </ol>
 *
 * Visibility checks for events, holidays, and important_dates are already covered by {@code
 * EventReadIT}, {@code CalendarTaskReadIT}, {@code CalendarHolidayImportantReadIT}; this file
 * focuses on the deltas + the audit contract.
 */
@SpringBootTest
class CalendarRoleVisibilityIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");
  private static final UUID AANYA = UUID.fromString("55555555-0000-0000-0000-000000000001");

  @Autowired CalendarReadService calendarReadService;
  @Autowired EventService eventService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-rv-%'");
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-rv-%'");
    calendarJdbc.update("DELETE FROM important_dates WHERE label LIKE 'IT-rv-%'");
    calendarJdbc.update(
        "DELETE FROM audit_events WHERE action = 'STUDENT_VIEW' AND user_agent IS NULL");
  }

  // -- STAFF narrowing on tasks ----------------------------------------------

  @Test
  void staffSeesOnlyTasksAssignedToThem() {
    insertTaskFor(MAYA, "IT-rv-maya-task", LocalDate.of(2026, 6, 10));
    insertTaskFor(TOM, "IT-rv-tom-task", LocalDate.of(2026, 6, 11));

    List<TaskCalendarItem> mayaTasks =
        onlyTasks(
            calendarReadService.read(
                SUNRISE, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), staff(MAYA)));
    assertThat(mayaTasks).hasSize(1);
    assertThat(mayaTasks.get(0).data().title()).isEqualTo("IT-rv-maya-task");

    List<TaskCalendarItem> tomTasks =
        onlyTasks(
            calendarReadService.read(
                SUNRISE, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), staff(TOM)));
    assertThat(tomTasks).hasSize(1);
    assertThat(tomTasks.get(0).data().title()).isEqualTo("IT-rv-tom-task");
  }

  @Test
  void adminSeesAllTasksRegardlessOfAssignee() {
    insertTaskFor(MAYA, "IT-rv-admin-maya", LocalDate.of(2026, 6, 10));
    insertTaskFor(TOM, "IT-rv-admin-tom", LocalDate.of(2026, 6, 11));

    List<TaskCalendarItem> tasks =
        onlyTasks(
            calendarReadService.read(
                SUNRISE, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin()));
    assertThat(tasks).hasSize(2);
  }

  // -- STUDENT_VIEW audit ----------------------------------------------------

  @Test
  void responseWithBirthdayWritesStudentViewAuditRow() {
    insertBirthday("IT-rv-aanya-bday", LocalDate.of(2026, 6, 12), AANYA);

    long before = countAuditRowsForActor(OLIVIA);
    calendarReadService.read(SUNRISE, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin());
    long after = countAuditRowsForActor(OLIVIA);
    assertThat(after - before).isEqualTo(1);

    // The newly-written row carries Aanya's UUID in subject_ids.
    String meta = latestStudentViewMetadata(OLIVIA);
    assertThat(meta).contains(AANYA.toString());
    assertThat(meta).contains("\"source\"").contains("calendar.read");
  }

  @Test
  void responseWithCustomEventCarryingStudentIdsWritesAuditRow() {
    // CUSTOM event with Aanya in studentIds → reveals her identity to the viewer.
    eventService.create(
        new CreateEventRequest(
            EventType.CUSTOM,
            SUNRISE,
            "IT-rv-custom-event",
            null,
            null,
            OffsetDateTime.parse("2026-06-15T09:00:00-04:00"),
            OffsetDateTime.parse("2026-06-15T10:00:00-04:00"),
            false,
            null,
            false,
            null,
            List.of(AANYA)),
        admin());

    long before = countAuditRowsForActor(OLIVIA);
    calendarReadService.read(SUNRISE, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin());
    long after = countAuditRowsForActor(OLIVIA);
    assertThat(after - before).isEqualTo(1);
    assertThat(latestStudentViewMetadata(OLIVIA)).contains(AANYA.toString());
  }

  @Test
  void responseWithOnlyHolidaysAndSchoolEventsWritesNoAuditRow() {
    eventService.create(
        new CreateEventRequest(
            EventType.SCHOOL,
            SUNRISE,
            "IT-rv-school-event",
            null,
            null,
            OffsetDateTime.parse("2026-06-15T09:00:00-04:00"),
            OffsetDateTime.parse("2026-06-15T10:00:00-04:00"),
            false,
            null,
            false),
        admin());

    long before = countAuditRowsForActor(OLIVIA);
    calendarReadService.read(SUNRISE, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin());
    long after = countAuditRowsForActor(OLIVIA);
    // SCHOOL events have no studentIds; nothing to audit.
    assertThat(after).isEqualTo(before);
  }

  @Test
  void emptyResponseWritesNoAuditRow() {
    long before = countAuditRowsForActor(OLIVIA);
    // Empty window at a date with no calendar items.
    calendarReadService.read(SUNRISE, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31), admin());
    long after = countAuditRowsForActor(OLIVIA);
    assertThat(after).isEqualTo(before);
  }

  @Test
  void birthdayPlusCustomEventConsolidateIntoOneAuditRow() {
    // Both items reference Aanya — exactly one audit row, with one subject_id (deduplicated).
    insertBirthday("IT-rv-aanya-bday-dedupe", LocalDate.of(2026, 6, 12), AANYA);
    eventService.create(
        new CreateEventRequest(
            EventType.CUSTOM,
            SUNRISE,
            "IT-rv-custom-aanya",
            null,
            null,
            OffsetDateTime.parse("2026-06-15T09:00:00-04:00"),
            OffsetDateTime.parse("2026-06-15T10:00:00-04:00"),
            false,
            null,
            false,
            null,
            List.of(AANYA)),
        admin());

    long before = countAuditRowsForActor(OLIVIA);
    calendarReadService.read(SUNRISE, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin());
    long after = countAuditRowsForActor(OLIVIA);
    // Exactly one audit row per response, regardless of how many student-bearing items it
    // contained.
    assertThat(after - before).isEqualTo(1);
    String meta = latestStudentViewMetadata(OLIVIA);
    assertThat(meta).contains(AANYA.toString());
  }

  // -- helpers ---------------------------------------------------------------

  private void insertTaskFor(UUID assignee, String title, LocalDate dueDate) {
    calendarJdbc.update(
        "INSERT INTO tasks "
            + "(id, org_id, school_id, title, assignee_user_id, due_date, status, priority, "
            + "created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'TODO', 'MEDIUM', ?)",
        UUID.randomUUID(),
        ORG,
        SUNRISE,
        title,
        assignee,
        dueDate,
        OLIVIA);
  }

  private void insertBirthday(String label, LocalDate date, UUID studentId) {
    calendarJdbc.update(
        "INSERT INTO important_dates "
            + "(id, org_id, school_id, date, label, kind, student_id, visible_to_parents) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, true)",
        UUID.randomUUID(),
        ORG,
        SUNRISE,
        date,
        label,
        ImportantKind.BIRTHDAY.name(),
        studentId);
  }

  private long countAuditRowsForActor(UUID actorId) {
    Integer n =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_events WHERE action = 'STUDENT_VIEW' AND actor_user_id = ?",
            Integer.class,
            actorId);
    return n == null ? 0L : n;
  }

  private String latestStudentViewMetadata(UUID actorId) {
    return calendarJdbc.queryForObject(
        "SELECT metadata::text FROM audit_events "
            + "WHERE action = 'STUDENT_VIEW' AND actor_user_id = ? "
            + "ORDER BY created_at DESC LIMIT 1",
        String.class,
        actorId);
  }

  private static List<TaskCalendarItem> onlyTasks(List<CalendarItem> items) {
    return items.stream()
        .filter(TaskCalendarItem.class::isInstance)
        .map(TaskCalendarItem.class::cast)
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
        Set.of(BUTTERFLIES, CATERPILLARS),
        Set.of(),
        "Owner");
  }

  private static UserPrincipal staff(UUID id) {
    return new UserPrincipal(
        id,
        "Test Staff",
        id + "@ccw.test",
        Role.STAFF,
        ORG,
        Set.of(SUNRISE),
        id.equals(MAYA) ? Set.of(BUTTERFLIES) : Set.of(CATERPILLARS),
        Set.of(),
        null);
  }
}
