package com.childcarewow.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.childcarewow.calendar.exception.InvalidRecurrenceException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.task.RecurCycle;
import com.childcarewow.calendar.task.RecurrenceRule;
import com.childcarewow.calendar.task.RecurrenceRuleRepository;
import com.childcarewow.calendar.task.Task;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RecurrenceService}: validation, DAILY expansion, the 1000-cap
 * truncation flag, window-out-of-range, and CRUD pass-through. The repository is mocked — there's a
 * separate failsafe IT that exercises the JPA round-trip in Series 5 once the controller wires up.
 */
class RecurrenceServiceTest {

  private final RecurrenceRuleRepository ruleRepo = mock(RecurrenceRuleRepository.class);
  private final RecurrenceService service = new RecurrenceService(ruleRepo);

  // -- DAILY expansion --------------------------------------------------------

  @Test
  void daily14DayRuleEmits14Occurrences() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    LocalDate untilDate = LocalDate.of(2026, 6, 14);
    Task task = task(dueDate, UUID.randomUUID());
    when(ruleRepo.findById(task.getRecurrenceId()))
        .thenReturn(Optional.of(rule(RecurCycle.DAILY, untilDate)));

    ExpansionResult result =
        service.expand(task, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 14));
    assertThat(result.occurrences()).hasSize(14);
    assertThat(result.occurrences().get(0)).isEqualTo(dueDate);
    assertThat(result.occurrences().get(13)).isEqualTo(untilDate);
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void dailyExpansionRespectsRequestWindowStart() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    LocalDate untilDate = LocalDate.of(2026, 6, 30);
    Task task = task(dueDate, UUID.randomUUID());
    when(ruleRepo.findById(any(UUID.class)))
        .thenReturn(Optional.of(rule(RecurCycle.DAILY, untilDate)));

    ExpansionResult result =
        service.expand(task, LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 12));
    assertThat(result.occurrences())
        .containsExactly(
            LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 12));
  }

  @Test
  void windowOutsideRuleRangeReturnsEmpty() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    LocalDate untilDate = LocalDate.of(2026, 6, 14);
    Task task = task(dueDate, UUID.randomUUID());
    when(ruleRepo.findById(any(UUID.class)))
        .thenReturn(Optional.of(rule(RecurCycle.DAILY, untilDate)));

    // Window entirely after the rule's untilDate
    ExpansionResult after =
        service.expand(task, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 30));
    assertThat(after.occurrences()).isEmpty();
    assertThat(after.truncated()).isFalse();

    // Window entirely before the task's dueDate
    ExpansionResult before =
        service.expand(task, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 30));
    assertThat(before.occurrences()).isEmpty();
  }

  @Test
  void nonRecurringTaskReturnsEmptyWithoutTouchingRepo() {
    Task task = task(LocalDate.of(2026, 6, 1), null);
    ExpansionResult result =
        service.expand(task, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    assertThat(result.occurrences()).isEmpty();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void deletedRuleReturnsEmpty() {
    Task task = task(LocalDate.of(2026, 6, 1), UUID.randomUUID());
    when(ruleRepo.findById(any(UUID.class))).thenReturn(Optional.empty());
    ExpansionResult result =
        service.expand(task, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    assertThat(result.occurrences()).isEmpty();
  }

  /**
   * A 5-year DAILY rule covers ~1827 days; expanded over the full range, the result is capped at
   * {@link RecurrenceService#MAX_OCCURRENCES} (1000) and {@code truncated=true}.
   */
  @Test
  void overFiveYearWindowTruncatesAtThousand() {
    LocalDate dueDate = LocalDate.of(2026, 1, 1);
    LocalDate untilDate = dueDate.plusYears(5); // exactly the validation cap
    Task task = task(dueDate, UUID.randomUUID());
    when(ruleRepo.findById(any(UUID.class)))
        .thenReturn(Optional.of(rule(RecurCycle.DAILY, untilDate)));

    ExpansionResult result = service.expand(task, dueDate, untilDate);
    assertThat(result.occurrences()).hasSize(RecurrenceService.MAX_OCCURRENCES);
    assertThat(result.truncated()).isTrue();
    assertThat(result.occurrences().get(0)).isEqualTo(dueDate);
  }

  @Test
  void monthlyCycleNotYetSupported() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    Task task = task(dueDate, UUID.randomUUID());
    RecurrenceRule monthly = rule(RecurCycle.MONTHLY, dueDate.plusMonths(3));
    monthly.setDueDayOfMonth((short) 15);
    when(ruleRepo.findById(any(UUID.class))).thenReturn(Optional.of(monthly));
    assertThatThrownBy(() -> service.expand(task, dueDate, dueDate.plusMonths(1)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("MONTHLY");
  }

  // -- WEEKLY expansion -------------------------------------------------------

  /** 2026-06-01 is a Monday; the next Tuesday is June 2. Four weeks → June 2, 9, 16, 23. */
  @Test
  void weeklyTuesdayRuleEmitsFourTuesdays() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1); // Monday
    LocalDate untilDate = LocalDate.of(2026, 6, 30);
    Task task = task(dueDate, UUID.randomUUID());
    RecurrenceRule weekly = rule(RecurCycle.WEEKLY, untilDate);
    weekly.setDueDayOfWeek((short) 2); // Tuesday in JS convention
    when(ruleRepo.findById(any(UUID.class))).thenReturn(Optional.of(weekly));

    ExpansionResult result = service.expand(task, dueDate, untilDate);
    assertThat(result.occurrences())
        .containsExactly(
            LocalDate.of(2026, 6, 2),
            LocalDate.of(2026, 6, 9),
            LocalDate.of(2026, 6, 16),
            LocalDate.of(2026, 6, 23),
            LocalDate.of(2026, 6, 30));
    assertThat(result.truncated()).isFalse();
  }

  /** dueDayOfWeek=0 means Sunday in the JS convention; verifies the 0→Java 7 mapping. */
  @Test
  void weeklySundayRuleMapsCorrectly() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1); // Monday
    LocalDate untilDate = LocalDate.of(2026, 6, 30);
    Task task = task(dueDate, UUID.randomUUID());
    RecurrenceRule weekly = rule(RecurCycle.WEEKLY, untilDate);
    weekly.setDueDayOfWeek((short) 0); // Sunday
    when(ruleRepo.findById(any(UUID.class))).thenReturn(Optional.of(weekly));

    ExpansionResult result = service.expand(task, dueDate, untilDate);
    assertThat(result.occurrences())
        .containsExactly(
            LocalDate.of(2026, 6, 7),
            LocalDate.of(2026, 6, 14),
            LocalDate.of(2026, 6, 21),
            LocalDate.of(2026, 6, 28));
  }

  /**
   * The expand path works in {@link LocalDate}, so DST transitions don't shift dates. Spans the
   * America/* fall-back boundary (2026-11-01).
   */
  @Test
  void weeklyExpansionAcrossDstBoundaryHoldsDate() {
    LocalDate dueDate = LocalDate.of(2026, 10, 25); // Sunday
    LocalDate untilDate = LocalDate.of(2026, 11, 22);
    Task task = task(dueDate, UUID.randomUUID());
    RecurrenceRule weekly = rule(RecurCycle.WEEKLY, untilDate);
    weekly.setDueDayOfWeek((short) 0); // Sunday
    when(ruleRepo.findById(any(UUID.class))).thenReturn(Optional.of(weekly));

    ExpansionResult result = service.expand(task, dueDate, untilDate);
    // Five Sundays bracketing the fall-back: 10/25, 11/1 (DST ends), 11/8, 11/15, 11/22.
    assertThat(result.occurrences())
        .containsExactly(
            LocalDate.of(2026, 10, 25),
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 8),
            LocalDate.of(2026, 11, 15),
            LocalDate.of(2026, 11, 22));
  }

  @Test
  void weeklyExpansionWithoutDueDayOfWeekThrowsAtExpand() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    Task task = task(dueDate, UUID.randomUUID());
    RecurrenceRule weeklyNoDow = rule(RecurCycle.WEEKLY, dueDate.plusMonths(1));
    // dueDayOfWeek deliberately unset
    when(ruleRepo.findById(any(UUID.class))).thenReturn(Optional.of(weeklyNoDow));
    assertThatThrownBy(() -> service.expand(task, dueDate, dueDate.plusMonths(1)))
        .isInstanceOf(InvalidRecurrenceException.class)
        .hasMessageContaining("dueDayOfWeek");
  }

  @Test
  void weeklyCreateRequiresDueDayOfWeek() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    RecurrenceRule rule = rule(RecurCycle.WEEKLY, dueDate.plusMonths(1));
    // dueDayOfWeek deliberately unset
    assertThatThrownBy(() -> service.create(rule, dueDate))
        .isInstanceOf(InvalidRecurrenceException.class)
        .hasMessageContaining("dueDayOfWeek");
  }

  @Test
  void weeklyCreateRejectsOutOfRangeDueDayOfWeek() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    RecurrenceRule rule = rule(RecurCycle.WEEKLY, dueDate.plusMonths(1));
    rule.setDueDayOfWeek((short) 7); // out of range (valid is 0..6)
    assertThatThrownBy(() -> service.create(rule, dueDate))
        .isInstanceOf(InvalidRecurrenceException.class)
        .hasMessageContaining("0..6");
  }

  @Test
  void weeklyExpandRejectsOutOfRangeDueDayOfWeek() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    Task task = task(dueDate, UUID.randomUUID());
    RecurrenceRule weekly = rule(RecurCycle.WEEKLY, dueDate.plusMonths(1));
    weekly.setDueDayOfWeek((short) -1); // legacy/corrupt rule that bypassed validation
    when(ruleRepo.findById(any(UUID.class))).thenReturn(Optional.of(weekly));
    assertThatThrownBy(() -> service.expand(task, dueDate, dueDate.plusMonths(1)))
        .isInstanceOf(InvalidRecurrenceException.class);
  }

  // -- validation -------------------------------------------------------------

  @Test
  void createValidatesUntilDateAfterDueDate() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    RecurrenceRule rule = rule(RecurCycle.DAILY, LocalDate.of(2026, 5, 1)); // untilDate < dueDate
    assertThatThrownBy(() -> service.create(rule, dueDate))
        .isInstanceOf(InvalidRecurrenceException.class)
        .hasMessageContaining("on or after");
  }

  @Test
  void createValidatesUntilDateWithinFiveYears() {
    LocalDate dueDate = LocalDate.of(2026, 1, 1);
    RecurrenceRule rule = rule(RecurCycle.DAILY, dueDate.plusYears(6));
    assertThatThrownBy(() -> service.create(rule, dueDate))
        .isInstanceOf(InvalidRecurrenceException.class)
        .hasMessageContaining("within 5 years");
  }

  @Test
  void createRequiresUntilDate() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    RecurrenceRule rule = rule(RecurCycle.DAILY, null);
    assertThatThrownBy(() -> service.create(rule, dueDate))
        .isInstanceOf(InvalidRecurrenceException.class)
        .hasMessageContaining("untilDate");
  }

  @Test
  void createRequiresCycle() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    RecurrenceRule rule = rule(null, dueDate.plusDays(7));
    assertThatThrownBy(() -> service.create(rule, dueDate))
        .isInstanceOf(InvalidRecurrenceException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void createPersistsValidRule() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    RecurrenceRule rule = rule(RecurCycle.DAILY, dueDate.plusDays(7));
    when(ruleRepo.save(any(RecurrenceRule.class))).thenReturn(rule);
    RecurrenceRule saved = service.create(rule, dueDate);
    assertThat(saved).isSameAs(rule);
  }

  // -- shortenUntil + remove --------------------------------------------------

  @Test
  void shortenUntilRejectsExtension() {
    UUID id = UUID.randomUUID();
    RecurrenceRule existing = rule(RecurCycle.DAILY, LocalDate.of(2026, 6, 30));
    when(ruleRepo.findById(eq(id))).thenReturn(Optional.of(existing));
    assertThatThrownBy(() -> service.shortenUntil(id, LocalDate.of(2026, 7, 30)))
        .isInstanceOf(InvalidRecurrenceException.class)
        .hasMessageContaining("shortened");
  }

  @Test
  void shortenUntilUpdatesAndPersists() {
    UUID id = UUID.randomUUID();
    RecurrenceRule existing = rule(RecurCycle.DAILY, LocalDate.of(2026, 6, 30));
    when(ruleRepo.findById(eq(id))).thenReturn(Optional.of(existing));
    when(ruleRepo.save(any(RecurrenceRule.class))).thenReturn(existing);
    LocalDate newUntil = LocalDate.of(2026, 6, 15);
    service.shortenUntil(id, newUntil);
    assertThat(existing.getUntilDate()).isEqualTo(newUntil);
  }

  @Test
  void shortenUntilRequiresNewUntilDate() {
    UUID id = UUID.randomUUID();
    when(ruleRepo.findById(eq(id)))
        .thenReturn(Optional.of(rule(RecurCycle.DAILY, LocalDate.now())));
    assertThatThrownBy(() -> service.shortenUntil(id, null))
        .isInstanceOf(InvalidRecurrenceException.class);
  }

  @Test
  void shortenUntilThrowsForUnknownRule() {
    UUID id = UUID.randomUUID();
    when(ruleRepo.findById(eq(id))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.shortenUntil(id, LocalDate.now()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void removeDeletesExisting() {
    UUID id = UUID.randomUUID();
    when(ruleRepo.existsById(eq(id))).thenReturn(true);
    service.remove(id);
    org.mockito.Mockito.verify(ruleRepo).deleteById(id);
  }

  @Test
  void removeThrowsForUnknownRule() {
    UUID id = UUID.randomUUID();
    when(ruleRepo.existsById(eq(id))).thenReturn(false);
    assertThatThrownBy(() -> service.remove(id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void updateAppliesPatchAndPersists() {
    UUID id = UUID.randomUUID();
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    RecurrenceRule existing = rule(RecurCycle.DAILY, dueDate.plusDays(7));
    RecurrenceRule patch = rule(RecurCycle.DAILY, dueDate.plusDays(30));
    patch.setDueTime(java.time.LocalTime.of(9, 0));
    when(ruleRepo.findById(eq(id))).thenReturn(Optional.of(existing));
    when(ruleRepo.save(any(RecurrenceRule.class))).thenReturn(existing);

    service.update(id, patch, dueDate);

    assertThat(existing.getUntilDate()).isEqualTo(dueDate.plusDays(30));
    assertThat(existing.getDueTime()).isEqualTo(java.time.LocalTime.of(9, 0));
  }

  @Test
  void updateThrowsForUnknownRule() {
    UUID id = UUID.randomUUID();
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    when(ruleRepo.findById(eq(id))).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.update(id, rule(RecurCycle.DAILY, dueDate.plusDays(7)), dueDate))
        .isInstanceOf(NotFoundException.class);
  }

  // -- helpers ----------------------------------------------------------------

  private static Task task(LocalDate dueDate, UUID recurrenceId) {
    Task t = new Task();
    t.setOrgId(UUID.randomUUID());
    t.setSchoolId(UUID.randomUUID());
    t.setTitle("test");
    t.setAssigneeUserId(UUID.randomUUID());
    t.setDueDate(dueDate);
    t.setCreatedByUserId(UUID.randomUUID());
    t.setRecurrenceId(recurrenceId);
    return t;
  }

  private static RecurrenceRule rule(RecurCycle cycle, LocalDate untilDate) {
    RecurrenceRule r = new RecurrenceRule();
    r.setCycle(cycle);
    r.setUntilDate(untilDate);
    return r;
  }
}
