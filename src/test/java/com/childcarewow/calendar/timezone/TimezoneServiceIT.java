package com.childcarewow.calendar.timezone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises {@link TimezoneService} against the real platform DB (school timezones) and the real
 * calendar DB (holidays). Covers cache behavior, DST correctness, holiday lookup, and the
 * unknown-school path.
 */
@SpringBootTest
@Transactional // rolls back any holiday rows we insert
class TimezoneServiceIT {

  // From docker/platform-seed.sql
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-000000000000");

  @Autowired TimezoneService service;
  @Autowired MeterRegistry meterRegistry;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    // Belt-and-suspenders: even though @Transactional rolls back, drop any rows we wrote.
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'TZ_TEST_%'");
  }

  @Test
  void seededSchoolsHaveExpectedZones() {
    assertThat(service.zoneFor(SUNRISE)).isEqualTo(ZoneId.of("America/New_York"));
    assertThat(service.zoneFor(MAPLEWOOD)).isEqualTo(ZoneId.of("America/Chicago"));
  }

  @Test
  void zoneCacheHitsOnRepeatedCall() {
    UUID isolatedSchool = SUNRISE;
    double hitsBefore = meterRegistry.counter("timezone_service_cache_hits").count();
    double missesBefore = meterRegistry.counter("timezone_service_cache_misses").count();

    service.zoneFor(isolatedSchool); // could be miss or hit depending on test order
    service.zoneFor(isolatedSchool); // definitely hit
    service.zoneFor(isolatedSchool); // definitely hit

    double hitsAfter = meterRegistry.counter("timezone_service_cache_hits").count();
    double missesAfter = meterRegistry.counter("timezone_service_cache_misses").count();

    assertThat(hitsAfter - hitsBefore).isGreaterThanOrEqualTo(2);
    assertThat(missesAfter - missesBefore).isGreaterThanOrEqualTo(0);
  }

  /**
   * Two UTC instants that bracket the fall-back boundary in America/New_York 2026 (DST ends at
   * 2026-11-01 06:00 UTC = 2 AM EDT, then clocks go back to 1 AM EST). Both instants must map to
   * the same school-local date {@code 2026-11-01}.
   */
  @Test
  void dstFallBackInNyMapsToSameLocalDate() {
    Instant before = Instant.parse("2026-11-01T05:30:00Z"); // 1:30 AM EDT (-4) → still Nov 1
    Instant after = Instant.parse("2026-11-01T06:30:00Z"); // 1:30 AM EST (-5) → still Nov 1

    assertThat(service.toSchoolLocalDate(before, SUNRISE)).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(service.toSchoolLocalDate(after, SUNRISE)).isEqualTo(LocalDate.of(2026, 11, 1));
  }

  /**
   * Demonstrates timezone matters: at 2026-06-15 04:00 UTC, NY (EDT -4) is at 00:00 → June 15;
   * Chicago (CDT -5) is at 23:00 → still June 14.
   */
  @Test
  void differentTimezonesDifferentLocalDates() {
    Instant aroundMidnightInNy = Instant.parse("2026-06-15T04:00:00Z");
    assertThat(service.toSchoolLocalDate(aroundMidnightInNy, SUNRISE))
        .isEqualTo(LocalDate.of(2026, 6, 15));
    assertThat(service.toSchoolLocalDate(aroundMidnightInNy, MAPLEWOOD))
        .isEqualTo(LocalDate.of(2026, 6, 14));
  }

  @Test
  void approvedHolidayForSchoolIsRecognised() {
    LocalDate date = LocalDate.of(2026, 12, 25);
    insertHoliday(SUNRISE, date, "TZ_TEST_xmas", true);

    assertThat(service.isHolidayForSchool(SUNRISE, date)).isTrue();
    assertThat(service.isHolidayForSchool(SUNRISE, date.plusDays(1))).isFalse();
    // Other school on same date — not a holiday for them.
    assertThat(service.isHolidayForSchool(MAPLEWOOD, date)).isFalse();
  }

  @Test
  void pendingFederalHolidayDoesNotCount() {
    LocalDate date = LocalDate.of(2026, 7, 4);
    insertHoliday(SUNRISE, date, "TZ_TEST_july4_pending", false);
    assertThat(service.isHolidayForSchool(SUNRISE, date)).isFalse();
  }

  @Test
  void unknownSchoolThrows() {
    assertThatThrownBy(() -> service.zoneFor(UNKNOWN))
        .isInstanceOf(UnknownSchoolTimezoneException.class)
        .hasMessageContaining("School not found");
  }

  // -- helper -----------------------------------------------------------------

  private void insertHoliday(UUID schoolId, LocalDate date, String name, boolean approved) {
    UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "approved_at, approved_by_user_id, created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, 'CUSTOM', ?, ?, ?, ?)",
        UUID.randomUUID(),
        orgId,
        schoolId,
        date,
        name,
        approved,
        approved ? OffsetDateTime.now() : null,
        approved ? UUID.fromString("33333333-0000-0000-0000-000000000001") : null,
        UUID.fromString("33333333-0000-0000-0000-000000000001"));
  }
}
