package com.childcarewow.calendar.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationReadRepository
    extends JpaRepository<NotificationRead, NotificationReadId> {

  /** Batched read-by lookup for multiple notifications in one query. */
  List<NotificationRead> findByNotificationIdIn(Collection<UUID> notificationIds);
}
