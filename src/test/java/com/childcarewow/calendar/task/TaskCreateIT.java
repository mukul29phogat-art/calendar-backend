package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.TaskOnHolidayException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.holiday.CreateHolidayRequest;
import com.childcarewow.calendar.holiday.HolidayService;
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
 * Real-DB IT for {@link TaskService#create}. Exercises the full Part 8.1 create flow: validation,
 * holiday block on dueDate, platform-entity assertions, soft-flag recompute, notification dispatch
 * (TASK_ASSIGNED). Multi-assignee fan-out is intentionally NOT here — that's Part 8.2.
 */
@SpringBootTest
class TaskCreateIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
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
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-tc-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-tc-%'");
    calendarJdbc.update(
        "DELETE FROM conflict_flags WHERE conflicting_entity_id IN "
            + "(SELECT id FROM holidays WHERE name LIKE 'IT-tc-%')");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-tc-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-tc-%'");
  }

  @Test
  void happyPathCreatesTaskWithAllFieldsPopulated() {
    LocalDate due = LocalDate.of(2026, 9, 15);
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tc-grade-papers",
            "weekly grading",
            SUNRISE,
            BUTTERFLIES,
            List.of(MAYA),
            due,
            LocalTime.of(15, 0),
            TaskStatus.TODO,
            TaskPriority.HIGH);

    TaskView v = taskService.create(req, admin());

    assertThat(v.id()).isNotNull();
    assertThat(v.title()).isEqualTo("IT-tc-grade-papers");
    assertThat(v.description()).isEqualTo("weekly grading");
    assertThat(v.schoolId()).isEqualTo(SUNRISE);
    assertThat(v.classroomId()).isEqualTo(BUTTERFLIES);
    assertThat(v.assigneeUserId()).isEqualTo(MAYA);
    assertThat(v.dueDate()).isEqualTo(due);
    assertThat(v.dueTime()).isEqualTo(LocalTime.of(15, 0));
    assertThat(v.status()).isEqualTo(TaskStatus.TODO);
    assertThat(v.priority()).isEqualTo(TaskPriority.HIGH);
    assertThat(v.createdAt()).isNotNull();
  }

  @Test
  void statusAndPriorityDefaultWhenOmitted() {
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tc-default-fields",
            null,
            SUNRISE,
            null,
            List.of(MAYA),
            LocalDate.of(2026, 9, 16),
            null,
            null,
            null);

    TaskView v = taskService.create(req, admin());

    assertThat(v.status()).isEqualTo(TaskStatus.TODO);
    assertThat(v.priority()).isEqualTo(TaskPriority.MEDIUM);
  }

  @Test
  void multiAssigneeRejectedUntilPart8_2() {
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tc-multi-assignee",
            null,
            SUNRISE,
            null,
            List.of(MAYA, TOM),
            LocalDate.of(2026, 9, 17),
            null,
            null,
            null);

    assertThatThrownBy(() -> taskService.create(req, admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Part 8.2");
  }

  @Test
  void holidayOnDueDateBlocksCreation() {
    LocalDate date = LocalDate.of(2026, 12, 25);
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-tc-Christmas", null), admin());

    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tc-blocked", null, SUNRISE, null, List.of(MAYA), date, null, null, null);

    assertThatThrownBy(() -> taskService.create(req, admin()))
        .isInstanceOf(TaskOnHolidayException.class)
        .hasMessageContaining("IT-tc-Christmas");

    // No task row was inserted.
    Integer count =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM tasks WHERE title = ?", Integer.class, "IT-tc-blocked");
    assertThat(count).isZero();
  }

  @Test
  void classroomInDifferentSchoolRejected() {
    // CATERPILLARS is at Sunrise per seed. Let me actually use a Maplewood classroom to force a
    // school-mismatch — Sunbeams (Maplewood) and BUTTERFLIES (Sunrise).
    UUID sunbeams = UUID.fromString("44444444-0000-0000-0000-000000000003");
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tc-mismatched-classroom",
            null,
            SUNRISE,
            sunbeams,
            List.of(MAYA),
            LocalDate.of(2026, 9, 18),
            null,
            null,
            null);

    assertThatThrownBy(() -> taskService.create(req, admin()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void unknownAssigneeRejected() {
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tc-bad-assignee",
            null,
            SUNRISE,
            null,
            List.of(UUID.fromString("99999999-0000-0000-0000-000000000099")),
            LocalDate.of(2026, 9, 19),
            null,
            null,
            null);

    assertThatThrownBy(() -> taskService.create(req, admin()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void notificationRowAndRecipientWrittenForAssignee() {
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tc-notify-maya",
            null,
            SUNRISE,
            null,
            List.of(MAYA),
            LocalDate.of(2026, 9, 20),
            null,
            null,
            null);

    TaskView v = taskService.create(req, admin());

    Integer notifCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE related_entity_id = ? AND kind = 'TASK_ASSIGNED'",
            Integer.class,
            v.id());
    assertThat(notifCount).isEqualTo(1);

    UUID recipient =
        calendarJdbc.queryForObject(
            "SELECT user_id FROM notification_recipients nr "
                + "JOIN notifications n ON n.id = nr.notification_id "
                + "WHERE n.related_entity_id = ?",
            UUID.class,
            v.id());
    assertThat(recipient).isEqualTo(MAYA);
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
}
