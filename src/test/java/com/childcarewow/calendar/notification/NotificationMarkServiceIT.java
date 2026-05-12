package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-DB IT for Part 11.3 — {@code POST /api/v1/notifications/{id}/read} + {@code /read-all}. Pins
 * the idempotent-upsert behavior, the visibility gate (cross-user reads return 404), and the
 * bulk-read shape.
 */
@SpringBootTest
class NotificationMarkServiceIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");

  @Autowired NotificationMarkService markService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-nms-%')");
    calendarJdbc.update(
        "DELETE FROM notification_reads WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-nms-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-nms-%'");
  }

  @Test
  void markReadInsertsRowAndIsIdempotent() {
    UUID notifId = seedNotification("IT-nms-mark", MAYA);

    markService.markRead(notifId, maya());
    markService.markRead(notifId, maya()); // idempotent — second call is a no-op upsert.

    Integer rowCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_reads WHERE notification_id = ? AND user_id = ?",
            Integer.class,
            notifId,
            MAYA);
    assertThat(rowCount).isEqualTo(1);
  }

  @Test
  void markReadByNonRecipientReturns404() {
    UUID notifId = seedNotification("IT-nms-not-mine", TOM);

    // Maya isn't a recipient on this notification — must look indistinguishable from "doesn't
    // exist" to avoid leaking the row's existence.
    assertThatThrownBy(() -> markService.markRead(notifId, maya()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void markReadOnUnknownNotificationReturns404() {
    assertThatThrownBy(
            () ->
                markService.markRead(
                    UUID.fromString("99999999-0000-0000-0000-000000000099"), maya()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void readAllMarksAllVisibleUnreadAndReturnsCount() {
    UUID a = seedNotification("IT-nms-bulk-a", MAYA);
    UUID b = seedNotification("IT-nms-bulk-b", MAYA);
    UUID c = seedNotification("IT-nms-bulk-c", MAYA);
    UUID notMine = seedNotification("IT-nms-bulk-not-mine", TOM);

    int newlyRead = markService.markAllRead(maya());

    assertThat(newlyRead).isEqualTo(3);
    Integer myReads =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_reads WHERE user_id = ? "
                + "AND notification_id IN (?, ?, ?)",
            Integer.class,
            MAYA,
            a,
            b,
            c);
    assertThat(myReads).isEqualTo(3);
    // Tom's notification is untouched.
    Integer toms =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_reads WHERE notification_id = ?",
            Integer.class,
            notMine);
    assertThat(toms).isZero();
  }

  @Test
  void readAllIsIdempotentSecondCallReturnsZero() {
    seedNotification("IT-nms-idem-1", MAYA);
    seedNotification("IT-nms-idem-2", MAYA);

    int first = markService.markAllRead(maya());
    int second = markService.markAllRead(maya());

    assertThat(first).isEqualTo(2);
    assertThat(second).isZero();
  }

  @Test
  void readAllWithEmptyInboxReturnsZero() {
    int newlyRead = markService.markAllRead(maya());
    assertThat(newlyRead).isZero();
  }

  // -- helpers ---------------------------------------------------------------

  private UUID seedNotification(String relatedTitle, UUID recipient) {
    UUID id = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO notifications (id, org_id, school_id, kind, message, related_entity_title, paused) "
            + "VALUES (?, ?, ?, 'EVENT_INVITE', ?, ?, false)",
        id,
        ORG,
        SUNRISE,
        relatedTitle + " msg",
        relatedTitle);
    calendarJdbc.update(
        "INSERT INTO notification_recipients (notification_id, user_id) VALUES (?, ?)",
        id,
        recipient);
    return id;
  }

  private static UserPrincipal maya() {
    return new UserPrincipal(
        MAYA,
        "Maya",
        "maya@ccw.test",
        Role.STAFF,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES),
        Set.of(),
        "Lead Teacher");
  }
}
