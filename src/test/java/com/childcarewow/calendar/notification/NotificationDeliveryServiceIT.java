package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-DB IT for Part 11.7 — {@link NotificationDeliveryService} audit-row writer. Verifies each
 * status path persists the right fields and that the per-notification + per-status queries return
 * the expected sets.
 */
@SpringBootTest
class NotificationDeliveryServiceIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");

  @Autowired NotificationDeliveryService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_deliveries WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-nds-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-nds-%'");
  }

  @Test
  void recordSentInsertsRowWithSentAtPopulated() {
    UUID notifId = seedNotification("IT-nds-sent");

    NotificationDelivery row = service.recordSent(notifId, MAYA, DeliveryChannel.EMAIL, 1);

    assertThat(row.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(row.getSentAt()).isNotNull();
    assertThat(row.getAttemptCount()).isEqualTo(1);
    assertThat(row.getLastError()).isNull();
  }

  @Test
  void recordFailedPersistsLastError() {
    UUID notifId = seedNotification("IT-nds-failed");

    NotificationDelivery row =
        service.recordFailed(notifId, MAYA, DeliveryChannel.EMAIL, 2, "SMTP timeout after 30s");

    assertThat(row.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(row.getSentAt()).isNull();
    assertThat(row.getLastError()).isEqualTo("SMTP timeout after 30s");
    assertThat(row.getAttemptCount()).isEqualTo(2);
  }

  @Test
  void recordPausedPersistsReasonInLastError() {
    UUID notifId = seedNotification("IT-nds-paused");

    NotificationDelivery row =
        service.recordPaused(
            notifId, MAYA, DeliveryChannel.EMAIL, 1, "BLOCKED_BY_ALLOWLIST: not in dev list");

    assertThat(row.getStatus()).isEqualTo(DeliveryStatus.PAUSED);
    assertThat(row.getLastError()).contains("BLOCKED_BY_ALLOWLIST");
  }

  @Test
  void findByNotificationIdReturnsAllRowsNewestFirst() {
    UUID notifId = seedNotification("IT-nds-history");

    service.recordFailed(notifId, MAYA, DeliveryChannel.EMAIL, 1, "transient");
    service.recordFailed(notifId, MAYA, DeliveryChannel.EMAIL, 2, "transient again");
    service.recordSent(notifId, MAYA, DeliveryChannel.EMAIL, 3);

    List<NotificationDelivery> rows = service.findByNotificationId(notifId);

    // Three audit rows; newest-first ordering (last write = first row).
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(rows.get(0).getAttemptCount()).isEqualTo(3);
    // The two FAILEDs are ordered DESC by created_at — attempt 2 newer than attempt 1.
    assertThat(rows.get(1).getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(rows.get(2).getStatus()).isEqualTo(DeliveryStatus.FAILED);
  }

  @Test
  void countByStatusReflectsPersistedRows() {
    UUID notifA = seedNotification("IT-nds-count-a");
    UUID notifB = seedNotification("IT-nds-count-b");

    long failedBefore = service.countByStatus(DeliveryStatus.FAILED);

    service.recordFailed(notifA, MAYA, DeliveryChannel.EMAIL, 1, "x");
    service.recordFailed(notifB, TOM, DeliveryChannel.EMAIL, 1, "x");

    long failedAfter = service.countByStatus(DeliveryStatus.FAILED);
    assertThat(failedAfter - failedBefore).isEqualTo(2);
  }

  @Test
  void shouldRetryReturnsTrueBelowMaxFalseAtAndAbove() {
    assertThat(NotificationDeliveryService.shouldRetry(1)).isTrue();
    assertThat(NotificationDeliveryService.shouldRetry(2)).isTrue();
    assertThat(NotificationDeliveryService.shouldRetry(3)).isFalse();
    assertThat(NotificationDeliveryService.shouldRetry(4)).isFalse();
  }

  @Test
  void longErrorMessagesAreTruncatedNotPropagatedRaw() {
    UUID notifId = seedNotification("IT-nds-long-error");
    String huge = "x".repeat(5000);

    NotificationDelivery row = service.recordFailed(notifId, MAYA, DeliveryChannel.EMAIL, 1, huge);

    assertThat(row.getLastError()).hasSize(2048);
  }

  // -- helpers ---------------------------------------------------------------

  private UUID seedNotification(String relatedTitle) {
    UUID id = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO notifications (id, org_id, school_id, kind, message, related_entity_title, paused) "
            + "VALUES (?, ?, ?, 'EVENT_INVITE', ?, ?, false)",
        id,
        ORG,
        SUNRISE,
        relatedTitle + " msg",
        relatedTitle);
    return id;
  }
}
