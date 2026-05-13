package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

/**
 * Real-DB IT for the retry job: seeds FAILED delivery rows + invokes the retry tick + asserts the
 * orchestrator produces NEW audit rows with incremented attempt_count. Uses {@link
 * Clock#fixed(java.time.Instant, java.time.ZoneId)} to control cutoff math, and {@link MockBean}
 * over {@link JavaMailSender} so retry attempts don't hit real SMTP.
 */
@SpringBootTest(
    properties = {
      "management.health.mail.enabled=false",
      "notifications.email.dev-allowlist=@ccw-demo.test,@ccw.test,@childcarewow.com"
    })
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NotificationDeliveryRetryJobIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");

  @Autowired NotificationDeliveryRetryJob job;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @MockBean JavaMailSender mailSender;

  @BeforeEach
  void setUp() {
    cleanup();
    Mockito.when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
  }

  @AfterEach
  void cleanupAfter() {
    cleanup();
  }

  private void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_deliveries WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-ndr-%')");
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-ndr-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-ndr-%'");
  }

  @Test
  void retryProducesNewSentRowWhenSmtpRecovers() {
    // Seed a notification + one FAILED delivery row backdated 1 hour. The retry job picks it up,
    // the mocked JavaMailSender succeeds → orchestrator records a new SENT row with attempt=2.
    UUID notifId = seedNotification("IT-ndr-recover", NotificationKind.EVENT_INVITE);
    seedRecipient(notifId, MAYA);
    seedDelivery(notifId, MAYA, DeliveryStatus.FAILED, 1, "SMTP server down", minutesAgo(60));

    int processed = job.retryUsingClock(nowClock());

    assertThat(processed).isEqualTo(1);
    List<String> statuses = deliveryStatuses(notifId);
    // Original FAILED (attempt=1) + new SENT (attempt=2).
    assertThat(statuses).containsExactlyInAnyOrder("FAILED", "SENT");
    Integer maxAttempt =
        calendarJdbc.queryForObject(
            "SELECT MAX(attempt_count) FROM notification_deliveries WHERE notification_id = ?",
            Integer.class,
            notifId);
    assertThat(maxAttempt).isEqualTo(2);
  }

  @Test
  void recentFailureIsNotPickedUpYetByBackoffGate() {
    // FAILED row created 5 minutes ago — BACKOFF_GUARD is 30 min, so it's NOT eligible yet.
    UUID notifId = seedNotification("IT-ndr-too-recent", NotificationKind.EVENT_INVITE);
    seedRecipient(notifId, MAYA);
    seedDelivery(notifId, MAYA, DeliveryStatus.FAILED, 1, "transient", minutesAgo(5));

    int processed = job.retryUsingClock(nowClock());

    assertThat(processed).isZero();
    // No second delivery row should have appeared.
    Integer total =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_deliveries WHERE notification_id = ?",
            Integer.class,
            notifId);
    assertThat(total).isEqualTo(1);
  }

  @Test
  void maxedOutAttemptsAreNotRetried() {
    // attempt_count=3 (MAX) — no more retries.
    UUID notifId = seedNotification("IT-ndr-maxed", NotificationKind.EVENT_INVITE);
    seedRecipient(notifId, MAYA);
    seedDelivery(notifId, MAYA, DeliveryStatus.FAILED, 3, "permanent", minutesAgo(60));

    int processed = job.retryUsingClock(nowClock());

    assertThat(processed).isZero();
  }

  @Test
  void deliveryWithLaterSuccessIsNotRetriedAgain() {
    // attempt=1 FAILED + attempt=2 SENT exist for the same tuple. The retry job must NOT pick up
    // the FAILED row because a newer attempt already succeeded.
    UUID notifId = seedNotification("IT-ndr-already-sent", NotificationKind.EVENT_INVITE);
    seedRecipient(notifId, MAYA);
    seedDelivery(notifId, MAYA, DeliveryStatus.FAILED, 1, "transient", minutesAgo(120));
    seedDelivery(notifId, MAYA, DeliveryStatus.SENT, 2, null, minutesAgo(90));

    int processed = job.retryUsingClock(nowClock());

    assertThat(processed).isZero();
  }

  @Test
  void appChannelFailuresAreNotRetriedDefensively() {
    // APP-channel rows can't actually be FAILED in production (the orchestrator's APP path always
    // writes SENT or PAUSED), but if one ever showed up, retryDelivery short-circuits with
    // SKIPPED_NOT_EMAIL — no new audit row.
    UUID notifId = seedNotification("IT-ndr-app-failure", NotificationKind.EVENT_INVITE);
    seedRecipient(notifId, MAYA);
    seedDeliveryWithChannel(
        notifId,
        MAYA,
        DeliveryStatus.FAILED,
        1,
        "synthesized",
        DeliveryChannel.APP,
        minutesAgo(60));

    int processed = job.retryUsingClock(nowClock());

    // The job did pick up the row (it matches the FAILED + attempt<3 + cutoff filter), but the
    // orchestrator short-circuited with SKIPPED_NOT_EMAIL — no new row written.
    assertThat(processed).isEqualTo(1);
    Integer total =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_deliveries WHERE notification_id = ?",
            Integer.class,
            notifId);
    assertThat(total).isEqualTo(1);
  }

  @Test
  void pausedNotificationDuringRetryRecordsPausedRow() {
    // FAILED delivery exists; then the notification is paused (e.g. a holiday was approved
    // post-failure). The retry attempt records a PAUSED row, not a SENT/FAILED one.
    UUID notifId = seedNotification("IT-ndr-paused-after", NotificationKind.EVENT_INVITE);
    seedRecipient(notifId, MAYA);
    seedDelivery(notifId, MAYA, DeliveryStatus.FAILED, 1, "transient", minutesAgo(60));
    // Pause the notification.
    calendarJdbc.update(
        "UPDATE notifications SET paused = true, paused_reason = 'Holiday: Mid-cycle hold' "
            + "WHERE id = ?",
        notifId);

    int processed = job.retryUsingClock(nowClock());

    assertThat(processed).isEqualTo(1);
    List<String> statuses = deliveryStatuses(notifId);
    assertThat(statuses).containsExactlyInAnyOrder("FAILED", "PAUSED");
  }

  // -- helpers ---------------------------------------------------------------

  private static Clock nowClock() {
    // Use system time so the cutoff math is consistent with the seeded `minutesAgo` rows.
    return Clock.fixed(Instant.now(), ZoneOffset.UTC);
  }

  private static java.time.OffsetDateTime minutesAgo(int minutes) {
    return java.time.OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
  }

  private UUID seedNotification(String relatedTitle, NotificationKind kind) {
    UUID id = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO notifications (id, org_id, school_id, kind, message, related_entity_title, paused) "
            + "VALUES (?, ?, ?, ?, ?, ?, false)",
        id,
        ORG,
        SUNRISE,
        kind.name(),
        relatedTitle + " msg",
        relatedTitle);
    return id;
  }

  private void seedRecipient(UUID notifId, UUID userId) {
    calendarJdbc.update(
        "INSERT INTO notification_recipients (notification_id, user_id) VALUES (?, ?)",
        notifId,
        userId);
  }

  private void seedDelivery(
      UUID notifId,
      UUID userId,
      DeliveryStatus status,
      int attempt,
      String lastError,
      java.time.OffsetDateTime createdAt) {
    seedDeliveryWithChannel(
        notifId, userId, status, attempt, lastError, DeliveryChannel.EMAIL, createdAt);
  }

  private void seedDeliveryWithChannel(
      UUID notifId,
      UUID userId,
      DeliveryStatus status,
      int attempt,
      String lastError,
      DeliveryChannel channel,
      java.time.OffsetDateTime createdAt) {
    UUID id = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO notification_deliveries (id, notification_id, recipient_user_id, channel, "
            + "status, scheduled_at, attempt_count, last_error, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        notifId,
        userId,
        channel.name(),
        status.name(),
        createdAt,
        attempt,
        lastError,
        createdAt,
        createdAt);
  }

  private List<String> deliveryStatuses(UUID notifId) {
    return calendarJdbc.queryForList(
        "SELECT status FROM notification_deliveries WHERE notification_id = ?",
        String.class,
        notifId);
  }
}
