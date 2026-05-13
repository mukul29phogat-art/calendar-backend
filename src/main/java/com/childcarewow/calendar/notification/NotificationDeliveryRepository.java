package com.childcarewow.calendar.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

  /** Audit log for a single notification — used by tests + dashboards. Ordered newest-first. */
  List<NotificationDelivery> findByNotificationIdOrderByCreatedAtDesc(UUID notificationId);

  /** Health-check / dashboard count for failure surfacing. */
  long countByStatus(DeliveryStatus status);

  /**
   * Returns delivery rows eligible for dispatch retry. Two trigger shapes:
   *
   * <ul>
   *   <li><b>FAILED retry:</b> {@code status='FAILED' AND attempt_count < maxAttempts}.
   *   <li><b>Holiday-resume reconnection:</b> {@code status='PAUSED'} AND {@code last_error} starts
   *       with {@code 'Holiday: '} AND the underlying notification's {@code paused=false} (i.e. the
   *       holiday-suppression resume job has already cleared the pause). Closes the gap that {@code
   *       HolidaySuppressionResumeJob} leaves open — it unpauses notifications but doesn't itself
   *       fire dispatch.
   * </ul>
   *
   * <p>Both triggers share the rest of the eligibility filter: (a) created before {@code cutoff}
   * (implicit backoff — the retry job's tick interval is the minimum gap between attempts), (b)
   * {@code attempt_count < maxAttempts}, (c) no LATER attempt exists for the same {@code
   * (notification_id, recipient_user_id, channel)} tuple. The NOT EXISTS clause prevents
   * re-attempting tuples whose newer audit row already shows SENT / a different state / higher
   * attempt count.
   *
   * <p><b>PAUSED-by-allowlist rows are NOT eligible.</b> The {@code last_error LIKE 'Holiday: %'}
   * filter excludes {@code BLOCKED_BY_ALLOWLIST} + {@code EMAIL_DISABLED} reasons — those reflect
   * dev-environment state, not transient conditions to retry past.
   */
  @Query(
      value =
          "SELECT d.* FROM notification_deliveries d "
              + "LEFT JOIN notifications n ON n.id = d.notification_id "
              + "WHERE ("
              + "  d.status = 'FAILED' "
              + "  OR (d.status = 'PAUSED' AND d.last_error LIKE 'Holiday: %' "
              + "      AND n.paused = false)"
              + ") "
              + "AND d.attempt_count < :maxAttempts "
              + "AND d.created_at < :cutoff "
              + "AND NOT EXISTS ("
              + "  SELECT 1 FROM notification_deliveries d2 "
              + "  WHERE d2.notification_id = d.notification_id "
              + "    AND d2.recipient_user_id = d.recipient_user_id "
              + "    AND d2.channel = d.channel "
              + "    AND d2.attempt_count > d.attempt_count"
              + ") "
              + "ORDER BY d.created_at ASC",
      nativeQuery = true)
  List<NotificationDelivery> findFailedRetriable(
      @Param("maxAttempts") int maxAttempts, @Param("cutoff") OffsetDateTime cutoff);
}
