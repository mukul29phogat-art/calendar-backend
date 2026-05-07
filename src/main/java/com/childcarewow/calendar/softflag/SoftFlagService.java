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

  private final ConflictFlagRepository repo;
  private final EventRepository eventRepo;

  public SoftFlagService(ConflictFlagRepository repo, EventRepository eventRepo) {
    this.repo = repo;
    this.eventRepo = eventRepo;
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
}
