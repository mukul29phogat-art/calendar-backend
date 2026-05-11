package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
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
 * Real-DB IT for {@link TaskService#delete}. Part 8.6 exercises: soft-delete via {@code deletedAt}
 * timestamp, idempotent double-delete (second call surfaces 404), bidirectional DOUBLE_BOOKING flag
 * cleanup via {@code softFlagService.removeFlagsForTask}, and the TASK_DELETED notification
 * dispatch.
 */
@SpringBootTest
class TaskDeleteIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");

  @Autowired TaskService taskService;
  @Autowired TaskReadService readService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-td-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-td-%'");
    calendarJdbc.update(
        "DELETE FROM conflict_flags WHERE entity_type = 'TASK' AND entity_id IN "
            + "(SELECT id FROM tasks WHERE title LIKE 'IT-td-%')");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-td-%'");
  }

  @Test
  void softDeletesAndExcludesFromReads() {
    UUID id = createTask("IT-td-soft-delete", LocalDate.of(2026, 10, 5));

    taskService.delete(id, admin());

    // findById now 404s.
    assertThatThrownBy(() -> readService.findById(id, admin()))
        .isInstanceOf(NotFoundException.class);

    // Row still in DB but with deleted_at populated.
    java.time.OffsetDateTime deletedAt =
        calendarJdbc.queryForObject(
            "SELECT deleted_at FROM tasks WHERE id = ?", java.time.OffsetDateTime.class, id);
    assertThat(deletedAt).isNotNull();
  }

  @Test
  void doubleDeleteReturns404() {
    UUID id = createTask("IT-td-double-delete", LocalDate.of(2026, 10, 6));

    taskService.delete(id, admin());

    // The second call sees the row but it's deletedAt-filtered → NotFoundException.
    assertThatThrownBy(() -> taskService.delete(id, admin())).isInstanceOf(NotFoundException.class);
  }

  @Test
  void unknownIdReturns404() {
    UUID bogus = UUID.fromString("99999999-0000-0000-0000-000000000099");
    assertThatThrownBy(() -> taskService.delete(bogus, admin()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void deleteClearsBidirectionalDoubleBookingFlagPair() {
    // Two same-school + same-assignee + same-dueDate TODO tasks → DOUBLE_BOOKING pair (Part 3.12).
    LocalDate date = LocalDate.of(2026, 10, 7);
    UUID t1 = createTask("IT-td-overlap-a", date);
    UUID t2 = createTask("IT-td-overlap-b", date);

    Integer beforeCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'TASK' AND conflict_type = 'DOUBLE_BOOKING' "
                + "AND (entity_id IN (?, ?) OR conflicting_entity_id IN (?, ?))",
            Integer.class,
            t1,
            t2,
            t1,
            t2);
    assertThat(beforeCount).as("bidirectional pair present before delete").isEqualTo(2);

    taskService.delete(t1, admin());

    Integer afterCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'TASK' AND conflict_type = 'DOUBLE_BOOKING' "
                + "AND (entity_id IN (?, ?) OR conflicting_entity_id IN (?, ?))",
            Integer.class,
            t1,
            t2,
            t1,
            t2);
    assertThat(afterCount)
        .as("bidirectional pair cleared after delete (both sides of the pair gone)")
        .isZero();
  }

  @Test
  void writesTaskDeletedNotificationToAssignee() {
    UUID id = createTask("IT-td-notify", LocalDate.of(2026, 10, 8));

    taskService.delete(id, admin());

    Integer notifCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications "
                + "WHERE related_entity_id = ? AND kind = 'TASK_DELETED'",
            Integer.class,
            id);
    assertThat(notifCount).isEqualTo(1);

    UUID recipient =
        calendarJdbc.queryForObject(
            "SELECT user_id FROM notification_recipients nr "
                + "JOIN notifications n ON n.id = nr.notification_id "
                + "WHERE n.related_entity_id = ? AND n.kind = 'TASK_DELETED'",
            UUID.class,
            id);
    assertThat(recipient).isEqualTo(MAYA);
  }

  @Test
  void deletedTaskExcludedFromWindowRead() {
    LocalDate date = LocalDate.of(2026, 10, 9);
    UUID id = createTask("IT-td-window-excluded", date);

    // Before delete: row appears in the window.
    List<TaskView> before =
        readService.findInWindow(
            SUNRISE, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), admin());
    assertThat(before).extracting(TaskView::id).contains(id);

    taskService.delete(id, admin());

    List<TaskView> after =
        readService.findInWindow(
            SUNRISE, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), admin());
    assertThat(after).extracting(TaskView::id).doesNotContain(id);
  }

  // -- helpers ---------------------------------------------------------------

  private UUID createTask(String title, LocalDate dueDate) {
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
        Set.of(BUTTERFLIES, CATERPILLARS),
        Set.of(),
        "Owner");
  }
}
