package com.childcarewow.calendar.softflag;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.conflict.ConflictFlag;
import com.childcarewow.calendar.conflict.ConflictFlagRepository;
import com.childcarewow.calendar.conflict.FlaggedEntity;
import com.childcarewow.calendar.conflict.SoftFlagType;
import com.childcarewow.calendar.event.Event;
import com.childcarewow.calendar.event.EventRepository;
import com.childcarewow.calendar.exception.ForbiddenException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.holiday.Holiday;
import com.childcarewow.calendar.task.Task;
import com.childcarewow.calendar.task.TaskRepository;
import com.childcarewow.calendar.task.TaskStatus;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-cutting flag insert/list/dismiss for {@code conflict_flags} (architecture spec § 7.3). This
 * part lays the skeleton; the recompute triggers (bidirectional double-booking on event save,
 * holiday-paint on holiday approve) land in Parts 3.11 and 3.12.
 *
 * <p><b>Authorization caveat.</b> Controllers gate flag actions via {@code
 * policyService.assertCan(actor, "calendar.softFlag.see", ...)} before reaching this service. As
 * defense in depth, {@link #dismiss} re-checks: a {@link Role#PARENT} actor is forbidden from
 * dismissing flags here so a misconfigured route can't bypass the policy layer.
 */
@Service
public class SoftFlagService {

  /** Window for considering two tasks "overlapping" by due time. */
  static final long TASK_OVERLAP_MINUTES = 120L;

  private final ConflictFlagRepository repo;
  private final EventRepository eventRepo;
  private final TaskRepository taskRepo;

  public SoftFlagService(
      ConflictFlagRepository repo, EventRepository eventRepo, TaskRepository taskRepo) {
    this.repo = repo;
    this.eventRepo = eventRepo;
    this.taskRepo = taskRepo;
  }

  /**
   * Inserts an active (non-dismissed) flag. Used by the recompute paths in 3.11/3.12 and by the
   * holiday-approve hook in 3.12. The caller is responsible for any uniqueness / dedup logic; the
   * schema does not enforce uniqueness on (entity, conflictType) so callers that want to avoid
   * duplicates should clear+re-insert (the recompute pattern).
   */
  @Transactional
  public ConflictFlag insertFlag(
      UUID orgId,
      UUID schoolId,
      FlaggedEntity entityType,
      UUID entityId,
      SoftFlagType conflictType,
      UUID conflictingEntityId,
      String message) {
    if (orgId == null
        || schoolId == null
        || entityType == null
        || entityId == null
        || conflictType == null
        || message == null
        || message.isBlank()) {
      throw new ValidationException("flag", "missing required fields");
    }
    ConflictFlag f = new ConflictFlag();
    f.setOrgId(orgId);
    f.setSchoolId(schoolId);
    f.setEntityType(entityType);
    f.setEntityId(entityId);
    f.setConflictType(conflictType);
    f.setConflictingEntityId(conflictingEntityId);
    f.setMessage(message);
    return repo.save(f);
  }

  /**
   * Returns the active (non-dismissed) flags attached to a single entity. Dismissed flags are kept
   * in the database for audit but never appear in the default API surface.
   */
  @Transactional(readOnly = true)
  public List<ConflictFlag> findActiveByEntity(FlaggedEntity entityType, UUID entityId) {
    return repo.findByEntityTypeAndEntityIdAndDismissedFalse(entityType, entityId);
  }

  /**
   * Marks a flag as dismissed and stamps {@code dismissed_by_user_id} + {@code dismissed_at}.
   * Idempotent: a second call on an already-dismissed flag is a no-op (no exception, no clock
   * update). Throws {@link NotFoundException} for an unknown id.
   */
  @Transactional
  public ConflictFlag dismiss(UUID flagId, UserPrincipal actor) {
    if (actor == null) {
      throw new ForbiddenException("calendar.softFlag.dismiss");
    }
    if (actor.role() == Role.PARENT) {
      // Defense in depth — controllers should already have rejected this.
      throw new ForbiddenException("calendar.softFlag.dismiss");
    }
    ConflictFlag flag =
        repo.findById(flagId).orElseThrow(() -> new NotFoundException("ConflictFlag", flagId));
    if (flag.isDismissed()) {
      return flag; // idempotent
    }
    flag.setDismissed(true);
    flag.setDismissedByUserId(actor.id());
    flag.setDismissedAt(OffsetDateTime.now());
    return repo.save(flag);
  }

  // -- recompute hooks --------------------------------------------------------

  /**
   * Recomputes {@code DOUBLE_BOOKING} flags for an event after it's saved or moved. Clears every
   * existing flag involving the event (on either side of the pair), then inserts a fresh A&harr;B
   * pair for each current overlap. The bidirectional invariant — both endpoints carry a flag so the
   * FE can render warnings on both events without re-fetching the other side — is preserved by
   * inserting both rows in this single transaction.
   *
   * <p>Soft-deleted events skip overlap detection and only clear their flags (callers should also
   * call {@link #removeFlagsForEvent} after a hard delete to ensure the other side is cleaned up).
   *
   * <p>Idempotent: running this twice on the same event produces the same final state.
   */
  @Transactional
  public void recomputeForEvent(UUID eventId) {
    Event event =
        eventRepo.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));

    // Always clear first — both for soft-deletes (keep nothing) and live events (rebuild).
    repo.deleteDoubleBookingFlagsForEvent(eventId);

    if (event.getDeletedAt() != null) {
      return;
    }

    List<Event> overlaps =
        eventRepo.findOverlapping(
            event.getId(),
            event.getSchoolId(),
            event.getStartDt(),
            event.getEndDt(),
            event.getClassroomId(),
            event.getOrganizerUserId());

    for (Event other : overlaps) {
      // A -> B
      insertDoubleBookingPair(event, other);
      // B -> A
      insertDoubleBookingPair(other, event);
    }
  }

  /**
   * Hard-clears DOUBLE_BOOKING flags involving an event on either side. Called from the event
   * delete path so the other endpoint of every pair (B's flag pointing at A) is also cleared.
   */
  @Transactional
  public void removeFlagsForEvent(UUID eventId) {
    repo.deleteDoubleBookingFlagsForEvent(eventId);
  }

  private void insertDoubleBookingPair(Event subject, Event other) {
    ConflictFlag flag = new ConflictFlag();
    flag.setOrgId(subject.getOrgId());
    flag.setSchoolId(subject.getSchoolId());
    flag.setEntityType(FlaggedEntity.EVENT);
    flag.setEntityId(subject.getId());
    flag.setConflictType(SoftFlagType.DOUBLE_BOOKING);
    flag.setConflictingEntityId(other.getId());
    flag.setMessage("Overlaps with: " + other.getTitle());
    repo.save(flag);
  }

  // -- task recompute --------------------------------------------------------

  /**
   * Same-day same-assignee task overlap (architecture spec § 7.3). Bidirectional A&harr;B pair
   * inserted in one transaction. Conflict rule: same {@code school_id} + same {@code
   * assignee_user_id} + same {@code due_date} + neither task is {@link TaskStatus#DONE} + dueTime
   * within ±{@value #TASK_OVERLAP_MINUTES} minutes (or both null/missing → conflict; one null one
   * set → no conflict).
   *
   * <p>Soft-deleted tasks skip the overlap search and only clear flags. 404 for unknown task.
   */
  @Transactional
  public void recomputeForTask(UUID taskId) {
    Task task = taskRepo.findById(taskId).orElseThrow(() -> new NotFoundException("Task", taskId));

    repo.deleteDoubleBookingFlagsForTask(taskId);

    if (task.getDeletedAt() != null) {
      return;
    }
    if (task.getStatus() == TaskStatus.DONE) {
      return; // a DONE task can't conflict with anything per the rule
    }

    List<Task> candidates =
        taskRepo.findOverlapCandidates(
            task.getId(), task.getSchoolId(), task.getAssigneeUserId(), task.getDueDate());

    for (Task other : candidates) {
      if (!dueTimesOverlap(task.getDueTime(), other.getDueTime())) {
        continue;
      }
      insertTaskDoubleBookingPair(task, other);
      insertTaskDoubleBookingPair(other, task);
    }
  }

  /** See {@link #removeFlagsForEvent} — same idea, scoped to a task. */
  @Transactional
  public void removeFlagsForTask(UUID taskId) {
    repo.deleteDoubleBookingFlagsForTask(taskId);
  }

  static boolean dueTimesOverlap(LocalTime a, LocalTime b) {
    if (a == null && b == null) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    long diff = Math.abs(Duration.between(a, b).toMinutes());
    return diff <= TASK_OVERLAP_MINUTES;
  }

  private void insertTaskDoubleBookingPair(Task subject, Task other) {
    ConflictFlag flag = new ConflictFlag();
    flag.setOrgId(subject.getOrgId());
    flag.setSchoolId(subject.getSchoolId());
    flag.setEntityType(FlaggedEntity.TASK);
    flag.setEntityId(subject.getId());
    flag.setConflictType(SoftFlagType.DOUBLE_BOOKING);
    flag.setConflictingEntityId(other.getId());
    flag.setMessage("Overlaps with: " + other.getTitle());
    repo.save(flag);
  }

  // -- holiday recompute ------------------------------------------------------

  /**
   * Paints HOLIDAY soft-flags onto every event and task that lands on the holiday's {@code
   * (school_id, date)}. Idempotent: clears any existing flags pointing at the holiday first, then
   * re-paints if {@code holiday.approved}. An unapproved holiday clears flags but inserts none — so
   * approving and un-approving toggles the painted state cleanly.
   *
   * <p>The flag's {@code entity_id} is the event/task and {@code conflicting_entity_id} is the
   * holiday (per playbook common-failure-points: don't filter by {@code entity_id = holiday.id};
   * the holiday is on the conflicting side).
   */
  @Transactional
  public void recomputeForHoliday(Holiday holiday) {
    if (holiday == null || holiday.getId() == null) {
      throw new ValidationException("holiday", "holiday and id are required");
    }
    repo.deleteHolidayFlagsForHoliday(holiday.getId());
    if (!holiday.isApproved()) {
      return;
    }

    String message = "Falls on holiday: " + holiday.getName();

    List<Event> events = eventRepo.findBySchoolAndDate(holiday.getSchoolId(), holiday.getDate());
    for (Event e : events) {
      ConflictFlag f = new ConflictFlag();
      f.setOrgId(e.getOrgId());
      f.setSchoolId(e.getSchoolId());
      f.setEntityType(FlaggedEntity.EVENT);
      f.setEntityId(e.getId());
      f.setConflictType(SoftFlagType.HOLIDAY);
      f.setConflictingEntityId(holiday.getId());
      f.setMessage(message);
      repo.save(f);
    }

    List<Task> tasks =
        taskRepo.findBySchoolIdAndDueDateAndDeletedAtIsNull(
            holiday.getSchoolId(), holiday.getDate());
    for (Task t : tasks) {
      ConflictFlag f = new ConflictFlag();
      f.setOrgId(t.getOrgId());
      f.setSchoolId(t.getSchoolId());
      f.setEntityType(FlaggedEntity.TASK);
      f.setEntityId(t.getId());
      f.setConflictType(SoftFlagType.HOLIDAY);
      f.setConflictingEntityId(holiday.getId());
      f.setMessage(message);
      repo.save(f);
    }
  }
}
