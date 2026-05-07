package com.childcarewow.calendar.conflict;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ConflictFlagRepository extends JpaRepository<ConflictFlag, UUID> {

  /**
   * Returns the active (non-dismissed) flags attached to a given entity. Mirrors the {@code
   * idx_conflict_flags_entity} partial index's predicate.
   */
  List<ConflictFlag> findByEntityTypeAndEntityIdAndDismissedFalse(
      FlaggedEntity entityType, UUID entityId);

  /**
   * Hard-deletes every {@code DOUBLE_BOOKING} flag involving an event on either side of the pair.
   * Used by the recompute path before re-inserting freshly-detected overlaps. We delete (not
   * dismiss) because the bidirectional invariant on rebuild expects a clean slate; old dismissed
   * rows would leak into history with stale conflict data.
   */
  @Modifying
  @Transactional
  @Query(
      "DELETE FROM ConflictFlag c "
          + "WHERE c.entityType = com.childcarewow.calendar.conflict.FlaggedEntity.EVENT "
          + "AND c.conflictType = com.childcarewow.calendar.conflict.SoftFlagType.DOUBLE_BOOKING "
          + "AND (c.entityId = :eventId OR c.conflictingEntityId = :eventId)")
  int deleteDoubleBookingFlagsForEvent(@Param("eventId") UUID eventId);

  /** Same idea as the event variant, scoped to TASK entities. Used by {@code recomputeForTask}. */
  @Modifying
  @Transactional
  @Query(
      "DELETE FROM ConflictFlag c "
          + "WHERE c.entityType = com.childcarewow.calendar.conflict.FlaggedEntity.TASK "
          + "AND c.conflictType = com.childcarewow.calendar.conflict.SoftFlagType.DOUBLE_BOOKING "
          + "AND (c.entityId = :taskId OR c.conflictingEntityId = :taskId)")
  int deleteDoubleBookingFlagsForTask(@Param("taskId") UUID taskId);

  /**
   * Hard-clears every {@code HOLIDAY} flag pointing at the holiday via {@code
   * conflicting_entity_id}. Per the playbook's common-failure-points: the HOLIDAY flag's {@code
   * entity_id} is the event/task, not the holiday — so we filter on {@code conflicting_entity_id}.
   */
  @Modifying
  @Transactional
  @Query(
      "DELETE FROM ConflictFlag c "
          + "WHERE c.conflictType = com.childcarewow.calendar.conflict.SoftFlagType.HOLIDAY "
          + "AND c.conflictingEntityId = :holidayId")
  int deleteHolidayFlagsForHoliday(@Param("holidayId") UUID holidayId);
}
