package com.childcarewow.calendar.holiday;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

  /**
   * Returns an existing approved, non-deleted holiday on {@code (schoolId, date)} — the duplicate
   * check the service runs before insert. Filtered by {@code approved=true} because an unapproved
   * (federal-pending) row on the same date is allowed to coexist with a CUSTOM creation; once both
   * land approved, the second insert is the conflict.
   */
  @Query(
      "SELECT h FROM Holiday h WHERE h.schoolId = :schoolId AND h.date = :date "
          + "AND h.approved = true AND h.deletedAt IS NULL")
  Optional<Holiday> findApprovedAt(UUID schoolId, LocalDate date);
}
