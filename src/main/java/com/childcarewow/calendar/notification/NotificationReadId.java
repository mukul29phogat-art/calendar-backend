package com.childcarewow.calendar.notification;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class NotificationReadId implements Serializable {

  private static final long serialVersionUID = 1L;

  private UUID notificationId;
  private UUID userId;

  public NotificationReadId() {}

  public NotificationReadId(UUID notificationId, UUID userId) {
    this.notificationId = notificationId;
    this.userId = userId;
  }

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

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof NotificationReadId other)) return false;
    return Objects.equals(notificationId, other.notificationId)
        && Objects.equals(userId, other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(notificationId, userId);
  }
}
