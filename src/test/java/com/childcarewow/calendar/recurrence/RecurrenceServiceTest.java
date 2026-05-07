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
  void weeklyAndMonthlyCyclesNotYetSupported() {
    LocalDate dueDate = LocalDate.of(2026, 6, 1);
    Task task = task(dueDate, UUID.randomUUID());
    when(ruleRepo.findById(any(UUID.class)))
        .thenReturn(Optional.of(rule(RecurCycle.WEEKLY, dueDate.plusMonths(3))));
    assertThatThrownBy(() -> service.expand(task, dueDate, dueDate.plusMonths(1)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("WEEKLY");
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
