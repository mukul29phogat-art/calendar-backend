package com.childcarewow.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional // each test rolls back; calendar-db doesn't accumulate test rows across runs
class TaskRepositoryIT {

  @Autowired TaskRepository tasks;
  @Autowired RecurrenceRuleRepository rules;
  @Autowired TaskInstanceOverrideRepository overrides;
  @PersistenceContext EntityManager em;

  @Test
  void roundTripsTask() {
    UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID schoolId = UUID.fromString("22222222-2222-2222-2222-222222222221");
    UUID classroomId = UUID.fromString("44444444-0000-0000-0000-000000000001");
    UUID assigneeId = UUID.fromString("33333333-0000-0000-0000-000000000004");
    UUID createdById = UUID.fromString("33333333-0000-0000-0000-000000000002");
    UUID updatedById = UUID.fromString("33333333-0000-0000-0000-000000000003");
    UUID groupId = UUID.fromString("66666666-0000-0000-0000-000000000001");

    RecurrenceRule rule = new RecurrenceRule();
    rule.setCycle(RecurCycle.WEEKLY);
    rule.setDueDayOfWeek((short) 1);
    rule.setDueTime(LocalTime.of(9, 0));
    rule.setUntilDate(LocalDate.of(2026, 12, 31));
    UUID ruleId = rules.saveAndFlush(rule).getId();

    Task t = new Task();
    t.setOrgId(orgId);
    t.setSchoolId(schoolId);
    t.setParentTaskGroupId(groupId);
    t.setTitle("Sanitize toys");
    t.setDescription("Weekly classroom toy sanitization");
    t.setClassroomId(classroomId);
    t.setAssigneeUserId(assigneeId);
    t.setDueDate(LocalDate.of(2026, 6, 1));
    t.setDueTime(LocalTime.of(15, 30));
    t.setStatus(TaskStatus.IN_PROGRESS);
    t.setPriority(TaskPriority.HIGH);
    t.setRecurrenceId(ruleId);
    t.setCreatedByUserId(createdById);
    t.setUpdatedByUserId(updatedById);

    UUID id = tasks.saveAndFlush(t).getId();
    em.clear();
    Task read = tasks.findById(id).orElseThrow();

    assertThat(read.getId()).isEqualTo(id);
    assertThat(read.getOrgId()).isEqualTo(orgId);
    assertThat(read.getSchoolId()).isEqualTo(schoolId);
    assertThat(read.getParentTaskGroupId()).isEqualTo(groupId);
    assertThat(read.getTitle()).isEqualTo("Sanitize toys");
    assertThat(read.getDescription()).isEqualTo("Weekly classroom toy sanitization");
    assertThat(read.getClassroomId()).isEqualTo(classroomId);
    assertThat(read.getAssigneeUserId()).isEqualTo(assigneeId);
    assertThat(read.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(read.getDueTime()).isEqualTo(LocalTime.of(15, 30));
    assertThat(read.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(read.getPriority()).isEqualTo(TaskPriority.HIGH);
    assertThat(read.getRecurrenceId()).isEqualTo(ruleId);
    assertThat(read.getCreatedByUserId()).isEqualTo(createdById);
    assertThat(read.getUpdatedByUserId()).isEqualTo(updatedById);
    assertThat(read.getDeletedAt()).isNull();
    assertThat(read.getCreatedAt()).isNotNull();
    assertThat(read.getUpdatedAt()).isNotNull();
  }

  @Test
  void enforcesWeeklyRequiresDayOfWeek() {
    RecurrenceRule rule = new RecurrenceRule();
    rule.setCycle(RecurCycle.WEEKLY);
    rule.setDueDayOfWeek(null); // violates chk_weekly_dow
    rule.setUntilDate(LocalDate.of(2026, 12, 31));

    assertThatThrownBy(() -> rules.saveAndFlush(rule))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void enforcesMonthlyRequiresDayOfMonth() {
    RecurrenceRule rule = new RecurrenceRule();
    rule.setCycle(RecurCycle.MONTHLY);
    rule.setDueDayOfMonth(null); // violates chk_monthly_dom
    rule.setUntilDate(LocalDate.of(2026, 12, 31));

    assertThatThrownBy(() -> rules.saveAndFlush(rule))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void cascadeDeleteOverridesWhenTaskDeleted() {
    Task t = new Task();
    t.setOrgId(UUID.randomUUID());
    t.setSchoolId(UUID.randomUUID());
    t.setTitle("Daily standup");
    t.setAssigneeUserId(UUID.randomUUID());
    t.setDueDate(LocalDate.of(2026, 6, 1));
    t.setCreatedByUserId(UUID.randomUUID());
    UUID taskId = tasks.saveAndFlush(t).getId();

    TaskInstanceOverride o1 = new TaskInstanceOverride();
    o1.setTaskId(taskId);
    o1.setOccurrenceDate(LocalDate.of(2026, 6, 5));
    o1.setSkipped(true);
    o1.setNotes("School holiday");
    UUID o1Id = overrides.saveAndFlush(o1).getId();

    TaskInstanceOverride o2 = new TaskInstanceOverride();
    o2.setTaskId(taskId);
    o2.setOccurrenceDate(LocalDate.of(2026, 6, 12));
    o2.setStatus(TaskStatus.DONE);
    o2.setTitle("Daily standup (Friday early)");
    o2.setDueTime(LocalTime.of(8, 30));
    UUID o2Id = overrides.saveAndFlush(o2).getId();

    em.clear();
    assertThat(overrides.findById(o1Id)).isPresent();
    assertThat(overrides.findById(o2Id)).isPresent();

    tasks.deleteById(taskId);
    em.flush(); // send the DELETE so the DB cascade fires
    em.clear();

    assertThat(tasks.findById(taskId)).isEmpty();
    assertThat(overrides.findById(o1Id)).isEmpty();
    assertThat(overrides.findById(o2Id)).isEmpty();
  }
}
