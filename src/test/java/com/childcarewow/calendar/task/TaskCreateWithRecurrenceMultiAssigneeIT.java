package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
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
 * Real-DB IT for Part 9.2 — recurrence + multi-assignee fan-out. Per locked decision D9, each
 * fanned-out task row gets its OWN {@code recurrence_id}. All N rows share one {@code
 * parent_task_group_id} (the multi-assignee group identity, same as Part 8.2), but the rules are
 * independent so per-assignee overrides (Part 9.3+) don't coupling-leak across rows through the
 * {@code task_instance_overrides(task_id, occurrence_date)} UNIQUE constraint.
 */
@SpringBootTest
class TaskCreateWithRecurrenceMultiAssigneeIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");

  @Autowired TaskService taskService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-tcr-multi-%')");
    calendarJdbc.update(
        "DELETE FROM notifications WHERE related_entity_title LIKE 'IT-tcr-multi-%'");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-tcr-multi-%'");
    calendarJdbc.update(
        "DELETE FROM recurrence_rules WHERE id NOT IN "
            + "(SELECT recurrence_id FROM tasks WHERE recurrence_id IS NOT NULL)");
  }

  @Test
  void threeAssigneesEachGetIndependentRecurrenceIdAndShareGroupId() {
    LocalDate dueDate = LocalDate.of(2027, 7, 5);
    LocalDate untilDate = LocalDate.of(2027, 8, 31);
    RecurrenceSpec spec = new RecurrenceSpec(RecurCycle.DAILY, null, null, null, untilDate);

    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tcr-multi-3",
            null,
            SUNRISE,
            null,
            List.of(MAYA, TOM, PRIYA),
            dueDate,
            null,
            TaskStatus.TODO,
            TaskPriority.MEDIUM,
            spec);

    List<TaskView> created = taskService.create(req, admin());
    assertThat(created).hasSize(3);

    // All three tasks carry a recurrence_id.
    assertThat(created).allSatisfy(v -> assertThat(v.recurrenceId()).isNotNull());

    // All three recurrence_ids are distinct (per D9).
    List<UUID> recurrenceIds = created.stream().map(TaskView::recurrenceId).toList();
    assertThat(recurrenceIds).doesNotHaveDuplicates();

    // All three share one parent_task_group_id (the multi-assignee fan-out group identity from
    // Part 8.2 — unchanged by Part 9.2; group_id is about "this batch of rows came from one
    // request", recurrence_id is about "this row's recurrence schedule").
    List<UUID> groupIds =
        created.stream()
            .map(
                v ->
                    calendarJdbc.queryForObject(
                        "SELECT parent_task_group_id FROM tasks WHERE id = ?", UUID.class, v.id()))
            .toList();
    assertThat(groupIds).hasSize(3);
    assertThat(Set.copyOf(groupIds)).hasSize(1);
    assertThat(groupIds.get(0)).isNotNull();

    // Each row points at its own rule, and each rule row exists (sanity — the FKs would already
    // enforce this, but the explicit row-presence check protects against a future "lazy create"
    // refactor that might skip the persist).
    for (UUID ruleId : recurrenceIds) {
      Integer ruleRows =
          calendarJdbc.queryForObject(
              "SELECT COUNT(*) FROM recurrence_rules WHERE id = ?", Integer.class, ruleId);
      assertThat(ruleRows).isEqualTo(1);
    }
  }

  @Test
  void singleAssigneeWithRecurrenceStillLeavesGroupIdNull() {
    // Regression: lifting the size==1 guard from Part 9.1 must not accidentally start setting
    // group_id for solo assignments. The group_id contract (Part 8.2): non-null only when
    // assignees.size() > 1.
    RecurrenceSpec spec =
        new RecurrenceSpec(RecurCycle.DAILY, null, null, null, LocalDate.of(2027, 7, 14));
    CreateTaskRequest req =
        new CreateTaskRequest(
            "IT-tcr-multi-solo",
            null,
            SUNRISE,
            null,
            List.of(MAYA),
            LocalDate.of(2027, 7, 1),
            null,
            TaskStatus.TODO,
            TaskPriority.MEDIUM,
            spec);

    List<TaskView> created = taskService.create(req, admin());
    assertThat(created).hasSize(1);
    assertThat(created.get(0).recurrenceId()).isNotNull();

    UUID groupId =
        calendarJdbc.queryForObject(
            "SELECT parent_task_group_id FROM tasks WHERE id = ?", UUID.class, created.get(0).id());
    assertThat(groupId).isNull();
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
