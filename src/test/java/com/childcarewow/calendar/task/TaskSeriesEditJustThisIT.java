package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.InvalidRecurrenceException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.task.CreateTaskRequest.RecurrenceSpec;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * Real-DB IT for Part 9.3 — {@code PUT /api/v1/tasks/{id}/series} with {@code choice=JUST_THIS}.
 * The handler upserts a {@link TaskInstanceOverride} row keyed on {@code (taskId, occurrenceDate)}
 * and leaves the master task untouched (only {@code updatedByUserId} bumps).
 */
@SpringBootTest
class TaskSeriesEditJustThisIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");

  @Autowired TaskService taskService;
  @Autowired TaskInstanceOverrideRepository overrideRepo;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-tseje-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-tseje-%'");
    calendarJdbc.update(
        "DELETE FROM task_instance_overrides WHERE task_id IN "
            + "(SELECT id FROM tasks WHERE title LIKE 'IT-tseje-%')");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-tseje-%'");
    calendarJdbc.update(
        "DELETE FROM recurrence_rules WHERE id NOT IN "
            + "(SELECT recurrence_id FROM tasks WHERE recurrence_id IS NOT NULL)");
  }

  @Test
  void justThisUpsertsOverrideForOccurrenceDate() {
    UUID taskId = createDailyRecurringTask("IT-tseje-base", LocalDate.of(2027, 8, 2));

    TaskSeriesEditRequest req =
        new TaskSeriesEditRequest(
            EditChoice.JUST_THIS,
            LocalDate.of(2027, 8, 5),
            "IT-tseje-overridden-title",
            LocalTime.of(14, 30),
            TaskStatus.IN_PROGRESS,
            false);

    TaskView returned = taskService.applySeriesEdit(taskId, req, admin());
    assertThat(returned.id()).isEqualTo(taskId);

    // Row landed with the three override fields populated.
    String overrideTitle =
        calendarJdbc.queryForObject(
            "SELECT title FROM task_instance_overrides WHERE task_id = ? AND occurrence_date = ?",
            String.class,
            taskId,
            LocalDate.of(2027, 8, 5));
    String overrideStatus =
        calendarJdbc.queryForObject(
            "SELECT status FROM task_instance_overrides WHERE task_id = ? AND occurrence_date = ?",
            String.class,
            taskId,
            LocalDate.of(2027, 8, 5));
    // Read via JPA so Hibernate's LocalTime mapping (not raw JDBC Time, which round-trips through
    // the local timezone) reflects the true stored value.
    LocalTime overrideDueTime =
        overrideRepo
            .findByTaskIdAndOccurrenceDate(taskId, LocalDate.of(2027, 8, 5))
            .orElseThrow()
            .getDueTime();
    Boolean overrideSkipped =
        calendarJdbc.queryForObject(
            "SELECT skipped FROM task_instance_overrides WHERE task_id = ? AND occurrence_date = ?",
            Boolean.class,
            taskId,
            LocalDate.of(2027, 8, 5));

    assertThat(overrideTitle).isEqualTo("IT-tseje-overridden-title");
    assertThat(overrideStatus).isEqualTo("IN_PROGRESS");
    assertThat(overrideDueTime).isEqualTo(LocalTime.of(14, 30));
    assertThat(overrideSkipped).isFalse();

    // Master task title untouched.
    String masterTitle =
        calendarJdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, taskId);
    assertThat(masterTitle).isEqualTo("IT-tseje-base");
  }

  @Test
  void justThisIsIdempotentSecondCallOverwritesFirst() {
    UUID taskId = createDailyRecurringTask("IT-tseje-idem", LocalDate.of(2027, 8, 2));
    LocalDate occ = LocalDate.of(2027, 8, 6);

    taskService.applySeriesEdit(
        taskId,
        new TaskSeriesEditRequest(EditChoice.JUST_THIS, occ, "first", null, null, false),
        admin());

    taskService.applySeriesEdit(
        taskId,
        new TaskSeriesEditRequest(EditChoice.JUST_THIS, occ, "second", null, null, false),
        admin());

    // Exactly one row by the UNIQUE constraint on (task_id, occurrence_date), and the second
    // payload won.
    Integer rowCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM task_instance_overrides WHERE task_id = ? AND occurrence_date = ?",
            Integer.class,
            taskId,
            occ);
    String title =
        calendarJdbc.queryForObject(
            "SELECT title FROM task_instance_overrides WHERE task_id = ? AND occurrence_date = ?",
            String.class,
            taskId,
            occ);
    assertThat(rowCount).isEqualTo(1);
    assertThat(title).isEqualTo("second");
  }

  @Test
  void justThisWithSkippedTrueMarksDateSkipped() {
    UUID taskId = createDailyRecurringTask("IT-tseje-skip", LocalDate.of(2027, 8, 2));
    LocalDate occ = LocalDate.of(2027, 8, 7);

    taskService.applySeriesEdit(
        taskId,
        new TaskSeriesEditRequest(EditChoice.JUST_THIS, occ, null, null, null, true),
        admin());

    Boolean skipped =
        calendarJdbc.queryForObject(
            "SELECT skipped FROM task_instance_overrides WHERE task_id = ? AND occurrence_date = ?",
            Boolean.class,
            taskId,
            occ);
    assertThat(skipped).isTrue();
  }

  @Test
  void justThisOnNonRecurringTaskRejected() {
    UUID taskId = createNonRecurringTask("IT-tseje-non-recur", LocalDate.of(2027, 8, 2));

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    taskId,
                    new TaskSeriesEditRequest(
                        EditChoice.JUST_THIS, LocalDate.of(2027, 8, 5), null, null, null, false),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("not recurring");
  }

  @Test
  void justThisWithOccurrenceDateOutsideRuleWindowRejected() {
    // DAILY rule from 2027-08-02 to 2027-08-16. A date outside the [taskDueDate, untilDate]
    // range is not in the rule's expansion → InvalidRecurrenceException.
    UUID taskId = createDailyRecurringTask("IT-tseje-outside", LocalDate.of(2027, 8, 2));

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    taskId,
                    new TaskSeriesEditRequest(
                        EditChoice.JUST_THIS, LocalDate.of(2027, 9, 1), null, null, null, false),
                    admin()))
        .isInstanceOf(InvalidRecurrenceException.class);
  }

  @Test
  void thisAndFollowingRejectedUntilPart9_4() {
    UUID taskId = createDailyRecurringTask("IT-tseje-taf", LocalDate.of(2027, 8, 2));

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    taskId,
                    new TaskSeriesEditRequest(
                        EditChoice.THIS_AND_FOLLOWING,
                        LocalDate.of(2027, 8, 5),
                        null,
                        null,
                        null,
                        false),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Part 9.4");
  }

  @Test
  void entireSeriesRejectedUntilPart9_5() {
    UUID taskId = createDailyRecurringTask("IT-tseje-es", LocalDate.of(2027, 8, 2));

    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    taskId,
                    new TaskSeriesEditRequest(
                        EditChoice.ENTIRE_SERIES,
                        LocalDate.of(2027, 8, 5),
                        null,
                        null,
                        null,
                        false),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Part 9.5");
  }

  @Test
  void unknownTaskIdReturns404() {
    assertThatThrownBy(
            () ->
                taskService.applySeriesEdit(
                    UUID.fromString("99999999-0000-0000-0000-000000000099"),
                    new TaskSeriesEditRequest(
                        EditChoice.JUST_THIS, LocalDate.of(2027, 8, 5), null, null, null, false),
                    admin()))
        .isInstanceOf(NotFoundException.class);
  }

  // -- helpers ---------------------------------------------------------------

  private UUID createDailyRecurringTask(String title, LocalDate dueDate) {
    RecurrenceSpec spec =
        new RecurrenceSpec(RecurCycle.DAILY, null, null, null, dueDate.plusDays(14));
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

  private UUID createNonRecurringTask(String title, LocalDate dueDate) {
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
                TaskPriority.MEDIUM),
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
