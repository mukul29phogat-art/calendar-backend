package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.calendar.CalendarReadService;
import com.childcarewow.calendar.calendar.TaskCalendarItem;
import com.childcarewow.calendar.exception.InvalidRecurrenceException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.task.CreateTaskRequest.RecurrenceSpec;
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
 * Real-DB IT for Part 9.1 — task creation with a recurrence spec. The {@code TaskService.create}
 * flow now creates a {@link RecurrenceRule} alongside the task when {@code req.recurrence()} is
 * present, and the {@code calendar} read path (Part 7.2 / {@code TaskReadService.findInWindow})
 * expands the rule into per-occurrence {@link TaskCalendarItem}s. This file pins both halves: the
 * write produces a real recurrence row stamped on the task, and the read returns the expected
 * occurrence count.
 */
@SpringBootTest
class TaskCreateWithRecurrenceIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");

  @Autowired TaskService taskService;
  @Autowired CalendarReadService calendarReadService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-tcr-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-tcr-%'");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-tcr-%'");
    calendarJdbc.update(
        "DELETE FROM recurrence_rules WHERE id NOT IN "
            + "(SELECT recurrence_id FROM tasks WHERE recurrence_id IS NOT NULL)");
  }

  @Test
  void dailyRuleRoundTripsThroughCreate() {
    LocalDate dueDate = LocalDate.of(2027, 6, 1);
    LocalDate untilDate = LocalDate.of(2027, 6, 14);
    RecurrenceSpec spec = new RecurrenceSpec(RecurCycle.DAILY, null, null, null, untilDate);

    TaskView created =
        taskService.create(requestFor("IT-tcr-daily", dueDate, spec), admin()).get(0);

    assertThat(created.recurrenceId()).isNotNull();

    // The rule row carries the cycle + untilDate we sent.
    String cycle =
        calendarJdbc.queryForObject(
            "SELECT cycle FROM recurrence_rules WHERE id = ?",
            String.class,
            created.recurrenceId());
    java.sql.Date untilJdbc =
        calendarJdbc.queryForObject(
            "SELECT until_date FROM recurrence_rules WHERE id = ?",
            java.sql.Date.class,
            created.recurrenceId());
    assertThat(cycle).isEqualTo("DAILY");
    assertThat(untilJdbc.toLocalDate()).isEqualTo(untilDate);
  }

  @Test
  void weeklyRuleRoundTripsAndExpandsToFourOccurrencesInOneMonthWindow() {
    LocalDate dueDate = LocalDate.of(2027, 6, 2); // a Wednesday
    LocalDate untilDate = LocalDate.of(2027, 12, 31);
    // dueDayOfWeek = 3 (Wed) in the FE-prototype 0=Sun..6=Sat convention.
    RecurrenceSpec spec = new RecurrenceSpec(RecurCycle.WEEKLY, (short) 3, null, null, untilDate);

    TaskView created =
        taskService.create(requestFor("IT-tcr-weekly", dueDate, spec), admin()).get(0);
    assertThat(created.recurrenceId()).isNotNull();

    // Calendar read for June 2027 → 5 Wednesdays (2nd, 9th, 16th, 23rd, 30th).
    List<TaskCalendarItem> items =
        calendarReadService
            .read(SUNRISE, LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 30), admin())
            .stream()
            .filter(TaskCalendarItem.class::isInstance)
            .map(TaskCalendarItem.class::cast)
            .toList();
    assertThat(items.stream().map(TaskCalendarItem::date).toList())
        .containsExactlyInAnyOrder(
            LocalDate.of(2027, 6, 2),
            LocalDate.of(2027, 6, 9),
            LocalDate.of(2027, 6, 16),
            LocalDate.of(2027, 6, 23),
            LocalDate.of(2027, 6, 30));
  }

  @Test
  void monthlyRuleRoundTripsAndExpandsForFiveMonthWindow() {
    LocalDate dueDate = LocalDate.of(2027, 6, 15);
    LocalDate untilDate = LocalDate.of(2027, 12, 31);
    RecurrenceSpec spec = new RecurrenceSpec(RecurCycle.MONTHLY, null, (short) 15, null, untilDate);

    TaskView created =
        taskService.create(requestFor("IT-tcr-monthly", dueDate, spec), admin()).get(0);
    assertThat(created.recurrenceId()).isNotNull();

    List<TaskCalendarItem> items =
        calendarReadService
            .read(SUNRISE, LocalDate.of(2027, 6, 1), LocalDate.of(2027, 10, 31), admin())
            .stream()
            .filter(TaskCalendarItem.class::isInstance)
            .map(TaskCalendarItem.class::cast)
            .toList();
    assertThat(items.stream().map(TaskCalendarItem::date).toList())
        .containsExactlyInAnyOrder(
            LocalDate.of(2027, 6, 15),
            LocalDate.of(2027, 7, 15),
            LocalDate.of(2027, 8, 15),
            LocalDate.of(2027, 9, 15),
            LocalDate.of(2027, 10, 15));
  }

  @Test
  void weeklyMissingDueDayOfWeekRejected() {
    // Cycle-shape validation lives in RecurrenceService.validate — invalid recurrence surfaces as
    // InvalidRecurrenceException (400 INVALID_RECURRENCE envelope).
    RecurrenceSpec spec =
        new RecurrenceSpec(RecurCycle.WEEKLY, null, null, null, LocalDate.of(2027, 12, 31));

    assertThatThrownBy(
            () ->
                taskService.create(
                    requestFor("IT-tcr-bad-weekly", LocalDate.of(2027, 6, 1), spec), admin()))
        .isInstanceOf(InvalidRecurrenceException.class);
  }

  @Test
  void untilDateBeforeDueDateRejected() {
    RecurrenceSpec spec =
        new RecurrenceSpec(RecurCycle.DAILY, null, null, null, LocalDate.of(2027, 5, 30));

    assertThatThrownBy(
            () ->
                taskService.create(
                    requestFor("IT-tcr-bad-until", LocalDate.of(2027, 6, 1), spec), admin()))
        .isInstanceOf(InvalidRecurrenceException.class);
  }

  @Test
  void recurrenceWithMultiAssigneeRejectedUntilPart9_2() {
    RecurrenceSpec spec =
        new RecurrenceSpec(RecurCycle.DAILY, null, null, null, LocalDate.of(2027, 6, 14));
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tcr-multi-recur",
            null,
            SUNRISE,
            null,
            List.of(MAYA, TOM),
            LocalDate.of(2027, 6, 1),
            null,
            TaskStatus.TODO,
            TaskPriority.MEDIUM,
            spec);

    assertThatThrownBy(() -> taskService.create(req, admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Part 9.2");
  }

  @Test
  void nullRecurrenceStillCreatesNonRecurringTask() {
    // Sanity: existing non-recurring path unchanged. Back-compat ctor on CreateTaskRequest still
    // works (no recurrence field — defaults to null).
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tcr-non-recurring",
            null,
            SUNRISE,
            null,
            List.of(MAYA),
            LocalDate.of(2027, 6, 1),
            null,
            TaskStatus.TODO,
            TaskPriority.MEDIUM);

    TaskView created = taskService.create(req, admin()).get(0);
    assertThat(created.recurrenceId()).isNull();
  }

  // -- helpers ---------------------------------------------------------------

  private static CreateTaskRequest requestFor(
      String title, LocalDate dueDate, RecurrenceSpec recurrence) {
    return new CreateTaskRequest(
        title,
        null,
        SUNRISE,
        null,
        List.of(MAYA),
        dueDate,
        null,
        TaskStatus.TODO,
        TaskPriority.MEDIUM,
        recurrence);
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
}
