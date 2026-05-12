package com.childcarewow.calendar.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

  /** Audit log for a single notification — used by tests + dashboards. Ordered newest-first. */
  List<NotificationDelivery> findByNotificationIdOrderByCreatedAtDesc(UUID notificationId);

  /** Health-check / dashboard count for failure surfacing. */
  long countByStatus(DeliveryStatus status);
}
