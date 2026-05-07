package com.childcarewow.calendar.recurrence;

import com.childcarewow.calendar.exception.InvalidRecurrenceException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.task.RecurCycle;
import com.childcarewow.calendar.task.RecurrenceRule;
import com.childcarewow.calendar.task.RecurrenceRuleRepository;
import com.childcarewow.calendar.task.Task;
import com.childcarewow.calendar.task.TaskInstanceOverride;
import com.childcarewow.calendar.task.TaskInstanceOverrideRepository;
import com.childcarewow.calendar.task.TaskStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
  private final TaskInstanceOverrideRepository overrideRepo;

  public RecurrenceService(
      RecurrenceRuleRepository ruleRepo, TaskInstanceOverrideRepository overrideRepo) {
    this.ruleRepo = ruleRepo;
    this.overrideRepo = overrideRepo;
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
   *
   * <p>Per-occurrence overrides marked {@code skipped=true} are filtered <i>after</i> the cycle
   * generates dates (per playbook common-failure-points: applying skips before the rule produces
   * the date is wrong because the date wouldn't be in the result anyway). Title / due-time / status
   * overrides do <b>not</b> apply here — see {@link #projectFor} for the per-date snapshot read.
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

    ExpansionResult raw =
        switch (rule.getCycle()) {
          case DAILY -> expandDaily(winStart, winEnd);
          case WEEKLY -> expandWeekly(rule, winStart, winEnd);
          case MONTHLY -> expandMonthly(rule, winStart, winEnd);
        };

    // Filter skipped occurrences. Cheap when the task has no overrides (the common case); the
    // single SELECT is unconditional but its result set is usually empty.
    if (task.getId() != null) {
      Set<LocalDate> skipped =
          overrideRepo.findByTaskId(task.getId()).stream()
              .filter(TaskInstanceOverride::isSkipped)
              .map(TaskInstanceOverride::getOccurrenceDate)
              .collect(Collectors.toSet());
      if (!skipped.isEmpty()) {
        List<LocalDate> filtered =
            raw.occurrences().stream().filter(d -> !skipped.contains(d)).toList();
        return new ExpansionResult(filtered, raw.truncated());
      }
    }
    return raw;
  }

  // -- per-occurrence overrides ----------------------------------------------

  /**
   * Insert-or-update by {@code (taskId, occurrenceDate)} (UNIQUE constraint on the table).
   * Idempotent by design: calling with the same payload twice leaves a single row whose fields
   * equal the second payload.
   */
  @Transactional
  public TaskInstanceOverride upsertOverride(TaskInstanceOverride incoming) {
    if (incoming.getTaskId() == null || incoming.getOccurrenceDate() == null) {
      throw new InvalidRecurrenceException("override taskId and occurrenceDate are required");
    }
    return overrideRepo
        .findByTaskIdAndOccurrenceDate(incoming.getTaskId(), incoming.getOccurrenceDate())
        .map(
            existing -> {
              existing.setStatus(incoming.getStatus());
              existing.setTitle(incoming.getTitle());
              existing.setDueTime(incoming.getDueTime());
              existing.setSkipped(incoming.isSkipped());
              existing.setNotes(incoming.getNotes());
              return overrideRepo.save(existing);
            })
        .orElseGet(() -> overrideRepo.save(incoming));
  }

  @Transactional(readOnly = true)
  public Optional<TaskInstanceOverride> getOverride(UUID taskId, LocalDate occurrenceDate) {
    return overrideRepo.findByTaskIdAndOccurrenceDate(taskId, occurrenceDate);
  }

  /** Drops every override for a task. Used when the parent task is deleted. */
  @Transactional
  public void removeOverridesForTask(UUID taskId) {
    overrideRepo.deleteByTaskId(taskId);
  }

  /**
   * Drops overrides on or after {@code fromDate}. Backs the "this and following" task-edit flow,
   * which shortens the parent rule and discards future per-occurrence customizations.
   */
  @Transactional
  public void removeOverridesFromDate(UUID taskId, LocalDate fromDate) {
    if (fromDate == null) {
      throw new InvalidRecurrenceException("fromDate is required");
    }
    overrideRepo.deleteByTaskIdAndOccurrenceDateGreaterThanEqual(taskId, fromDate);
  }

  /**
   * Returns the snapshot for a specific occurrence date. Falls through to the parent task's values
   * when the override doesn't set the corresponding field. Returns {@code null} if the date is
   * skipped (callers should normally not call this for skipped dates because {@link #expand}
   * filters them out, but the guard makes the contract obvious).
   */
  @Transactional(readOnly = true)
  public OccurrenceSnapshot projectFor(Task task, LocalDate occurrenceDate) {
    TaskInstanceOverride override =
        overrideRepo.findByTaskIdAndOccurrenceDate(task.getId(), occurrenceDate).orElse(null);
    if (override != null && override.isSkipped()) {
      return null;
    }
    String title =
        override != null && override.getTitle() != null ? override.getTitle() : task.getTitle();
    LocalTime dueTime =
        override != null && override.getDueTime() != null
            ? override.getDueTime()
            : task.getDueTime();
    TaskStatus status =
        override != null && override.getStatus() != null ? override.getStatus() : task.getStatus();
    return new OccurrenceSnapshot(occurrenceDate, title, dueTime, status);
  }

  /**
   * Walks months in {@code [winStart, winEnd]} inclusive. For each month, snaps {@code
   * dueDayOfMonth} to {@code min(d, lastDayOfMonth)} so that "the 31st" lands on Feb 28/29 or on
   * the 30th of 30-day months. Emits the resulting date if it falls within the window.
   */
  private static ExpansionResult expandMonthly(
      RecurrenceRule rule, LocalDate winStart, LocalDate winEnd) {
    if (rule.getDueDayOfMonth() == null) {
      throw new InvalidRecurrenceException("MONTHLY cycle requires dueDayOfMonth");
    }
    int dom = rule.getDueDayOfMonth();
    if (dom < 1 || dom > 31) {
      throw new InvalidRecurrenceException("dueDayOfMonth must be in 1..31");
    }

    List<LocalDate> occurrences = new ArrayList<>();
    boolean truncated = false;
    YearMonth ym = YearMonth.from(winStart);
    YearMonth lastYm = YearMonth.from(winEnd);
    while (!ym.isAfter(lastYm)) {
      if (occurrences.size() >= MAX_OCCURRENCES) {
        truncated = true;
        break;
      }
      LocalDate candidate = ym.atDay(Math.min(dom, ym.lengthOfMonth()));
      if (!candidate.isBefore(winStart) && !candidate.isAfter(winEnd)) {
        occurrences.add(candidate);
      }
      ym = ym.plusMonths(1);
    }
    return new ExpansionResult(occurrences, truncated);
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
    if (rule.getCycle() == RecurCycle.MONTHLY) {
      Short dom = rule.getDueDayOfMonth();
      if (dom == null) {
        throw new InvalidRecurrenceException("MONTHLY cycle requires dueDayOfMonth");
      }
      if (dom < 1 || dom > 31) {
        throw new InvalidRecurrenceException("dueDayOfMonth must be in 1..31");
      }
    }
  }
}
