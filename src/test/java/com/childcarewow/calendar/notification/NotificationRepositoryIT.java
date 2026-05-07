package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class NotificationRepositoryIT {

  @Autowired NotificationRepository notifications;
  @Autowired NotificationRecipientRepository recipients;
  @Autowired NotificationReadRepository reads;
  @Autowired NotificationDeliveryRepository deliveries;
  @PersistenceContext EntityManager em;

  @Test
  void roundTripsNotification() {
    UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID schoolId = UUID.fromString("22222222-2222-2222-2222-222222222221");
    UUID relatedEntityId = UUID.randomUUID();
    UUID recipientId = UUID.fromString("33333333-0000-0000-0000-000000000004");

    Notification n = new Notification();
    n.setOrgId(orgId);
    n.setSchoolId(schoolId);
    n.setKind(NotificationKind.EVENT_INVITE);
    n.setMessage("You're invited to Story Time");
    n.setRelatedEntityId(relatedEntityId);
    n.setRelatedEntityTitle("Story Time");
    n.setPaused(true);
    n.setPausedReason("Holiday: Memorial Day");
    n.setPayload("{\"channel\":\"app\",\"icon\":\"book\"}");

    UUID id = notifications.saveAndFlush(n).getId();

    NotificationRecipient r = new NotificationRecipient();
    r.setNotificationId(id);
    r.setUserId(recipientId);
    recipients.saveAndFlush(r);

    NotificationDelivery d = new NotificationDelivery();
    d.setNotificationId(id);
    d.setRecipientUserId(recipientId);
    d.setChannel(DeliveryChannel.PUSH);
    d.setStatus(DeliveryStatus.SENT);
    d.setScheduledAt(OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC));
    d.setSentAt(OffsetDateTime.of(2026, 6, 1, 9, 0, 12, 0, ZoneOffset.UTC));
    d.setAttemptCount(1);
    d.setLastError(null);
    UUID deliveryId = deliveries.saveAndFlush(d).getId();

    em.clear();

    Notification read = notifications.findById(id).orElseThrow();
    assertThat(read.getId()).isEqualTo(id);
    assertThat(read.getOrgId()).isEqualTo(orgId);
    assertThat(read.getSchoolId()).isEqualTo(schoolId);
    assertThat(read.getKind()).isEqualTo(NotificationKind.EVENT_INVITE);
    assertThat(read.getMessage()).isEqualTo("You're invited to Story Time");
    assertThat(read.getRelatedEntityId()).isEqualTo(relatedEntityId);
    assertThat(read.getRelatedEntityTitle()).isEqualTo("Story Time");
    assertThat(read.isPaused()).isTrue();
    assertThat(read.getPausedReason()).isEqualTo("Holiday: Memorial Day");
    assertThat(read.getPayload()).contains("\"channel\"").contains("\"icon\"");
    assertThat(read.getCreatedAt()).isNotNull();

    NotificationRecipient readR =
        recipients.findById(new NotificationRecipientId(id, recipientId)).orElseThrow();
    assertThat(readR.getNotificationId()).isEqualTo(id);
    assertThat(readR.getUserId()).isEqualTo(recipientId);

    NotificationDelivery readD = deliveries.findById(deliveryId).orElseThrow();
    assertThat(readD.getNotificationId()).isEqualTo(id);
    assertThat(readD.getRecipientUserId()).isEqualTo(recipientId);
    assertThat(readD.getChannel()).isEqualTo(DeliveryChannel.PUSH);
    assertThat(readD.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(readD.getScheduledAt())
        .isEqualTo(OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC));
    assertThat(readD.getSentAt())
        .isEqualTo(OffsetDateTime.of(2026, 6, 1, 9, 0, 12, 0, ZoneOffset.UTC));
    assertThat(readD.getAttemptCount()).isEqualTo(1);
    assertThat(readD.getLastError()).isNull();
    assertThat(readD.getCreatedAt()).isNotNull();
    assertThat(readD.getUpdatedAt()).isNotNull();
  }

  @Test
  void cascadeDeletesRecipientsWhenNotificationDeleted() {
    UUID schoolId = UUID.randomUUID();

    Notification n = new Notification();
    n.setOrgId(UUID.randomUUID());
    n.setSchoolId(schoolId);
    n.setKind(NotificationKind.TASK_ASSIGNED);
    n.setMessage("New task: Sanitize toys");
    UUID notifId = notifications.saveAndFlush(n).getId();

    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    NotificationRecipient ra = new NotificationRecipient();
    ra.setNotificationId(notifId);
    ra.setUserId(userA);
    recipients.saveAndFlush(ra);
    NotificationRecipient rb = new NotificationRecipient();
    rb.setNotificationId(notifId);
    rb.setUserId(userB);
    recipients.saveAndFlush(rb);

    em.clear();
    assertThat(recipients.findById(new NotificationRecipientId(notifId, userA))).isPresent();

    notifications.deleteById(notifId);
    em.flush(); // send DELETE so the FK CASCADE fires
    em.clear();

    assertThat(notifications.findById(notifId)).isEmpty();
    assertThat(recipients.findById(new NotificationRecipientId(notifId, userA))).isEmpty();
    assertThat(recipients.findById(new NotificationRecipientId(notifId, userB))).isEmpty();
  }

  @Test
  void multipleReadsForOneNotificationPerUserViolatesPK() {
    Notification n = new Notification();
    n.setOrgId(UUID.randomUUID());
    n.setSchoolId(UUID.randomUUID());
    n.setKind(NotificationKind.TASK_OVERDUE);
    n.setMessage("Task is overdue");
    UUID notifId = notifications.saveAndFlush(n).getId();

    UUID userId = UUID.randomUUID();

    NotificationRead first = new NotificationRead();
    first.setNotificationId(notifId);
    first.setUserId(userId);
    reads.saveAndFlush(first);

    em.clear();

    // Use em.persist() directly: JpaRepository.save() with an @IdClass merges (upsert),
    // which would silently UPDATE the existing row instead of throwing. persist() forces
    // an INSERT, which collides with the existing (notification_id, user_id) PK.
    NotificationRead second = new NotificationRead();
    second.setNotificationId(notifId);
    second.setUserId(userId);

    assertThatThrownBy(
            () -> {
              em.persist(second);
              em.flush();
            })
        .isInstanceOf(jakarta.persistence.PersistenceException.class);
  }
}
