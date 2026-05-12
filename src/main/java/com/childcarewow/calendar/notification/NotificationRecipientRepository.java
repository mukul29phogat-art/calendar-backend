package com.childcarewow.calendar.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRecipientRepository
    extends JpaRepository<NotificationRecipient, NotificationRecipientId> {

  /** All recipient rows the user is on — i.e. all notifications the user can see. */
  List<NotificationRecipient> findByUserId(UUID userId);

  /** Batched recipient lookup for multiple notifications in one query. */
  List<NotificationRecipient> findByNotificationIdIn(Collection<UUID> notificationIds);
}
