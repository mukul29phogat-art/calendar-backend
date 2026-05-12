package com.childcarewow.calendar.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** Batched fetch — loads multiple notifications, newest first, in a single query. */
  List<Notification> findByIdInOrderByCreatedAtDesc(Collection<UUID> ids);
}
