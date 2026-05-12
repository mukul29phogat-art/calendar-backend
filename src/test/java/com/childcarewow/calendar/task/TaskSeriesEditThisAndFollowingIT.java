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
 * Real-DB IT for Part 9.4 — {@code PUT /api/v1/tasks/{id}/series} with {@code
 * choice=THIS_AND_FOLLOWING}. The handler shortens the master rule's {@code until_date} to {@code
 * occurrenceDate - 1 day}, drops overrides at/after the split, and creates a new task at {@code
 * occurrenceDate} with its own recurrence rule (per D9). The new task shares {@code
 * parent_task_group_id} with the master.
 */
@SpringBootTest
class TaskSeriesEditThisAndFollowingIT {

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
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-tsetaf-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-tsetaf-%'");
    calendarJdbc.update(
        "DELETE FROM task_instance_overrides WHERE task_id IN "
            + "(SELECT id FROM tasks WHERE title LIKE 'IT-tsetaf-%')");
    // Drop CUSTOM holidays seeded by tests in this file.
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-tsetaf-holiday-%'");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-tsetaf-%'");
    calendarJdbc.update(
        "DELETE FROM recurrence_rules WHERE id NOT IN "
            + "(SELECT recurrence_id FROM tasks WHERE recurrence_id IS NOT NULL)");
  }

  @Test
  void shortenMasterRuleAndCreateNewTaskAtSplit() {
    LocalDate masterDue = LocalDate.of(2027, 8, 2);
    LocalDate split = LocalDate.of(2027, 8, 10);
    UUID masterId = createDailyRecurringTask("IT-tsetaf-master", masterDue);

    UUID masterRuleId =
        calendarJdbc.queryForObject(
            "SELECT recurrence_id FROM tasks WHERE id = ?", UUID.class, masterId);

    TaskView newTaskView =
        taskService.applySeriesEdit(
            masterId,
            new TaskSeriesEditRequest(
                EditChoice.THIS_AND_FOLLOWING,
                split,
                "IT-tsetaf-new-after-split",
                "post-split desc",
                null,
                TaskStatus.TODO,
                TaskPriority.HIGH,
                false,
                new RecurrenceSpec(RecurCycle.DAILY, null, null, null, LocalDate.of(2027, 9, 1))),
            admin());

    // New task fields
    assertThat(newTaskView.id()).isNotEqualTo(masterId);
    assertThat(newTaskView.title()).isEqualTo("IT-tsetaf-new-after-split");
    assertThat(newTaskView.priority()).isEqualTo(TaskPriority.HIGH);
    assertThat(newTaskView.dueDate()).isEqualTo(split);
    assertThat(newTaskView.recurrenceId()).isNotNull();
    assertThat(newTaskView.recurrenceId()).isNotEqualTo(masterRuleId);

    // Master's rule until_date shortened to the day BEFORE split.
    java.sql.Date masterUntil =
        calendarJdbc.queryForObject(
            "SELECT until_date FROM recurrence_rules WHERE id = ?",
            java.sql.Date.class,
            masterRuleId);
    assertThat(masterUntil.toLocalDate()).isEqualTo(split.minusDays(1));

    // Both rows share parent_task_group_id (the original master was single-assignee, so this is
    // null on both — orthogonal to recurrence per Part 9.2).
    UUID masterGroup =
        calendarJdbc.queryForObject(
            "SELECT parent_task_group_id FROM tasks WHERE id = ?", UUID.class, masterId);
    UUID newGroup =
        calendarJdbc.queryForObject(
            "SELECT parent_task_group_id FROM tasks WHERE id = ?", UUID.class, newTaskView.id());
    assertThat(newGroup).isEqualTo(masterGroup);
  }

  @Test
  void splitDropsOverridesAtOrAfterSplitDate() {
    LocalDate masterDue = LocalDate.of(2027, 8, 2);
    LocalDate split = LocalDate.of(2027, 8, 10);
    UUID masterId = createDailyRecurringTask("IT-tsetaf-drop-overrides", masterDue);

    // Seed two overrides: one BEFORE split (should survive), one AT/AFTER split (should be
    // dropped by the THIS_AND_FOLLOWING handler).
    taskService.applySeriesEdit(
        masterId,
        new TaskSeriesEditRequest(
            EditChoice.JUST_THIS, LocalDate.of(2027, 8, 5), "early", null, null, false),
        admin());
    taskService.applySeriesEdit(
        masterId,
        new TaskSeriesEditRequest(
            EditChoice.JUST_THIS, LocalDate.of(2027, 8, 12), "late", null, null, false),
        admin());

    taskService.applySeriesEdit(
        masterId,
        new TaskSeriesEditRequest(
            EditChoice.THIS_AND_FOLLOWING,
            split,
            "IT-tsetaf-new-after-split",
            null,
            null,
            TaskStatus.TODO,
            TaskPriority.MEDIUM,
            false,
            null),
        admin());

    Integer beforeCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM task_instance_overrides "
                + "WHERE task_id = ? AND occurrence_date < ?",
            Integer.class,
            masterId,
            split);
    Integer afterCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM task_instance_overrides "
                + "WHERE task_id = ? AND occurrence_date >= ?",
            Integer.class,
            masterId,
            split);
    assertThat(beforeCount).isEqualTo(1);
    assertThat(afterCount).isZero();
  }

  @Test
  void firstOccurrenceCollapseRejectedUntilPart9_5() {
    LocalDate masterDue = LocalDate.of(2027, 8, 2);
    UUID masterId = createDailyRecurringTask("IT-tsetaf-collapse", masterDue);

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    masterId,
                    new TaskSeriesEditRequest(
                        EditChoice.THIS_AND_FOLLOWING,
                        masterDue,
                        "x",
                        null,
                        null,
                        TaskStatus.TODO,
                        TaskPriority.MEDIUM,
                        false,
                        null),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ENTIRE_SERIES");
  }

  @Test
  void holidayOnSplitDateRejected() {
    LocalDate masterDue = LocalDate.of(2027, 8, 2);
    LocalDate split = LocalDate.of(2027, 8, 11);
    UUID masterId = createDailyRecurringTask("IT-tsetaf-holiday-split", masterDue);

    // Seed an approved CUSTOM holiday on the split date.
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        ORG,
        SUNRISE,
        java.sql.Date.valueOf(split),
        "IT-tsetaf-holiday-split-day",
        "CUSTOM",
        true,
        OLIVIA);

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    masterId,
                    new TaskSeriesEditRequest(
                        EditChoice.THIS_AND_FOLLOWING,
                        split,
                        "x",
                        null,
                        null,
                        TaskStatus.TODO,
                        TaskPriority.MEDIUM,
                        false,
                        null),
                    admin()))
        .isInstanceOf(TaskOnHolidayException.class);
  }

  @Test
  void missingTitleRejected() {
    LocalDate masterDue = LocalDate.of(2027, 8, 2);
    UUID masterId = createDailyRecurringTask("IT-tsetaf-no-title", masterDue);

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    masterId,
                    new TaskSeriesEditRequest(
                        EditChoice.THIS_AND_FOLLOWING,
                        LocalDate.of(2027, 8, 5),
                        null,
                        null,
                        null,
                        TaskStatus.TODO,
                        TaskPriority.MEDIUM,
                        false,
                        null),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("title");
  }

  // -- helpers ---------------------------------------------------------------

  private UUID createDailyRecurringTask(String title, LocalDate dueDate) {
    RecurrenceSpec spec =
        new RecurrenceSpec(RecurCycle.DAILY, null, null, null, LocalDate.of(2027, 8, 31));
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
