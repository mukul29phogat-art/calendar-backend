package com.childcarewow.calendar.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "notification_recipients")
@IdClass(NotificationRecipientId.class)
public class NotificationRecipient {

  @Id
  @Column(name = "notification_id", nullable = false)
  private UUID notificationId;

  @Id
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  public UUID getNotificationId() {
    return notificationId;
  }

  public void setNotificationId(UUID notificationId) {
    this.notificationId = notificationId;
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }
}
