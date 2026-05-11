package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.exception.TaskOnHolidayException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.holiday.CreateHolidayRequest;
import com.childcarewow.calendar.holiday.HolidayService;
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
 * Real-DB IT for {@link TaskService#update}. Exercises the Part 8.4 update flow: pre-update
 * snapshot, holiday block on date-moves only, immutable schoolId, soft-flag recompute on save,
 * notification dispatch diff (TASK_UPDATED / TASK_STATUS_CHANGED / TASK_ASSIGNED on reassignment).
 */
@SpringBootTest
class TaskUpdateIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");

  @Autowired TaskService taskService;
  @Autowired HolidayService holidayService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-tu-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-tu-%'");
    calendarJdbc.update(
        "DELETE FROM conflict_flags WHERE conflicting_entity_id IN "
            + "(SELECT id FROM holidays WHERE name LIKE 'IT-tu-%')");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-tu-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-tu-%'");
  }

  @Test
  void titleAndPriorityUpdateInPlace() {
    UUID id =
        createTaskAndGetId("IT-tu-original", LocalDate.of(2026, 9, 10), MAYA, TaskStatus.TODO);

    TaskView updated =
        taskService.update(
            id,
            requestFor(
                "IT-tu-renamed",
                "now important",
                MAYA,
                LocalDate.of(2026, 9, 10),
                TaskStatus.TODO,
                TaskPriority.HIGH),
            admin());

    assertThat(updated.title()).isEqualTo("IT-tu-renamed");
    assertThat(updated.description()).isEqualTo("now important");
    assertThat(updated.priority()).isEqualTo(TaskPriority.HIGH);
    assertThat(updated.assigneeUserId()).isEqualTo(MAYA);
  }

  @Test
  void statusChangeWritesTaskStatusChangedNotification() {
    UUID id = createTaskAndGetId("IT-tu-status", LocalDate.of(2026, 9, 11), MAYA, TaskStatus.TODO);

    taskService.update(
        id,
        requestFor(
            "IT-tu-status",
            null,
            MAYA,
            LocalDate.of(2026, 9, 11),
            TaskStatus.IN_PROGRESS,
            TaskPriority.MEDIUM),
        admin());

    Integer count =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications "
                + "WHERE related_entity_id = ? AND kind = 'TASK_STATUS_CHANGED'",
            Integer.class,
            id);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void dateMoveToHolidayThrows() {
    UUID id =
        createTaskAndGetId("IT-tu-date-block", LocalDate.of(2026, 12, 24), MAYA, TaskStatus.TODO);
    LocalDate holidayDate = LocalDate.of(2026, 12, 25);
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, holidayDate, "IT-tu-Christmas", null), admin());

    assertThatThrownBy(
            () ->
                taskService.update(
                    id,
                    requestFor(
                        "IT-tu-date-block",
                        null,
                        MAYA,
                        holidayDate,
                        TaskStatus.TODO,
                        TaskPriority.MEDIUM),
                    admin()))
        .isInstanceOf(TaskOnHolidayException.class)
        .hasMessageContaining("IT-tu-Christmas");
  }

  @Test
  void sameDateEditDoesNotRecheckHolidayTable() {
    // Holiday on Dec 25 exists; task is on Dec 26. A title-only edit (no date move) must NOT throw
    // even though a holiday is in the table at a nearby date.
    UUID id =
        createTaskAndGetId("IT-tu-same-date", LocalDate.of(2026, 12, 26), MAYA, TaskStatus.TODO);
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, LocalDate.of(2026, 12, 25), "IT-tu-Xmas-Adj", null),
        admin());

    // Title-only change; due_date stays at 12/26 (non-holiday). Should succeed.
    TaskView updated =
        taskService.update(
            id,
            requestFor(
                "IT-tu-same-date-renamed",
                null,
                MAYA,
                LocalDate.of(2026, 12, 26),
                TaskStatus.TODO,
                TaskPriority.MEDIUM),
            admin());
    assertThat(updated.title()).isEqualTo("IT-tu-same-date-renamed");
  }

  @Test
  void reassignmentDispatchesTaskAssignedToNewAndTaskUpdatedToOld() {
    UUID id =
        createTaskAndGetId("IT-tu-reassign", LocalDate.of(2026, 9, 13), MAYA, TaskStatus.TODO);

    taskService.update(
        id,
        requestFor(
            "IT-tu-reassign",
            null,
            TOM,
            LocalDate.of(2026, 9, 13),
            TaskStatus.TODO,
            TaskPriority.MEDIUM),
        admin());

    Integer assigned =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_recipients nr "
                + "JOIN notifications n ON n.id = nr.notification_id "
                + "WHERE n.related_entity_id = ? AND n.kind = 'TASK_ASSIGNED' AND nr.user_id = ?",
            Integer.class,
            id,
            TOM);
    Integer headsUp =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_recipients nr "
                + "JOIN notifications n ON n.id = nr.notification_id "
                + "WHERE n.related_entity_id = ? AND n.kind = 'TASK_UPDATED' AND nr.user_id = ?",
            Integer.class,
            id,
            MAYA);
    assertThat(assigned).as("TASK_ASSIGNED to new assignee (Tom)").isEqualTo(1);
    assertThat(headsUp).as("TASK_UPDATED heads-up to prior assignee (Maya)").isEqualTo(1);
  }

  @Test
  void schoolIdImmutableOnUpdate() {
    UUID id =
        createTaskAndGetId("IT-tu-immutable", LocalDate.of(2026, 9, 14), MAYA, TaskStatus.TODO);

    CreateTaskRequest badReq =
        new CreateTaskRequest(
            "IT-tu-immutable",
            null,
            MAPLEWOOD, // moved to another school
            null,
            List.of(MAYA),
            LocalDate.of(2026, 9, 14),
            null,
            TaskStatus.TODO,
            TaskPriority.MEDIUM);

    assertThatThrownBy(() -> taskService.update(id, badReq, admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("schoolId");
  }

  @Test
  void unknownIdReturnsNotFound() {
    UUID bogus = UUID.fromString("99999999-0000-0000-0000-000000000099");
    assertThatThrownBy(
            () ->
                taskService.update(
                    bogus,
                    requestFor(
                        "IT-tu-bogus",
                        null,
                        MAYA,
                        LocalDate.of(2026, 9, 15),
                        TaskStatus.TODO,
                        TaskPriority.MEDIUM),
                    admin()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void multiAssigneeOnPutRejected() {
    UUID id =
        createTaskAndGetId("IT-tu-multi-put", LocalDate.of(2026, 9, 16), MAYA, TaskStatus.TODO);

    CreateTaskRequest badReq =
        new CreateTaskRequest(
            "IT-tu-multi-put",
            null,
            SUNRISE,
            null,
            List.of(MAYA, TOM),
            LocalDate.of(2026, 9, 16),
            null,
            TaskStatus.TODO,
            TaskPriority.MEDIUM);

    assertThatThrownBy(() -> taskService.update(id, badReq, admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("single-assignee");
  }

  @Test
  void titleOnlyChangeWritesTaskUpdated() {
    UUID id = createTaskAndGetId("IT-tu-title", LocalDate.of(2026, 9, 17), MAYA, TaskStatus.TODO);

    taskService.update(
        id,
        requestFor(
            "IT-tu-title-renamed",
            null,
            MAYA,
            LocalDate.of(2026, 9, 17),
            TaskStatus.TODO,
            TaskPriority.MEDIUM),
        admin());

    Integer count =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE related_entity_id = ? AND kind = 'TASK_UPDATED'",
            Integer.class,
            id);
    assertThat(count).isEqualTo(1);
  }

  // -- helpers ---------------------------------------------------------------

  private UUID createTaskAndGetId(
      String title, LocalDate dueDate, UUID assignee, TaskStatus status) {
    return taskService
        .create(
            new CreateTaskRequest(
                title,
                null,
                SUNRISE,
                null,
                List.of(assignee),
                dueDate,
                null,
                status,
                TaskPriority.MEDIUM),
            admin())
        .get(0)
        .id();
  }

  private static CreateTaskRequest requestFor(
      String title,
      String description,
      UUID assignee,
      LocalDate dueDate,
      TaskStatus status,
      TaskPriority priority) {
    return new CreateTaskRequest(
        title, description, SUNRISE, null, List.of(assignee), dueDate, null, status, priority);
  }

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE, MAPLEWOOD),
        Set.of(BUTTERFLIES, CATERPILLARS),
        Set.of(),
        "Owner");
  }
}
