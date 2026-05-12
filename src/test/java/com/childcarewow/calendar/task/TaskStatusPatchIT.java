package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.exception.ValidationException;
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
 * Real-DB IT for {@link TaskService#updateStatus} — the focused PATCH-status path (Part 8.5). The
 * full PUT update flow lives in {@code TaskUpdateIT}; this file exercises the smaller surface:
 * status-only mutation, idempotent no-op, soft-flag recompute on TODO ↔ DONE (the DOUBLE_BOOKING
 * rule excludes DONE per Part 3.12, so a status transition can clear/introduce overlap pairs).
 */
@SpringBootTest
class TaskStatusPatchIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
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
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-ts-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-ts-%'");
    calendarJdbc.update(
        "DELETE FROM conflict_flags WHERE entity_type = 'TASK' AND entity_id IN "
            + "(SELECT id FROM tasks WHERE title LIKE 'IT-ts-%')");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-ts-%'");
  }

  @Test
  void transitionToDoneWritesTaskStatusChanged() {
    // FE prototype contract: TASK_STATUS_CHANGED is reserved for the "marked done" transition.
    UUID id = createTask("IT-ts-to-done", LocalDate.of(2026, 9, 10), TaskStatus.TODO);

    TaskView updated = taskService.updateStatus(id, TaskStatus.DONE, admin());

    assertThat(updated.status()).isEqualTo(TaskStatus.DONE);
    Integer count =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications "
                + "WHERE related_entity_id = ? AND kind = 'TASK_STATUS_CHANGED'",
            Integer.class,
            id);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void todoToInProgressWritesTaskUpdatedNotStatusChanged() {
    // Non-DONE status transition → TASK_UPDATED, NOT TASK_STATUS_CHANGED. Pins the FE prototype
    // contract on the PATCH path (parallels TaskUpdateIT's PUT-side coverage of the same rule).
    UUID id = createTask("IT-ts-go-in-progress", LocalDate.of(2026, 9, 13), TaskStatus.TODO);

    taskService.updateStatus(id, TaskStatus.IN_PROGRESS, admin());

    Integer statusChanged =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications "
                + "WHERE related_entity_id = ? AND kind = 'TASK_STATUS_CHANGED'",
            Integer.class,
            id);
    Integer updated =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications "
                + "WHERE related_entity_id = ? AND kind = 'TASK_UPDATED'",
            Integer.class,
            id);
    assertThat(statusChanged).isZero();
    assertThat(updated).isEqualTo(1);
  }

  @Test
  void sameStatusIsNoOpAndWritesNoStatusChangedNotification() {
    UUID id = createTask("IT-ts-noop", LocalDate.of(2026, 9, 11), TaskStatus.TODO);

    TaskView returned = taskService.updateStatus(id, TaskStatus.TODO, admin());
    assertThat(returned.status()).isEqualTo(TaskStatus.TODO);

    // create() already wrote a TASK_ASSIGNED row for the new task; the noop PATCH must add zero
    // TASK_STATUS_CHANGED rows on top.
    Integer changed =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications "
                + "WHERE related_entity_id = ? AND kind = 'TASK_STATUS_CHANGED'",
            Integer.class,
            id);
    assertThat(changed).isZero();
  }

  @Test
  void unknownIdReturns404() {
    assertThatThrownBy(
            () ->
                taskService.updateStatus(
                    UUID.fromString("99999999-0000-0000-0000-000000000099"),
                    TaskStatus.DONE,
                    admin()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void softDeletedTaskReturns404() {
    UUID id = createTask("IT-ts-soft-deleted", LocalDate.of(2026, 9, 12), TaskStatus.TODO);
    calendarJdbc.update("UPDATE tasks SET deleted_at = now() WHERE id = ?", id);

    assertThatThrownBy(() -> taskService.updateStatus(id, TaskStatus.DONE, admin()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void nullStatusRejected() {
    UUID id = createTask("IT-ts-null-status", LocalDate.of(2026, 9, 13), TaskStatus.TODO);

    assertThatThrownBy(() -> taskService.updateStatus(id, null, admin()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void transitionToDoneClearsOverlapPairFlags() {
    // Two same-school + same-assignee + same-dueDate tasks with both dueTimes null → conflict.
    // Per Part 3.12, both rows get DOUBLE_BOOKING flags pointing at each other.
    LocalDate date = LocalDate.of(2026, 9, 14);
    UUID t1 = createTask("IT-ts-overlap-a", date, TaskStatus.TODO);
    UUID t2 = createTask("IT-ts-overlap-b", date, TaskStatus.TODO);

    // After both inserts (via TaskService.create), the soft-flag pair should exist.
    Integer pairCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'TASK' AND conflict_type = 'DOUBLE_BOOKING' "
                + "AND (entity_id = ? OR entity_id = ?)",
            Integer.class,
            t1,
            t2);
    assertThat(pairCount).as("DOUBLE_BOOKING pair present before status change").isEqualTo(2);

    // Transition one to DONE → recomputeForTask runs → the DONE task is excluded from the conflict
    // rule → both flags involving it should clear.
    taskService.updateStatus(t1, TaskStatus.DONE, admin());

    Integer remaining =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'TASK' AND conflict_type = 'DOUBLE_BOOKING' "
                + "AND (entity_id = ? OR entity_id = ? OR conflicting_entity_id = ? "
                + "OR conflicting_entity_id = ?)",
            Integer.class,
            t1,
            t2,
            t1,
            t2);
    assertThat(remaining)
        .as("DOUBLE_BOOKING pair cleared after one side transitions to DONE")
        .isZero();
  }

  // -- helpers ---------------------------------------------------------------

  private UUID createTask(String title, LocalDate dueDate, TaskStatus status) {
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
                status,
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
        Set.of(BUTTERFLIES, CATERPILLARS),
        Set.of(),
        "Owner");
  }
}
