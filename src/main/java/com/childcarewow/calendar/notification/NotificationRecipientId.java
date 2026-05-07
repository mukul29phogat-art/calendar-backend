package com.childcarewow.calendar.notification;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link NotificationRecipient}. */
public class NotificationRecipientId implements Serializable {

  private static final long serialVersionUID = 1L;

  private UUID notificationId;
  private UUID userId;

  public NotificationRecipientId() {}

  public NotificationRecipientId(UUID notificationId, UUID userId) {
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
    if (!(o instanceof NotificationRecipientId other)) return false;
    return Objects.equals(notificationId, other.notificationId)
        && Objects.equals(userId, other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(notificationId, userId);
  }
}
