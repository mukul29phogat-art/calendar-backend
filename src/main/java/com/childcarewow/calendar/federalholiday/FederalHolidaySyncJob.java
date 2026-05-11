package com.childcarewow.calendar.federalholiday;

import com.childcarewow.calendar.audit.AuditService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily sync of federal holidays from Nager.Date into the calendar {@code holidays} table.
 *
 * <p>For each unique {@code (org_id, country_code)} pair in {@code platform.schools} and each year
 * in {@code [currentYear, currentYear+1]}, fetches Nager.Date and upserts one pending-federal row
 * per matching school × date. Rows land with {@code source=FEDERAL, approved=false}; an admin
 * approves them via Part 6.4 / 6.5 before they actually block scheduling.
 *
 * <p><b>Idempotency strategy.</b> The named partial unique index {@code
 * uq_holidays_federal_pending} covers concurrent pending-pending collisions ({@code ON CONFLICT DO
 * NOTHING}). It does NOT cover the case where an admin already approved a previously-synced row —
 * that row now matches a different partial index, so a fresh pending INSERT would coexist with the
 * approved row, silently accumulating duplicates each sync run. We avoid that by pre-fetching every
 * existing non-deleted federal {@code (school_id, date)} pair in the year window and filtering the
 * Nager payload against it. The {@code ON CONFLICT} clause then only fires as a race-safety net.
 *
 * <p><b>Failure semantics.</b> Per-fetch errors (network, 5xx, malformed payload) are caught,
 * logged at WARN, recorded in the audit row's {@code errors} field, and the loop continues to the
 * next year / country. Existing holiday rows are not touched on failure paths.
 *
 * <p><b>Multi-instance coordination.</b> {@code @SchedulerLock(name = "NAGER_SYNC")} binds against
 * the calendar-DB shedlock table (V9 migration), so only one ECS task runs each tick. The 30-minute
 * lock-at-most ceiling is sized for the worst current load: 2 years × N orgs × 1 country × ~20
 * holidays × M schools, with two JDBC queries per (year, country) group.
 */
@Component
public class FederalHolidaySyncJob {

  private static final Logger log = LoggerFactory.getLogger(FederalHolidaySyncJob.class);

  /** Two-year horizon: current year and next year. Aligns with typical school-calendar planning. */
  private static final List<Integer> YEAR_OFFSETS = List.of(0, 1);

  private final NagerDateClient nagerClient;
  private final JdbcTemplate calendarJdbc;
  private final JdbcTemplate platformJdbc;
  private final AuditService auditService;
  private final Clock clock;

  @Autowired
  public FederalHolidaySyncJob(
      NagerDateClient nagerClient,
      @Qualifier("calendarJdbcTemplate") JdbcTemplate calendarJdbc,
      @Qualifier("platformJdbcTemplate") JdbcTemplate platformJdbc,
      AuditService auditService) {
    this(nagerClient, calendarJdbc, platformJdbc, auditService, Clock.system(ZoneOffset.UTC));
  }

