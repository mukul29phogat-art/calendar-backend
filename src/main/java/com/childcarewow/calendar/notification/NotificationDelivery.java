package com.childcarewow.calendar.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "notification_id", nullable = false)
  private UUID notificationId;

  @Column(name = "recipient_user_id", nullable = false)
  private UUID recipientUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeliveryChannel channel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeliveryStatus status = DeliveryStatus.QUEUED;

  @Column(name = "scheduled_at", nullable = false)
  private OffsetDateTime scheduledAt;

  @Column(name = "sent_at")
  private OffsetDateTime sentAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount = 0;

  @Column(name = "last_error")
  private String lastError;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private OffsetDateTime updatedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getNotificationId() {
    return notificationId;
  }

  public void setNotificationId(UUID notificationId) {
    this.notificationId = notificationId;
  }

  public UUID getRecipientUserId() {
    return recipientUserId;
  }

  public void setRecipientUserId(UUID recipientUserId) {
    this.recipientUserId = recipientUserId;
  }

  public DeliveryChannel getChannel() {
    return channel;
  }

  public void setChannel(DeliveryChannel channel) {
    this.channel = channel;
  }

  public DeliveryStatus getStatus() {
    return status;
  }

  public void setStatus(DeliveryStatus status) {
    this.status = status;
  }

  public OffsetDateTime getScheduledAt() {
    return scheduledAt;
  }

  public void setScheduledAt(OffsetDateTime scheduledAt) {
    this.scheduledAt = scheduledAt;
  }

  public OffsetDateTime getSentAt() {
    return sentAt;
  }

  public void setSentAt(OffsetDateTime sentAt) {
    this.sentAt = sentAt;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(int attemptCount) {
    this.attemptCount = attemptCount;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
