package com.childcarewow.calendar.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_reads")
@IdClass(NotificationReadId.class)
public class NotificationRead {

  @Id
  @Column(name = "notification_id", nullable = false)
  private UUID notificationId;

  @Id
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "read_at", insertable = false, updatable = false)
  private OffsetDateTime readAt;

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

  public OffsetDateTime getReadAt() {
    return readAt;
  }
}