  /** Test seam — pin the clock so year-boundary cases are deterministic. */
  FederalHolidaySyncJob(
      NagerDateClient nagerClient,
      JdbcTemplate calendarJdbc,
      JdbcTemplate platformJdbc,
      AuditService auditService,
      Clock clock) {
    this.nagerClient = nagerClient;
    this.calendarJdbc = calendarJdbc;
    this.platformJdbc = platformJdbc;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
  @SchedulerLock(name = "NAGER_SYNC", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
  public void runScheduled() {
    sync();
  }

  /**
   * Visible for tests and operations runbook. Returns a summary so manual invocations (Series 11
   * admin tooling) can render the result.
   */
  public SyncResult sync() {
    int currentYear = Year.now(clock).getValue();
    List<Integer> years = YEAR_OFFSETS.stream().map(o -> currentYear + o).toList();

    Map<OrgCountry, List<UUID>> schoolsByOrgCountry = loadSchoolGroups();
    int inserted = 0;
    int skipped = 0;
    int totalSchools = schoolsByOrgCountry.values().stream().mapToInt(List::size).sum();
    List<String> errors = new ArrayList<>();

    for (Map.Entry<OrgCountry, List<UUID>> entry : schoolsByOrgCountry.entrySet()) {
      UUID orgId = entry.getKey().orgId();
      String countryCode = entry.getKey().countryCode();
      List<UUID> schoolIds = entry.getValue();

      for (int year : years) {
        List<NagerHoliday> nagerRows;
        try {
          nagerRows = nagerClient.fetchPublicHolidays(year, countryCode);
        } catch (RuntimeException ex) {
          // Fail-soft per group: existing data untouched, audit row still written at the end.
          log.warn(
              "Nager.Date fetch failed for year={}, country={}: {}",
              year,
              countryCode,
              ex.getMessage());
          errors.add(year + "/" + countryCode + ":" + ex.getClass().getSimpleName());
          continue;
        }

        Set<ExistingKey> existing = loadExistingFederalsForYear(schoolIds, year);

        for (NagerHoliday h : nagerRows) {
          for (UUID schoolId : schoolIds) {
            if (existing.contains(new ExistingKey(schoolId, h.date()))) {
              skipped++;
              continue;
            }
            // ON CONFLICT uses the column-inference clause matching the partial unique index
            // uq_holidays_federal_pending (V4). The WHERE predicate must match the index's WHERE
            // byte-for-byte for Postgres to pick the index — `ON CONFLICT ON CONSTRAINT` would
            // be cleaner but Postgres only accepts constraint-by-name for non-partial unique
            // constraints. Architecture spec §7.8 will need a docs amendment.
            int rows =
                calendarJdbc.update(
                    "INSERT INTO holidays "
                        + "(id, org_id, school_id, date, name, source, approved) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, ?, 'FEDERAL', false) "
                        + "ON CONFLICT (school_id, date) "
                        + "WHERE source = 'FEDERAL' AND approved = false AND deleted_at IS NULL "
                        + "DO NOTHING",
                    orgId,
                    schoolId,
                    h.date(),
                    h.localName());
            if (rows > 0) {
              inserted++;
            } else {
              skipped++;
            }
          }
        }
      }
    }

    Map<String, Object> meta = new HashMap<>();
    meta.put("years", years);
    meta.put("schools_count", totalSchools);
    meta.put("inserted", inserted);
    meta.put("skipped", skipped);
    meta.put("errors", errors);
    auditService.log(null, "NAGER_SYNC", null, null, null, "FederalHolidaySyncJob", meta);

    log.info(
        "Nager sync done: years={}, schools={}, inserted={}, skipped={}, errors={}",
        years,
        totalSchools,
        inserted,
        skipped,
        errors.size());
    return new SyncResult(inserted, skipped, errors);
  }

  private Map<OrgCountry, List<UUID>> loadSchoolGroups() {
    Map<OrgCountry, List<UUID>> result = new LinkedHashMap<>();
    platformJdbc.query(
        "SELECT id, org_id, country_code FROM schools ORDER BY org_id, country_code, id",
        rs -> {
          UUID schoolId = rs.getObject("id", UUID.class);
          UUID orgId = rs.getObject("org_id", UUID.class);
          String country = rs.getString("country_code");
          result
              .computeIfAbsent(new OrgCountry(orgId, country), k -> new ArrayList<>())
              .add(schoolId);
        });
    return result;
  }

  private Set<ExistingKey> loadExistingFederalsForYear(List<UUID> schoolIds, int year) {
    if (schoolIds.isEmpty()) {
      return Set.of();
    }
    String inClause = schoolIds.stream().map(s -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT school_id, date FROM holidays "
            + "WHERE source = 'FEDERAL' AND deleted_at IS NULL "
            + "AND date >= ? AND date <= ? "
            + "AND school_id IN ("
            + inClause
            + ")";
    Object[] params = new Object[2 + schoolIds.size()];
    params[0] = LocalDate.of(year, 1, 1);
    params[1] = LocalDate.of(year, 12, 31);
    for (int i = 0; i < schoolIds.size(); i++) {
      params[i + 2] = schoolIds.get(i);
    }
    Set<ExistingKey> set = new HashSet<>();
    calendarJdbc.query(
        sql,
        rs -> {
          UUID schoolId = rs.getObject("school_id", UUID.class);
          LocalDate date = rs.getObject("date", LocalDate.class);
          set.add(new ExistingKey(schoolId, date));
        },
        params);
    return set;
  }

  private record OrgCountry(UUID orgId, String countryCode) {}

  private record ExistingKey(UUID schoolId, LocalDate date) {}

  /** Result returned from {@link #sync()} so manual invocations can render a summary. */
  public record SyncResult(int inserted, int skipped, List<String> errors) {}
}
