package com.childcarewow.calendar.importantdate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportantDateRepository extends JpaRepository<ImportantDate, UUID> {

  /**
   * Calendar-feed query (Part 7.3). Returns non-deleted important_dates at the school whose date
   * falls in the inclusive {@code [from, to]} window. Both BIRTHDAY and IMPORTANT kinds; visibility
   * narrowing (PARENT clamp on {@code visible_to_parents=true} and birthdays-of-own-child) happens
   * in the service.
   */
  @Query(
      "SELECT i FROM ImportantDate i "
          + "WHERE i.schoolId = :schoolId "
          + "AND i.deletedAt IS NULL "
          + "AND i.date >= :from "
          + "AND i.date <= :to "
          + "ORDER BY i.date ASC")
  List<ImportantDate> findInWindow(
      @Param("schoolId") UUID schoolId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
