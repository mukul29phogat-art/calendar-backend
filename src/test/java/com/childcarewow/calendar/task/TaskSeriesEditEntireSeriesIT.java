package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.TaskOnHolidayException;
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
 * Real-DB IT for Part 9.5 — {@code PUT /api/v1/tasks/{id}/series} with {@code
 * choice=ENTIRE_SERIES}. Updates the master task fields in place; handles three recurrence states:
 * keep (recurrence null + removeRecurrence != true), update/create (recurrence non-null), or remove
 * (removeRecurrence == true → rule + all overrides dropped).
 */
@SpringBootTest
class TaskSeriesEditEntireSeriesIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");

  @Autowired TaskService taskService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-tsees-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-tsees-%'");
    calendarJdbc.update(
        "DELETE FROM task_instance_overrides WHERE task_id IN "
            + "(SELECT id FROM tasks WHERE title LIKE 'IT-tsees-%')");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-tsees-holiday-%'");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-tsees-%'");
    calendarJdbc.update(
        "DELETE FROM recurrence_rules WHERE id NOT IN "
            + "(SELECT recurrence_id FROM tasks WHERE recurrence_id IS NOT NULL)");
  }

  @Test
  void updateMasterTitleAcrossAllOccurrencesNoRuleChange() {
    LocalDate masterDue = LocalDate.of(2027, 9, 1);
    UUID taskId = createDailyRecurringTask("IT-tsees-orig", masterDue);
    UUID ruleId =
        calendarJdbc.queryForObject(
            "SELECT recurrence_id FROM tasks WHERE id = ?", UUID.class, taskId);

    TaskView updated =
        taskService.applySeriesEdit(
            taskId,
            new TaskSeriesEditRequest(
                EditChoice.ENTIRE_SERIES,
                masterDue,
                "IT-tsees-renamed",
                "new desc",
                null,
                masterDue,
                null,
                TaskStatus.IN_PROGRESS,
                TaskPriority.HIGH,
                null,
                null,
                null),
            admin());

    assertThat(updated.id()).isEqualTo(taskId);
    assertThat(updated.title()).isEqualTo("IT-tsees-renamed");
    assertThat(updated.priority()).isEqualTo(TaskPriority.HIGH);
    assertThat(updated.status()).isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(updated.recurrenceId()).isEqualTo(ruleId);

    String dbTitle =
        calendarJdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, taskId);
    assertThat(dbTitle).isEqualTo("IT-tsees-renamed");
  }

  @Test
  void removeRecurrenceDropsRuleAndAllOverrides() {
    LocalDate masterDue = LocalDate.of(2027, 9, 1);
    UUID taskId = createDailyRecurringTask("IT-tsees-derecur", masterDue);
    UUID ruleId =
        calendarJdbc.queryForObject(
            "SELECT recurrence_id FROM tasks WHERE id = ?", UUID.class, taskId);

    // Seed two overrides.
    taskService.applySeriesEdit(
        taskId,
        new TaskSeriesEditRequest(
            EditChoice.JUST_THIS, LocalDate.of(2027, 9, 3), "a", null, null, false),
        admin());
    taskService.applySeriesEdit(
        taskId,
        new TaskSeriesEditRequest(
            EditChoice.JUST_THIS, LocalDate.of(2027, 9, 5), "b", null, null, false),
        admin());

    TaskView updated =
        taskService.applySeriesEdit(
            taskId,
            new TaskSeriesEditRequest(
                EditChoice.ENTIRE_SERIES,
                masterDue,
                "IT-tsees-now-single",
                null,
                null,
                masterDue,
                null,
                TaskStatus.TODO,
                TaskPriority.MEDIUM,
                null,
                true, // removeRecurrence
                null),
            admin());

    assertThat(updated.recurrenceId()).isNull();

    Integer overridesLeft =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM task_instance_overrides WHERE task_id = ?",
            Integer.class,
            taskId);
    Integer ruleRows =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM recurrence_rules WHERE id = ?", Integer.class, ruleId);
    assertThat(overridesLeft).isZero();
    assertThat(ruleRows).isZero();
  }

  @Test
  void dueDateMoveToHolidayRejected() {
    LocalDate masterDue = LocalDate.of(2027, 9, 1);
    LocalDate newDue = LocalDate.of(2027, 9, 7);
    UUID taskId = createDailyRecurringTask("IT-tsees-move-holiday", masterDue);

    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        ORG,
        SUNRISE,
        java.sql.Date.valueOf(newDue),
        "IT-tsees-holiday-labor",
        "CUSTOM",
        true,
        OLIVIA);

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    taskId,
                    new TaskSeriesEditRequest(
                        EditChoice.ENTIRE_SERIES,
                        masterDue,
                        "x",
                        null,
                        null,
                        newDue,
                        null,
                        TaskStatus.TODO,
                        TaskPriority.MEDIUM,
                        null,
                        null,
                        null),
                    admin()))
        .isInstanceOf(TaskOnHolidayException.class);
  }

  @Test
  void sameDateNoHolidayCheck() {
    // Regression — sames-date update must not fire the holiday SELECT (mirrors EventService's
    // startMoved gate).
    LocalDate masterDue = LocalDate.of(2027, 9, 1);
    UUID taskId = createDailyRecurringTask("IT-tsees-no-move", masterDue);

    // Seed a holiday on masterDue itself. The hard-block on CREATE would normally reject this, but
    // we seed AFTER create. Update with same dueDate should succeed (skipping the holiday check).
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        ORG,
        SUNRISE,
        java.sql.Date.valueOf(masterDue),
        "IT-tsees-holiday-coincidence",
        "CUSTOM",
        true,
        OLIVIA);

    TaskView updated =
        taskService.applySeriesEdit(
            taskId,
            new TaskSeriesEditRequest(
                EditChoice.ENTIRE_SERIES,
                masterDue,
                "IT-tsees-still-ok",
                null,
                null,
                masterDue,
                null,
                TaskStatus.TODO,
                TaskPriority.MEDIUM,
                null,
                null,
                null),
            admin());

    assertThat(updated.title()).isEqualTo("IT-tsees-still-ok");
  }

  @Test
  void firstOccurrenceCollapseFromThisAndFollowingFallsThrough() {
    // 9.4's first-occurrence collapse case: occurrenceDate == master.dueDate with
    // choice=THIS_AND_FOLLOWING now routes to the ENTIRE_SERIES handler (instead of rejecting).
    LocalDate masterDue = LocalDate.of(2027, 9, 1);
    UUID taskId = createDailyRecurringTask("IT-tsees-collapse", masterDue);

    TaskView updated =
        taskService.applySeriesEdit(
            taskId,
            new TaskSeriesEditRequest(
                EditChoice.THIS_AND_FOLLOWING,
                masterDue,
                "IT-tsees-collapsed-into-entire",
                null,
                null,
                masterDue,
                null,
                TaskStatus.TODO,
                TaskPriority.MEDIUM,
                null,
                null,
                null),
            admin());

    assertThat(updated.id()).isEqualTo(taskId);
    assertThat(updated.title()).isEqualTo("IT-tsees-collapsed-into-entire");
  }

  @Test
  void missingDueDateRejected() {
    UUID taskId = createDailyRecurringTask("IT-tsees-no-due", LocalDate.of(2027, 9, 1));

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    taskId,
                    new TaskSeriesEditRequest(
                        EditChoice.ENTIRE_SERIES,
                        LocalDate.of(2027, 9, 1),
                        "x",
                        null,
                        null,
                        null,
                        null,
                        TaskStatus.TODO,
                        TaskPriority.MEDIUM,
                        null,
                        null,
                        null),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("dueDate");
  }

  // -- helpers ---------------------------------------------------------------

  private UUID createDailyRecurringTask(String title, LocalDate dueDate) {
    RecurrenceSpec spec =
        new RecurrenceSpec(RecurCycle.DAILY, null, null, null, dueDate.plusDays(30));
    return taskService
        .create(
            new CreateTaskRequest(
                title,
                null,
                SUNRISE,
                null,
                List.of(MAYA),
                dueDate,
                null,
                TaskStatus.TODO,
                TaskPriority.MEDIUM,
                spec),
            admin())
        .get(0)
        .id();
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
