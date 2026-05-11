package com.childcarewow.calendar.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
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
 * Real-DB IT for the calendar feed's task branch (Part 7.2). Tasks are inserted via raw JDBC
 * because the full {@code TaskService} write surface doesn't exist yet — it lands in Series 8.
 * Until then this is the canonical exercise of the read path: non-recurring tasks land in the
 * response, recurring tasks expand via {@code RecurrenceService.expand}, per-occurrence overrides
 * apply (skipped drops a date), and PARENT short-circuits to no tasks per D10.
 */
@SpringBootTest
class CalendarTaskReadIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  private static final UUID AANYA = UUID.fromString("55555555-0000-0000-0000-000000000001");

  @Autowired CalendarReadService calendarReadService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM task_instance_overrides WHERE task_id IN "
            + "(SELECT id FROM tasks WHERE title LIKE 'IT-tcr-%')");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-tcr-%'");
    calendarJdbc.update(
        "DELETE FROM recurrence_rules WHERE id IN "
            + "(SELECT recurrence_id FROM tasks WHERE title LIKE 'IT-tcr-%')");
    // Some recurrence rules become orphaned when the task above is deleted first; clean by date.
    calendarJdbc.update(
        "DELETE FROM recurrence_rules "
            + "WHERE until_date >= '2026-01-01' AND until_date <= '2030-12-31' "
            + "AND id NOT IN (SELECT recurrence_id FROM tasks WHERE recurrence_id IS NOT NULL)");
  }

  @Test
  void nonRecurringTaskInWindowAppearsAsOneItem() {
    insertNonRecurringTask("IT-tcr-grade-papers", LocalDate.of(2026, 5, 10));

    List<TaskCalendarItem> tasks =
        onlyTasks(read(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)));
    assertThat(tasks).hasSize(1);
    TaskCalendarItem item = tasks.get(0);
    assertThat(item.date()).isEqualTo(LocalDate.of(2026, 5, 10));
    assertThat(item.data().title()).isEqualTo("IT-tcr-grade-papers");
    assertThat(item.data().recurrenceId()).isNull();
  }

  @Test
  void recurringWeeklyTaskExpandsToOneOccurrencePerWeek() {
    // Weekly on Wednesday (JS dueDayOfWeek=3). 4 Wednesdays in [2026-05-04, 2026-05-31]:
    // 5/6, 5/13, 5/20, 5/27.
    UUID ruleId = insertWeeklyRule(3, LocalDate.of(2026, 12, 31));
    insertRecurringTask("IT-tcr-weekly-standup", LocalDate.of(2026, 5, 6), ruleId);

    List<TaskCalendarItem> tasks =
        onlyTasks(read(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 31)));
    assertThat(tasks).hasSize(4);
    assertThat(tasks.stream().map(TaskCalendarItem::date).toList())
        .containsExactlyInAnyOrder(
            LocalDate.of(2026, 5, 6),
            LocalDate.of(2026, 5, 13),
            LocalDate.of(2026, 5, 20),
            LocalDate.of(2026, 5, 27));
    // Every occurrence carries the parent task's id.
    assertThat(tasks.stream().map(t -> t.data().id()).distinct()).hasSize(1);
  }

  @Test
  void skippedOverrideDropsTheOccurrence() {
    UUID ruleId = insertWeeklyRule(3, LocalDate.of(2026, 12, 31));
    UUID taskId = insertRecurringTask("IT-tcr-skip-one", LocalDate.of(2026, 5, 6), ruleId);
    // Skip the 5/20 occurrence.
    insertSkipOverride(taskId, LocalDate.of(2026, 5, 20));

    List<TaskCalendarItem> tasks =
        onlyTasks(read(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 31)));
    assertThat(tasks).hasSize(3);
    assertThat(tasks.stream().map(TaskCalendarItem::date).toList())
        .containsExactlyInAnyOrder(
            LocalDate.of(2026, 5, 6), LocalDate.of(2026, 5, 13), LocalDate.of(2026, 5, 27));
  }

  @Test
  void parentSeesNoTasks() {
    insertNonRecurringTask("IT-tcr-parent-blocked", LocalDate.of(2026, 5, 15));

    List<CalendarItem> all =
        calendarReadService.read(
            SUNRISE, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), parent());
    assertThat(all.stream().filter(i -> i instanceof TaskCalendarItem)).isEmpty();
  }

  @Test
  void taskAtOtherSchoolNotReturned() {
    // Insert task at Maplewood; query window for Sunrise.
    UUID taskId = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO tasks "
            + "(id, org_id, school_id, title, assignee_user_id, due_date, status, priority, "
            + "created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'TODO', 'MEDIUM', ?)",
        taskId,
        ORG,
        MAPLEWOOD,
        "IT-tcr-other-school",
        MAYA,
        LocalDate.of(2026, 5, 10),
        OLIVIA);

    List<TaskCalendarItem> tasks =
        onlyTasks(read(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)));
    assertThat(tasks).isEmpty();
  }

  // -- raw inserts -----------------------------------------------------------

  private void insertNonRecurringTask(String title, LocalDate dueDate) {
    calendarJdbc.update(
        "INSERT INTO tasks "
            + "(id, org_id, school_id, title, assignee_user_id, due_date, status, priority, "
            + "created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'TODO', 'MEDIUM', ?)",
        UUID.randomUUID(),
        ORG,
        SUNRISE,
        title,
        MAYA,
        dueDate,
        OLIVIA);
  }

  /** Inserts a WEEKLY recurrence_rule. {@code jsDayOfWeek} is 0=Sun..6=Sat (FE convention). */
  private UUID insertWeeklyRule(int jsDayOfWeek, LocalDate untilDate) {
    UUID ruleId = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO recurrence_rules (id, cycle, due_day_of_week, until_date) "
            + "VALUES (?, 'WEEKLY', ?, ?)",
        ruleId,
        jsDayOfWeek,
        untilDate);
    return ruleId;
  }

  private UUID insertRecurringTask(String title, LocalDate dueDate, UUID ruleId) {
    UUID taskId = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO tasks "
            + "(id, org_id, school_id, title, assignee_user_id, due_date, status, priority, "
            + "recurrence_id, created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'TODO', 'MEDIUM', ?, ?)",
        taskId,
        ORG,
        SUNRISE,
        title,
        MAYA,
        dueDate,
        ruleId,
        OLIVIA);
    return taskId;
  }

  private void insertSkipOverride(UUID taskId, LocalDate occurrenceDate) {
    calendarJdbc.update(
        "INSERT INTO task_instance_overrides (id, task_id, occurrence_date, skipped) "
            + "VALUES (?, ?, ?, true)",
        UUID.randomUUID(),
        taskId,
        occurrenceDate);
  }

  // -- helpers ---------------------------------------------------------------

  private List<CalendarItem> read(LocalDate from, LocalDate to) {
    return calendarReadService.read(SUNRISE, from, to, admin());
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
        Set.of(SUNRISE, MAPLEWOOD),
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
