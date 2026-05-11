package com.childcarewow.calendar.federalholiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.childcarewow.calendar.audit.AuditService;
import com.childcarewow.calendar.federalholiday.FederalHolidaySyncJob.SyncResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-DB integration test for {@link FederalHolidaySyncJob}. Mocks only the Nager HTTP client; the
 * upsert SQL, the existing-federal pre-fetch, the platform-schools read, and the audit-row write
 * all exercise live calendar + platform Postgres. The clock is pinned to mid-2026 so year math is
 * deterministic and assertions can reference {@code 2026} / {@code 2027} literally.
 *
 * <p>Cleanup is name-prefix-scoped ({@code IT-fs-%}) so the test never interferes with whatever
 * federal rows accumulate in the dev DB from other tests or manual runs.
 */
@SpringBootTest
class FederalHolidaySyncJobIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");

  private static final Clock FIXED_2026 =
      Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneOffset.UTC);

  @MockBean NagerDateClient nagerClient;
  @Autowired AuditService auditService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @Autowired
  @Qualifier("platformJdbcTemplate")
  JdbcTemplate platformJdbc;

  private FederalHolidaySyncJob job;

  @BeforeEach
  void setup() {
    job =
        new FederalHolidaySyncJob(
            nagerClient, calendarJdbc, platformJdbc, auditService, FIXED_2026);
  }

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM conflict_flags WHERE conflicting_entity_id IN "
            + "(SELECT id FROM holidays WHERE name LIKE 'IT-fs-%')");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-fs-%'");
    calendarJdbc.update("DELETE FROM audit_events WHERE action = 'NAGER_SYNC'");
  }

  @Test
  void insertsPendingFederalsForEachSchoolAndYear() {
    when(nagerClient.fetchPublicHolidays(eq(2026), eq("US")))
        .thenReturn(
            List.of(
                new NagerHoliday(LocalDate.of(2026, 7, 4), "IT-fs-Independence-2026"),
                new NagerHoliday(LocalDate.of(2026, 11, 26), "IT-fs-Thanksgiving-2026")));
    when(nagerClient.fetchPublicHolidays(eq(2027), eq("US")))
        .thenReturn(List.of(new NagerHoliday(LocalDate.of(2027, 7, 4), "IT-fs-Independence-2027")));

    SyncResult result = job.sync();

    // 2 schools × (2 + 1) holidays = 6 inserts; no pre-existing rows so nothing skipped.
    assertThat(result.inserted()).isEqualTo(6);
    assertThat(result.skipped()).isZero();
    assertThat(result.errors()).isEmpty();

    Integer total =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays "
                + "WHERE source = 'FEDERAL' AND approved = false AND name LIKE 'IT-fs-%'",
            Integer.class);
    assertThat(total).isEqualTo(6);

    Integer atSunrise =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays WHERE school_id = ? AND name LIKE 'IT-fs-%'",
            Integer.class, SUNRISE);
    Integer atMaplewood =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays WHERE school_id = ? AND name LIKE 'IT-fs-%'",
            Integer.class, MAPLEWOOD);
    assertThat(atSunrise).isEqualTo(3);
    assertThat(atMaplewood).isEqualTo(3);
  }

  @Test
  void idempotentReRunDoesNotInsertDuplicates() {
    when(nagerClient.fetchPublicHolidays(eq(2026), eq("US")))
        .thenReturn(List.of(new NagerHoliday(LocalDate.of(2026, 7, 4), "IT-fs-July4-26")));
    when(nagerClient.fetchPublicHolidays(eq(2027), eq("US")))
        .thenReturn(List.of(new NagerHoliday(LocalDate.of(2027, 7, 4), "IT-fs-July4-27")));

    SyncResult first = job.sync();
    SyncResult second = job.sync();

    // First run: 2 schools × 2 years × 1 holiday = 4 inserts.
    assertThat(first.inserted()).isEqualTo(4);
    assertThat(first.skipped()).isZero();
    // Second run: every (school, date) already present in the pre-fetch → all skipped.
    assertThat(second.inserted()).isZero();
    assertThat(second.skipped()).isEqualTo(4);

    Integer count =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays WHERE name LIKE 'IT-fs-%'", Integer.class);
    assertThat(count).isEqualTo(4);
  }

  @Test
  void existingApprovedFederalDoesNotGetDuplicatedAsPending() {
    // Pre-insert one APPROVED federal at (Sunrise, 2026-07-04). This simulates the admin having
    // already approved a previously-synced pending row.
    UUID approvedId = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO holidays "
            + "(id, org_id, school_id, date, name, source, approved, approved_at, approved_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, 'FEDERAL', true, ?, ?)",
        approvedId,
        ORG,
        SUNRISE,
        LocalDate.of(2026, 7, 4),
        "IT-fs-July4-PreApproved",
        OffsetDateTime.now(),
        OLIVIA);

    when(nagerClient.fetchPublicHolidays(eq(2026), eq("US")))
        .thenReturn(
            List.of(
                new NagerHoliday(LocalDate.of(2026, 7, 4), "IT-fs-July4-2026"),
                new NagerHoliday(LocalDate.of(2026, 12, 25), "IT-fs-Christmas-2026")));
    when(nagerClient.fetchPublicHolidays(eq(2027), eq("US"))).thenReturn(List.of());

    SyncResult result = job.sync();

    // 2 schools × 2 holidays = 4 candidate pairs, minus the pre-existing (Sunrise, 2026-07-04) → 3.
    assertThat(result.inserted()).isEqualTo(3);
    assertThat(result.skipped()).isEqualTo(1);

    // Sunrise still has only ONE row for 2026-07-04 — the originally-approved one.
    Integer sunriseJuly4 =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays WHERE school_id = ? AND date = ? AND deleted_at IS NULL",
            Integer.class,
            SUNRISE,
            LocalDate.of(2026, 7, 4));
    assertThat(sunriseJuly4).isEqualTo(1);

    // …and it's still approved (no re-insertion of a pending duplicate).
    Boolean stillApproved =
        calendarJdbc.queryForObject(
            "SELECT approved FROM holidays WHERE id = ?", Boolean.class, approvedId);
    assertThat(stillApproved).isTrue();
  }

  @Test
  void nagerFetchErrorIsRecordedAndDoesNotBlockOtherGroups() {
    // Year 2026 fetch fails; year 2027 succeeds — verifies per-group fail-soft semantics.
    when(nagerClient.fetchPublicHolidays(eq(2026), eq("US")))
        .thenThrow(new RuntimeException("simulated 503 from Nager.Date"));
    when(nagerClient.fetchPublicHolidays(eq(2027), eq("US")))
        .thenReturn(List.of(new NagerHoliday(LocalDate.of(2027, 7, 4), "IT-fs-July4-2027")));

    SyncResult result = job.sync();

    assertThat(result.inserted()).isEqualTo(2); // 2 schools × 1 holiday in 2027
    assertThat(result.errors()).hasSize(1);
    assertThat(result.errors().get(0)).contains("2026/US").contains("RuntimeException");

    // Year 2026 produced zero rows; year 2027 produced two rows.
    Integer count2026 =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays WHERE name LIKE 'IT-fs-%' "
                + "AND date >= '2026-01-01' AND date <= '2026-12-31'",
            Integer.class);
    Integer count2027 =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays WHERE name LIKE 'IT-fs-%' "
                + "AND date >= '2027-01-01' AND date <= '2027-12-31'",
            Integer.class);
    assertThat(count2026).isZero();
    assertThat(count2027).isEqualTo(2);
  }

  @Test
  void writesAuditRowWithSyncSummary() {
    when(nagerClient.fetchPublicHolidays(anyInt(), eq("US")))
        .thenReturn(List.of(new NagerHoliday(LocalDate.of(2026, 7, 4), "IT-fs-Audit-July4")));

    job.sync();

    Integer auditCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_events "
                + "WHERE action = 'NAGER_SYNC' AND user_agent = 'FederalHolidaySyncJob'",
            Integer.class);
    assertThat(auditCount).isEqualTo(1);

    String metadataJson =
        calendarJdbc.queryForObject(
            "SELECT metadata::text FROM audit_events WHERE action = 'NAGER_SYNC'", String.class);
    // Postgres jsonb ::text cast normalises with a space after the colon — match that exactly.
    assertThat(metadataJson)
        .contains("\"inserted\"")
        .contains("\"skipped\"")
        .contains("\"years\"")
        .contains("2026")
        .contains("2027")
        .contains("\"schools_count\": 2");
  }
}
