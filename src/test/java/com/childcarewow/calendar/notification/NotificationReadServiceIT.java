package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-DB IT for Part 11.2 — the inbox read flow that backs {@code GET /api/v1/notifications/me}.
 * Verifies: per-recipient visibility, batched recipients + read-by lookups, unread-count
 * accounting, and newest-first ordering.
 */
@SpringBootTest
class NotificationReadServiceIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");

  @Autowired NotificationReadService readService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-nrs-%')");
    calendarJdbc.update(
        "DELETE FROM notification_reads WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-nrs-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-nrs-%'");
  }

  @Test
  void emptyInboxWhenNoNotificationsAddressedToUser() {
    NotificationReadService.InboxView inbox = readService.loadFor(maya());
    assertThat(inbox.notifications()).isEmpty();
    assertThat(inbox.unreadCount()).isZero();
  }

  @Test
  void mayaSeesOnlyHerOwnNotifications() {
    UUID hers = seedNotification("IT-nrs-for-maya", MAYA);
    UUID toms = seedNotification("IT-nrs-for-tom", TOM);

    NotificationReadService.InboxView inbox = readService.loadFor(maya());

    assertThat(inbox.notifications()).hasSize(1);
    assertThat(inbox.notifications().get(0).id()).isEqualTo(hers);
    // Sanity: Tom's notification exists in the DB but is invisible to Maya.
    assertThat(inbox.notifications()).noneMatch(v -> v.id().equals(toms));
  }

  @Test
  void unreadCountTracksReadRows() {
    UUID a = seedNotification("IT-nrs-unread-a", MAYA);
    UUID b = seedNotification("IT-nrs-read-b", MAYA);
    // Mark b as read by Maya.
    calendarJdbc.update(
        "INSERT INTO notification_reads (notification_id, user_id) VALUES (?, ?)", b, MAYA);

    NotificationReadService.InboxView inbox = readService.loadFor(maya());

    assertThat(inbox.notifications()).hasSize(2);
    assertThat(inbox.unreadCount()).isEqualTo(1);
    // The read one has Maya in its readBy list; the unread one does not.
    NotificationView readView =
        inbox.notifications().stream().filter(v -> v.id().equals(b)).findFirst().orElseThrow();
    NotificationView unreadView =
        inbox.notifications().stream().filter(v -> v.id().equals(a)).findFirst().orElseThrow();
    assertThat(readView.readBy()).contains(MAYA);
    assertThat(unreadView.readBy()).doesNotContain(MAYA);
  }

  @Test
  void newestFirstOrdering() {
    UUID older = seedNotification("IT-nrs-older", MAYA);
    // Sleep is too brittle; force the createdAt back by 1 minute via direct UPDATE so the order
    // is deterministic regardless of insert-batch latency.
    calendarJdbc.update(
        "UPDATE notifications SET created_at = now() - interval '1 minute' WHERE id = ?", older);
    UUID newer = seedNotification("IT-nrs-newer", MAYA);

    NotificationReadService.InboxView inbox = readService.loadFor(maya());
    assertThat(inbox.notifications().get(0).id()).isEqualTo(newer);
    assertThat(inbox.notifications().get(1).id()).isEqualTo(older);
  }

  @Test
  void groupNotificationSurfacesEveryRecipientInView() {
    // One notification addressed to BOTH Maya and Tom (a multi-recipient row — common for
    // school-wide event invites).
    UUID notifId = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO notifications (id, org_id, school_id, kind, message, related_entity_title, paused) "
            + "VALUES (?, ?, ?, 'EVENT_INVITE', 'IT-nrs-group msg', 'IT-nrs-group', false)",
        notifId,
        ORG,
        SUNRISE);
    calendarJdbc.update(
        "INSERT INTO notification_recipients (notification_id, user_id) VALUES (?, ?), (?, ?)",
        notifId,
        MAYA,
        notifId,
        TOM);

    NotificationReadService.InboxView inbox = readService.loadFor(maya());

    assertThat(inbox.notifications()).hasSize(1);
    assertThat(inbox.notifications().get(0).recipientUserIds())
        .containsExactlyInAnyOrder(MAYA, TOM);
  }

  @Test
  void nullActorReturnsEmpty() {
    NotificationReadService.InboxView inbox = readService.loadFor(null);
    assertThat(inbox.notifications()).isEmpty();
    assertThat(inbox.unreadCount()).isZero();
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
