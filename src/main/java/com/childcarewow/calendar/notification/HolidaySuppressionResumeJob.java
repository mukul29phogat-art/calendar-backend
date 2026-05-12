package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.timezone.TimezoneService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resumes notifications that were paused because the related event/task fell on a holiday — once
 * the holiday date passes, the notification is no longer holiday-blocked and should fire.
 *
 * <p>Architecture spec §7.4 + playbook Part 11.8: scheduled job at 00:01 school-local. Per the
 * playbook common-failure-point, running the job in UTC is wrong because schools span multiple IANA
 * zones. Solution: run hourly, then for each school separately compute "today" in that school's
 * timezone and unpause notifications whose related entity date is strictly before that.
 *
 * <p><b>Why "&lt; today_school_local" instead of "== yesterday_school_local"?</b> Two reasons. (1)
 * Idempotency — running the job twice on the same day must not double-unpause; rows already
 * unpaused (paused=false) are excluded by the WHERE clause. (2) Catch-up — if a scheduler outage
 * skipped the 00:01 tick, the next hourly run still recovers; "&lt;" includes "yesterday" plus any
 * older paused-but-still-stuck rows.
 *
 * <p><b>Dispatch wiring intentionally omitted.</b> The playbook spec says "mark paused=false and
 * dispatch." The unpause is here; the dispatch hand-off needs the cross-DB recipient → email
 * resolver + delivery audit composition that lives in a follow-up Part. For now, unpaused
 * notifications surface to the FE bell on the next `GET /me` poll; real outbound dispatch hooks in
 * when the upstream pipeline lands.
 *
 * <p><b>ShedLock</b> binds the {@code HOLIDAY_RESUME} lock to the calendar-DB {@code shedlock}
 * table so multi-instance ECS doesn't double-unpause. {@code lockAtMostFor=PT10M} is comfortable
 * headroom over typical row counts (<10K paused rows × a few ms per UPDATE).
 */
@Component
public class HolidaySuppressionResumeJob {

  private static final Logger log = LoggerFactory.getLogger(HolidaySuppressionResumeJob.class);

  /** Prefix written by {@link NotificationService} on holiday-blocked pauses. */
  static final String HOLIDAY_REASON_PREFIX = "Holiday: ";

  private final JdbcTemplate calendarJdbc;
  private final TimezoneService timezoneService;

  public HolidaySuppressionResumeJob(
      @Qualifier("calendarJdbcTemplate") JdbcTemplate calendarJdbc,
      TimezoneService timezoneService) {
    this.calendarJdbc = calendarJdbc;
    this.timezoneService = timezoneService;
  }

  /**
   * Hourly schedule. {@code "0 0 * * * *"} = at minute 0 of every hour, every day. Multi-instance
   * coordination via ShedLock — only one container's job fires per tick.
   */
  @Scheduled(cron = "0 0 * * * *", zone = "UTC")
  @SchedulerLock(name = "HOLIDAY_RESUME", lockAtMostFor = "PT10M")
  public void resume() {
    resumeUsingClock(Clock.systemUTC());
  }

  /**
   * Visible for tests. Iterates schools with paused-holiday notifications, computes today's
   * school-local date for each, and unpauses notifications whose related event/task date is before
   * that local-today.
   *
   * @return total notifications unpaused across all schools (for log + assertion).
   */
  @Transactional
  public int resumeUsingClock(Clock clock) {
    List<UUID> candidateSchools = findSchoolsWithPausedHolidayNotifications();
    int totalUnpaused = 0;
    for (UUID schoolId : candidateSchools) {
      try {
        ZoneId zone = timezoneService.zoneFor(schoolId);
        LocalDate todaySchoolLocal = LocalDate.now(clock.withZone(zone));
        int unpausedEvents = unpauseEventNotifications(schoolId, zone, todaySchoolLocal);
        int unpausedTasks = unpauseTaskNotifications(schoolId, todaySchoolLocal);
        int perSchool = unpausedEvents + unpausedTasks;
        if (perSchool > 0) {
          log.info(
              "Unpaused {} holiday-suppressed notifications at school={} (today_school_local={})",
              perSchool,
              schoolId,
              todaySchoolLocal);
        }
        totalUnpaused += perSchool;
      } catch (RuntimeException e) {
        // A single school's timezone failure shouldn't stop the rest. Skip + log.
        log.warn("Could not resume holiday-paused notifications for school={}", schoolId, e);
      }
    }
    return totalUnpaused;
  }

  private List<UUID> findSchoolsWithPausedHolidayNotifications() {
    return calendarJdbc.queryForList(
        "SELECT DISTINCT school_id FROM notifications "
            + "WHERE paused = true AND paused_reason LIKE ?",
        UUID.class,
        HOLIDAY_REASON_PREFIX + "%");
  }

  /**
   * Events: convert the row's {@code start_dt} (timestamptz) to the school-local date via {@code AT
   * TIME ZONE}, then compare to {@code todaySchoolLocal}. Strict "&lt;" so a holiday running
   * through today (rare, but possible for multi-day events) doesn't prematurely resume.
   */
  private int unpauseEventNotifications(UUID schoolId, ZoneId zone, LocalDate todaySchoolLocal) {
    return calendarJdbc.update(
        "UPDATE notifications SET paused = false, paused_reason = NULL "
            + "WHERE id IN ("
            + "  SELECT n.id FROM notifications n "
            + "  JOIN events e ON e.id = n.related_entity_id "
            + "  WHERE n.school_id = ? "
            + "    AND n.paused = true "
            + "    AND n.paused_reason LIKE ? "
            + "    AND n.kind IN ('EVENT_INVITE', 'EVENT_UPDATED', 'EVENT_CANCELLED') "
            + "    AND (e.start_dt AT TIME ZONE ?)::date < ? "
            + "    AND e.deleted_at IS NULL"
            + ")",
        schoolId,
        HOLIDAY_REASON_PREFIX + "%",
        zone.getId(),
        java.sql.Date.valueOf(todaySchoolLocal));
  }

  /**
   * Tasks: {@code due_date} is already a date (no timezone conversion needed — it's stored as a
   * school-local date by convention from V3). Same "&lt;" comparison.
   */
  private int unpauseTaskNotifications(UUID schoolId, LocalDate todaySchoolLocal) {
    return calendarJdbc.update(
        "UPDATE notifications SET paused = false, paused_reason = NULL "
            + "WHERE id IN ("
            + "  SELECT n.id FROM notifications n "
            + "  JOIN tasks t ON t.id = n.related_entity_id "
            + "  WHERE n.school_id = ? "
            + "    AND n.paused = true "
            + "    AND n.paused_reason LIKE ? "
            + "    AND n.kind IN ('TASK_ASSIGNED', 'TASK_UPDATED', 'TASK_DELETED', "
            + "                  'TASK_STATUS_CHANGED', 'TASK_OVERDUE') "
            + "    AND t.due_date < ? "
            + "    AND t.deleted_at IS NULL"
            + ")",
        schoolId,
        HOLIDAY_REASON_PREFIX + "%",
        java.sql.Date.valueOf(todaySchoolLocal));
  }
}
