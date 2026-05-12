package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-DB IT for Part 11.8 — verifies the holiday-suppression resume job. Pins:
 *
 * <ul>
 *   <li>Past-date event notifications unpause when the school-local day has rolled past.
 *   <li>Future-date events stay paused (resume is past-only).
 *   <li>Non-holiday pause reasons (e.g. manual operator pause) are NOT touched.
 *   <li>Already-unpaused rows are unaffected (idempotent re-runs).
 * </ul>
 *
 * <p>Uses {@link Clock#fixed} to inject a deterministic "now" so the test doesn't drift with
 * wall-clock time.
 */
@SpringBootTest
class HolidaySuppressionResumeJobIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");

  @Autowired HolidaySuppressionResumeJob job;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notifications WHERE related_entity_title LIKE 'IT-hsrj-%' OR "
            + "related_entity_id IN (SELECT id FROM events WHERE title LIKE 'IT-hsrj-%') OR "
            + "related_entity_id IN (SELECT id FROM tasks WHERE title LIKE 'IT-hsrj-%')");
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-hsrj-%'");
    calendarJdbc.update("DELETE FROM tasks WHERE title LIKE 'IT-hsrj-%'");
  }

  @Test
  void unpausesPastEventNotificationsAfterHoliday() {
    // Event was on July 4, 2027 (a federal holiday). The notification was paused at create-time
    // with reason "Holiday: Independence Day." Run the job on July 6 → notification unpauses.
    UUID eventId = seedEvent("IT-hsrj-past-event", LocalDate.of(2027, 7, 4));
    UUID notifId =
        seedNotification(
            "IT-hsrj-past-event-notif",
            NotificationKind.EVENT_INVITE,
            eventId,
            true,
            "Holiday: Independence Day");

    int unpaused = job.resumeUsingClock(fixedClockOn(LocalDate.of(2027, 7, 6)));

    assertThat(unpaused).isGreaterThanOrEqualTo(1);
    assertThat(isPaused(notifId)).isFalse();
    assertThat(pausedReason(notifId)).isNull();
  }

  @Test
  void leavesFutureEventNotificationsPaused() {
    // Event is on July 4, 2027. Run the job on July 1 → event is still in the future, notification
    // stays paused.
    UUID eventId = seedEvent("IT-hsrj-future-event", LocalDate.of(2027, 7, 4));
    UUID notifId =
        seedNotification(
            "IT-hsrj-future-event-notif",
            NotificationKind.EVENT_INVITE,
            eventId,
            true,
            "Holiday: Independence Day");

    job.resumeUsingClock(fixedClockOn(LocalDate.of(2027, 7, 1)));

    assertThat(isPaused(notifId)).isTrue();
    assertThat(pausedReason(notifId)).startsWith("Holiday:");
  }

  @Test
  void leavesNonHolidayPausesAlone() {
    // A notification paused for "Manual: operator action" must NOT be touched even if its date is
    // past — the resume job only fires on holiday-prefixed reasons.
    UUID eventId = seedEvent("IT-hsrj-manual-pause", LocalDate.of(2027, 5, 1));
    UUID notifId =
        seedNotification(
            "IT-hsrj-manual-pause-notif",
            NotificationKind.EVENT_INVITE,
            eventId,
            true,
            "Manual: operator action");

    job.resumeUsingClock(fixedClockOn(LocalDate.of(2027, 7, 6)));

    assertThat(isPaused(notifId)).isTrue();
    assertThat(pausedReason(notifId)).isEqualTo("Manual: operator action");
  }

  @Test
  void idempotentSecondRunDoesNotTouchAlreadyUnpausedRows() {
    UUID eventId = seedEvent("IT-hsrj-idem", LocalDate.of(2027, 7, 4));
    UUID notifId =
        seedNotification(
            "IT-hsrj-idem-notif",
            NotificationKind.EVENT_INVITE,
            eventId,
            true,
            "Holiday: Independence Day");

    int firstRun = job.resumeUsingClock(fixedClockOn(LocalDate.of(2027, 7, 6)));
    int secondRun = job.resumeUsingClock(fixedClockOn(LocalDate.of(2027, 7, 6)));

    assertThat(firstRun).isGreaterThanOrEqualTo(1);
    assertThat(secondRun).isZero();
    assertThat(isPaused(notifId)).isFalse();
  }

  @Test
  void unpausesPastTaskNotifications() {
    UUID taskId = seedTask("IT-hsrj-past-task", LocalDate.of(2027, 7, 4));
    UUID notifId =
        seedNotification(
            "IT-hsrj-past-task-notif",
            NotificationKind.TASK_ASSIGNED,
            taskId,
            true,
            "Holiday: Independence Day");

    job.resumeUsingClock(fixedClockOn(LocalDate.of(2027, 7, 6)));

    assertThat(isPaused(notifId)).isFalse();
  }

  @Test
  void leavesFutureTaskNotificationsPaused() {
    UUID taskId = seedTask("IT-hsrj-future-task", LocalDate.of(2027, 7, 4));
    UUID notifId =
        seedNotification(
            "IT-hsrj-future-task-notif",
            NotificationKind.TASK_ASSIGNED,
            taskId,
            true,
            "Holiday: Independence Day");

    job.resumeUsingClock(fixedClockOn(LocalDate.of(2027, 7, 1)));

    assertThat(isPaused(notifId)).isTrue();
  }

  // -- helpers ---------------------------------------------------------------

  private static Clock fixedClockOn(LocalDate date) {
    // Pick mid-day UTC so the school-local conversion lands on the same calendar day in any
    // North-American zone (SUNRISE is America/New_York in the seed).
    Instant instant = OffsetDateTime.of(date, java.time.LocalTime.NOON, ZoneOffset.UTC).toInstant();
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  private UUID seedEvent(String title, LocalDate date) {
    UUID id = UUID.randomUUID();
    OffsetDateTime startDt = OffsetDateTime.of(date, java.time.LocalTime.of(10, 0), ZoneOffset.UTC);
    OffsetDateTime endDt = OffsetDateTime.of(date, java.time.LocalTime.of(11, 0), ZoneOffset.UTC);
    calendarJdbc.update(
        "INSERT INTO events (id, org_id, school_id, classroom_id, title, type, start_dt, end_dt, "
            + "created_by_user_id) VALUES (?, ?, ?, ?, ?, 'CLASSROOM', ?, ?, ?)",
        id,
        ORG,
        SUNRISE,
        BUTTERFLIES,
        title,
        startDt,
        endDt,
        OLIVIA);
    return id;
  }

  private UUID seedTask(String title, LocalDate dueDate) {
    UUID id = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO tasks (id, org_id, school_id, title, assignee_user_id, due_date, status, "
            + "priority, created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, 'TODO', 'MEDIUM', ?)",
        id,
        ORG,
        SUNRISE,
        title,
        MAYA,
        java.sql.Date.valueOf(dueDate),
        OLIVIA);
    return id;
  }

  private UUID seedNotification(
      String title, NotificationKind kind, UUID relatedEntityId, boolean paused, String reason) {
    UUID id = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO notifications (id, org_id, school_id, kind, message, related_entity_id, "
            + "related_entity_title, paused, paused_reason) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        ORG,
        SUNRISE,
        kind.name(),
        title + " msg",
        relatedEntityId,
        title,
        paused,
        reason);
    return id;
  }

  private boolean isPaused(UUID notifId) {
    return Boolean.TRUE.equals(
        calendarJdbc.queryForObject(
            "SELECT paused FROM notifications WHERE id = ?", Boolean.class, notifId));
  }

  private String pausedReason(UUID notifId) {
    return calendarJdbc.queryForObject(
        "SELECT paused_reason FROM notifications WHERE id = ?", String.class, notifId);
  }
}
