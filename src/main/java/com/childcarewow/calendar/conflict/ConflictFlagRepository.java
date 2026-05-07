package com.childcarewow.calendar.conflict;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConflictFlagRepository extends JpaRepository<ConflictFlag, UUID> {

  /**
   * Returns the active (non-dismissed) flags attached to a given entity. Mirrors the {@code
   * idx_conflict_flags_entity} partial index's predicate.
   */
  List<ConflictFlag> findByEntityTypeAndEntityIdAndDismissedFalse(
      FlaggedEntity entityType, UUID entityId);
}
