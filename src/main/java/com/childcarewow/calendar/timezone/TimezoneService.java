package com.childcarewow.calendar.timezone;

import com.childcarewow.calendar.exception.PlatformUnavailableException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for school-local time. The calendar stores everything in UTC ({@code
 * timestamptz}) and renders in the school's IANA zone.
 *
 * <p><b>Cache.</b> {@code (schoolId → ZoneId)} is cached in Caffeine for 1 hour. Schools change
 * timezones rarely; the trade-off is that a TZ edit on the platform side takes up to 1 hour to
 * propagate to running calendar instances. Holiday lookups are <i>not</i> cached — they hit the
 * calendar DB on every call. Series 11 may add a per-(schoolId,date) cache once the request pattern
 * is observed in production.
 *
 * <p><b>Failure semantics.</b> Platform-DB outages on {@link #zoneFor(UUID)} surface as {@link
 * PlatformUnavailableException} (HTTP 503) — fail-closed, matching the pattern from Part 2.3's
 * {@code PlatformEntityValidator}. An unknown {@code schoolId} or a row with a malformed IANA name
 * surfaces as {@link UnknownSchoolTimezoneException} (HTTP 404).
 */
@Service
public class TimezoneService {

  private static final Logger log = LoggerFactory.getLogger(TimezoneService.class);

  private final JdbcTemplate platformJdbc;
  private final JdbcTemplate calendarJdbc;
  private final Cache<UUID, ZoneId> zoneCache =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(1, TimeUnit.HOURS).build();
  private final Counter zoneCacheHits;
  private final Counter zoneCacheMisses;

  public TimezoneService(
      @Qualifier("platformJdbcTemplate") JdbcTemplate platformJdbc,
      @Qualifier("calendarJdbcTemplate") JdbcTemplate calendarJdbc,
      MeterRegistry meterRegistry) {
    this.platformJdbc = platformJdbc;
    this.calendarJdbc = calendarJdbc;
    this.zoneCacheHits = meterRegistry.counter("timezone_service_cache_hits");
    this.zoneCacheMisses = meterRegistry.counter("timezone_service_cache_misses");
  }

  /** Returns the school's IANA {@link ZoneId}. Cached for 1 hour. */
  public ZoneId zoneFor(UUID schoolId) {
    ZoneId cached = zoneCache.getIfPresent(schoolId);
    if (cached != null) {
      zoneCacheHits.increment();
      return cached;
    }
    zoneCacheMisses.increment();
    String iana = lookupTimezoneFromPlatform(schoolId);
    ZoneId zone = parseZoneOrThrow(iana, schoolId);
    zoneCache.put(schoolId, zone);
    return zone;
  }

  /**
   * Converts a UTC {@link Instant} to the calendar date a viewer in the school's timezone would
   * see. DST-correct: at fall-back the same UTC instant maps to a single school-local date even
   * though the wall clock visits the same hour twice.
   */
  public LocalDate toSchoolLocalDate(Instant instant, UUID schoolId) {
    return instant.atZone(zoneFor(schoolId)).toLocalDate();
  }

  /**
   * True if {@code localDate} is an approved (active) holiday at the given school. Soft-deleted
   * rows are excluded. Federal holidays in pending state ({@code approved=false}) do not count.
   */
  public boolean isHolidayForSchool(UUID schoolId, LocalDate localDate) {
    Integer count =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays "
                + "WHERE school_id = ? AND date = ? AND approved = true AND deleted_at IS NULL",
            Integer.class,
            schoolId,
            localDate);
    return count != null && count > 0;
  }

  // -- internals --------------------------------------------------------------

  private String lookupTimezoneFromPlatform(UUID schoolId) {
    try {
      return platformJdbc.queryForObject(
          "SELECT timezone FROM schools WHERE id = ?", String.class, schoolId);
    } catch (EmptyResultDataAccessException ex) {
      throw new UnknownSchoolTimezoneException(schoolId, "School not found", ex);
    } catch (DataAccessResourceFailureException ex) {
      throw new PlatformUnavailableException("Platform DB unreachable for timezone lookup", ex);
    }
  }

  private static ZoneId parseZoneOrThrow(String iana, UUID schoolId) {
    if (iana == null || iana.isBlank()) {
      throw new UnknownSchoolTimezoneException(schoolId, "School row has no timezone", null);
    }
    try {
      return ZoneId.of(iana);
    } catch (java.time.DateTimeException ex) {
      // Covers ZoneRulesException (unknown zone), DateTimeException (bad format), etc.
      log.warn("School {} has malformed IANA timezone '{}'", schoolId, iana, ex);
      throw new UnknownSchoolTimezoneException(schoolId, "Malformed IANA timezone: " + iana, ex);
    }
  }
}
