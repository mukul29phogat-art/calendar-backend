package com.childcarewow.calendar.holiday;

import java.time.LocalDate;
import java.util.List;
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

  /**
   * Filtered list query for {@code GET /api/v1/holidays}. {@code approved} and {@code source} are
   * nullable — null skips the filter. Always excludes soft-deleted rows. Ordered by date ascending
   * for stable pagination/UX.
   */
  @Query(
      "SELECT h FROM Holiday h WHERE h.schoolId = :schoolId "
          + "AND h.deletedAt IS NULL "
          + "AND (:approved IS NULL OR h.approved = :approved) "
          + "AND (:source IS NULL OR h.source = :source) "
          + "ORDER BY h.date ASC")
  List<Holiday> findFiltered(UUID schoolId, Boolean approved, HolidaySource source);

  /**
   * Calendar-feed query (Part 7.3). Returns only approved, non-deleted holidays at the school whose
   * date falls in the inclusive {@code [from, to]} window. Pending-federal rows never appear on the
   * calendar — they live in the approval queue (Part 6.4) until an admin acts on them.
   */
  @Query(
      "SELECT h FROM Holiday h WHERE h.schoolId = :schoolId "
          + "AND h.approved = true "
          + "AND h.deletedAt IS NULL "
          + "AND h.date >= :from "
          + "AND h.date <= :to "
          + "ORDER BY h.date ASC")
  List<Holiday> findApprovedInWindow(
      UUID schoolId, java.time.LocalDate from, java.time.LocalDate to);
}
