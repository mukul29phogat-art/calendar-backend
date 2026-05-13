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
   * Returns FAILED delivery rows eligible for retry. A row is eligible iff (a) its status is
   * FAILED, (b) its attempt_count is below {@code maxAttempts}, (c) it was created before {@code
   * cutoff} (implicit backoff — the retry job's tick interval is the minimum gap between attempts),
   * and (d) no LATER attempt for the same {@code (notification_id, recipient_user_id, channel)}
   * tuple exists. The NOT EXISTS clause prevents re-attempting tuples whose newer audit row already
   * shows SENT / PAUSED / a higher attempt count.
   */
  @Query(
      value =
          "SELECT * FROM notification_deliveries d "
              + "WHERE d.status = 'FAILED' "
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
