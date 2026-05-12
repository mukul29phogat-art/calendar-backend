package com.childcarewow.calendar.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit log writer for {@code notification_deliveries} (Part 11.7). Each call records ONE dispatch
 * attempt — caller has already attempted the send (via {@link EmailDispatcher} or its push
 * equivalent) and now hands the result to this service for persistence.
 *
 * <p><b>Audit-first design, not queue-first.</b> The {@code notification_deliveries} schema has a
 * {@code QUEUED} status + {@code scheduled_at} field that could power a poll-based queue model, but
 * Part 11.7 only needs the per-attempt audit log. The QUEUED path is reserved for a future sub-Part
 * (or a `notification_deliveries`-fed scheduler that wraps 11.4/11.6 dispatchers).
 *
 * <p><b>Retry counting.</b> Each {@code recordX} call takes an explicit {@code attempt} number.
 * Upstream callers track the per-(notification, recipient, channel) attempt count and pass it here;
 * the service doesn't auto-increment because a row's {@code attempt_count} reflects the specific
 * attempt being recorded, not a cumulative count across rows.
 */
@Service
public class NotificationDeliveryService {

  /** Max attempts before giving up. Playbook spec: "After 3 attempts, status=FAILED and stop." */
  public static final int MAX_ATTEMPTS = 3;

  private final NotificationDeliveryRepository repo;

  public NotificationDeliveryService(NotificationDeliveryRepository repo) {
    this.repo = repo;
  }

  /**
   * Record a successful delivery. {@code sent_at} is stamped at insert time so the audit row
   * reflects the wall-clock send moment.
   */
  @Transactional
  public NotificationDelivery recordSent(
      UUID notificationId, UUID recipientUserId, DeliveryChannel channel, int attempt) {
    NotificationDelivery row =
        baseRow(notificationId, recipientUserId, channel, attempt, DeliveryStatus.SENT);
    row.setSentAt(OffsetDateTime.now());
    return repo.saveAndFlush(row);
  }

  /**
   * Record a failed delivery attempt. The provided {@code error} fills {@code last_error}. If
   * {@code attempt &gt;= MAX_ATTEMPTS}, callers should treat this as terminal and stop retrying
   * (audit log shows {@code attempt_count=3, status=FAILED} for the final row).
   */
  @Transactional
  public NotificationDelivery recordFailed(
      UUID notificationId,
      UUID recipientUserId,
      DeliveryChannel channel,
      int attempt,
      String error) {
    NotificationDelivery row =
        baseRow(notificationId, recipientUserId, channel, attempt, DeliveryStatus.FAILED);
    row.setLastError(truncate(error));
    return repo.saveAndFlush(row);
  }

  /**
   * Record a dispatcher's decision to NOT send (allowlist block, holiday pause, etc.). Uses {@code
   * status=PAUSED} per the schema enum. {@code reason} fills {@code last_error} so an operator
   * inspecting the row sees the cause inline.
   */
  @Transactional
  public NotificationDelivery recordPaused(
      UUID notificationId,
      UUID recipientUserId,
      DeliveryChannel channel,
      int attempt,
      String reason) {
    NotificationDelivery row =
        baseRow(notificationId, recipientUserId, channel, attempt, DeliveryStatus.PAUSED);
    row.setLastError(truncate(reason));
    return repo.saveAndFlush(row);
  }

  /** Audit query — every delivery row for a notification, newest first. */
  @Transactional(readOnly = true)
  public List<NotificationDelivery> findByNotificationId(UUID notificationId) {
    return repo.findByNotificationIdOrderByCreatedAtDesc(notificationId);
  }

  /** Dashboard query — total rows currently in a given status. */
  @Transactional(readOnly = true)
  public long countByStatus(DeliveryStatus status) {
    return repo.countByStatus(status);
  }

  /**
   * Convenience: should the caller retry given the attempt count? Returns false at or beyond {@link
   * #MAX_ATTEMPTS}.
   */
  public static boolean shouldRetry(int attempt) {
    return attempt < MAX_ATTEMPTS;
  }

  // -- helpers ---------------------------------------------------------------

  private static NotificationDelivery baseRow(
      UUID notificationId,
      UUID recipientUserId,
      DeliveryChannel channel,
      int attempt,
      DeliveryStatus status) {
    NotificationDelivery row = new NotificationDelivery();
    row.setNotificationId(notificationId);
    row.setRecipientUserId(recipientUserId);
    row.setChannel(channel);
    row.setStatus(status);
    row.setScheduledAt(OffsetDateTime.now());
    row.setAttemptCount(attempt);
    return row;
  }

  /** {@code last_error} is unbounded TEXT but limiting to 2KB protects against runaway logs. */
  private static String truncate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() > 2048 ? s.substring(0, 2048) : s;
  }
}
