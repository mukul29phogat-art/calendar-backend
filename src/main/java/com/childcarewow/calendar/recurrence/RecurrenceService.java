package com.childcarewow.calendar.recurrence;

import com.childcarewow.calendar.exception.InvalidRecurrenceException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.task.RecurCycle;
import com.childcarewow.calendar.task.RecurrenceRule;
import com.childcarewow.calendar.task.RecurrenceRuleRepository;
import com.childcarewow.calendar.task.Task;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns recurrence rules and their on-the-fly expansion (architecture spec §7.5). The DB does not
 * store materialized occurrences — every calendar read calls {@link #expand} for the requested
 * window. This part covers the {@link RecurCycle#DAILY} cycle only; WEEKLY and MONTHLY land in
 * Parts 3.7 and 3.8.
 *
 * <p><b>Defensive caps.</b> A malformed rule (e.g. {@code until_date = 9999-12-31}) could produce
 * millions of occurrences. Two layers protect us:
 *
 * <ul>
 *   <li><b>Creation-time validation</b>: {@code untilDate} must be present, &ge; the task's {@code
 *       dueDate}, and &le; {@code dueDate + 5 years}.
 *   <li><b>Expansion-time cap</b>: at most {@link #MAX_OCCURRENCES} dates emitted per call. Excess
 *       is truncated and {@link ExpansionResult#truncated()} is {@code true}.
 * </ul>
 */
@Service
public class RecurrenceService {

  /** Hard cap on occurrences per {@link #expand} call. */
  public static final int MAX_OCCURRENCES = 1000;

  /** Maximum span between a task's {@code dueDate} and its rule's {@code untilDate}. */
  public static final long MAX_UNTIL_YEARS = 5L;

  private final RecurrenceRuleRepository ruleRepo;

  public RecurrenceService(RecurrenceRuleRepository ruleRepo) {
    this.ruleRepo = ruleRepo;
  }

  // -- CRUD -------------------------------------------------------------------

  @Transactional
  public RecurrenceRule create(RecurrenceRule rule, LocalDate taskDueDate) {
    validate(rule, taskDueDate);
    return ruleRepo.save(rule);
  }

  @Transactional
  public RecurrenceRule update(UUID ruleId, RecurrenceRule patch, LocalDate taskDueDate) {
    validate(patch, taskDueDate);
    RecurrenceRule existing =
        ruleRepo
            .findById(ruleId)
            .orElseThrow(() -> new NotFoundException("RecurrenceRule", ruleId));
    existing.setCycle(patch.getCycle());
    existing.setDueDayOfWeek(patch.getDueDayOfWeek());
    existing.setDueDayOfMonth(patch.getDueDayOfMonth());
    existing.setDueTime(patch.getDueTime());
    existing.setUntilDate(patch.getUntilDate());
    return ruleRepo.save(existing);
  }

  /**
   * Pulls the rule's {@code untilDate} in. Used by the "this and following" task-edit flow —
   * extending {@code untilDate} is not supported (would surprise users with new occurrences they
   * never asked for).
   */
  @Transactional
  public RecurrenceRule shortenUntil(UUID ruleId, LocalDate newUntil) {
    RecurrenceRule rule =
        ruleRepo
            .findById(ruleId)
            .orElseThrow(() -> new NotFoundException("RecurrenceRule", ruleId));
    if (newUntil == null) {
      throw new InvalidRecurrenceException("newUntil is required");
    }
    if (newUntil.isAfter(rule.getUntilDate())) {
      throw new InvalidRecurrenceException("untilDate can only be shortened, not extended");
    }
    rule.setUntilDate(newUntil);
    return ruleRepo.save(rule);
  }

  @Transactional
  public void remove(UUID ruleId) {
    if (!ruleRepo.existsById(ruleId)) {
      throw new NotFoundException("RecurrenceRule", ruleId);
    }
    ruleRepo.deleteById(ruleId);
  }

  // -- expansion --------------------------------------------------------------

  /**
   * Expands the task's recurrence rule into concrete dates within the inclusive window {@code
   * [from, to]}. Returns an empty list (no error) if the task is non-recurring, the rule has been
   * deleted, or the window doesn't intersect the rule's range.
   */
  @Transactional(readOnly = true)
  public ExpansionResult expand(Task task, LocalDate from, LocalDate to) {
    if (task.getRecurrenceId() == null) {
      return new ExpansionResult(List.of(), false);
    }
    RecurrenceRule rule = ruleRepo.findById(task.getRecurrenceId()).orElse(null);
    if (rule == null) {
      return new ExpansionResult(List.of(), false);
    }

    // Intersect requested window with the rule's effective range [taskDueDate, untilDate].
    LocalDate winStart = task.getDueDate().isAfter(from) ? task.getDueDate() : from;
    LocalDate winEnd = rule.getUntilDate().isBefore(to) ? rule.getUntilDate() : to;
    if (winStart.isAfter(winEnd)) {
      return new ExpansionResult(List.of(), false);
    }

    return switch (rule.getCycle()) {
      case DAILY -> expandDaily(winStart, winEnd);
      case WEEKLY -> expandWeekly(rule, winStart, winEnd);
      case MONTHLY ->
          throw new UnsupportedOperationException("Cycle MONTHLY not yet implemented (Part 3.8)");
    };
  }

  /**
   * Walks weeks within {@code [winStart, winEnd]} emitting the date in each week whose day-of-week
   * matches {@code rule.dueDayOfWeek}. The stored value is JS-flavoured ({@code 0=Sun..6=Sat},
   * matching the FE prototype's {@code Date.getDay()}); this method maps it to {@link
   * java.time.DayOfWeek} (1=Mon..7=Sun) before searching.
   */
  private static ExpansionResult expandWeekly(
      RecurrenceRule rule, LocalDate winStart, LocalDate winEnd) {
    if (rule.getDueDayOfWeek() == null) {
      throw new InvalidRecurrenceException("WEEKLY cycle requires dueDayOfWeek");
    }
    int js = rule.getDueDayOfWeek(); // 0=Sun..6=Sat
    if (js < 0 || js > 6) {
      throw new InvalidRecurrenceException("dueDayOfWeek must be in 0..6 (Sun..Sat)");
    }
    // 0=Sun -> Java 7=Sun; 1=Mon -> Java 1=Mon; 6=Sat -> Java 6=Sat.
    java.time.DayOfWeek targetDow = java.time.DayOfWeek.of(js == 0 ? 7 : js);

    // Advance from winStart to the next date whose day-of-week matches targetDow.
    int delta = (targetDow.getValue() - winStart.getDayOfWeek().getValue() + 7) % 7;
    LocalDate cursor = winStart.plusDays(delta);

    List<LocalDate> occurrences = new ArrayList<>();
    boolean truncated = false;
    while (!cursor.isAfter(winEnd)) {
      if (occurrences.size() >= MAX_OCCURRENCES) {
        truncated = true;
        break;
      }
      occurrences.add(cursor);
      cursor = cursor.plusWeeks(1);
    }
    return new ExpansionResult(occurrences, truncated);
  }

  private static ExpansionResult expandDaily(LocalDate winStart, LocalDate winEnd) {
    long span = ChronoUnit.DAYS.between(winStart, winEnd) + 1; // inclusive both ends
    boolean truncated = false;
    int count = (int) Math.min(span, MAX_OCCURRENCES);
    if (span > MAX_OCCURRENCES) {
      truncated = true;
    }
    List<LocalDate> occurrences = new ArrayList<>(count);
    LocalDate cursor = winStart;
    for (int i = 0; i < count; i++) {
      occurrences.add(cursor);
      cursor = cursor.plusDays(1);
    }
    return new ExpansionResult(occurrences, truncated);
  }

  // -- validation -------------------------------------------------------------

  private static void validate(RecurrenceRule rule, LocalDate taskDueDate) {
    if (rule == null || rule.getCycle() == null) {
      throw new InvalidRecurrenceException("rule and cycle are required");
    }
    if (rule.getUntilDate() == null) {
      throw new InvalidRecurrenceException("untilDate is required");
    }
    if (taskDueDate == null) {
      throw new InvalidRecurrenceException("taskDueDate is required for validation");
    }
    if (rule.getUntilDate().isBefore(taskDueDate)) {
      throw new InvalidRecurrenceException("untilDate must be on or after the task's dueDate");
    }
    if (rule.getUntilDate().isAfter(taskDueDate.plusYears(MAX_UNTIL_YEARS))) {
      throw new InvalidRecurrenceException(
          "untilDate must be within " + MAX_UNTIL_YEARS + " years of dueDate");
    }
    // Cycle-specific shape: WEEKLY needs dueDayOfWeek; MONTHLY (Part 3.8) will need
    // dueDayOfMonth.
    if (rule.getCycle() == RecurCycle.WEEKLY) {
      Short dow = rule.getDueDayOfWeek();
      if (dow == null) {
        throw new InvalidRecurrenceException("WEEKLY cycle requires dueDayOfWeek");
      }
      if (dow < 0 || dow > 6) {
        throw new InvalidRecurrenceException("dueDayOfWeek must be in 0..6 (Sun..Sat)");
      }
    }
  }
}
