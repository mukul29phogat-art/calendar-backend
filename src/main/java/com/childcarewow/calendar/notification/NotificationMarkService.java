package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write service for per-user notification reads (Part 11.3). Backs {@code POST
 * /api/v1/notifications/{id}/read} and {@code POST /api/v1/notifications/read-all}.
 *
 * <p><b>Visibility gate.</b> Marking a notification read is only permitted if the actor is one of
 * the notification's recipients. Cross-user reads return 404 (not 403) so we don't leak the
 * existence of notifications outside the actor's visibility scope — matches the soft-delete-as-404
 * convention used elsewhere.
 *
 * <p><b>Idempotent upsert.</b> {@code INSERT ... ON CONFLICT (notification_id, user_id) DO NOTHING}
 * keeps the original {@code read_at} timestamp on the second call. The FE bell pings {@code /read}
 * multiple times in the same session (e.g. on every modal open); the DB stays stable.
 */
@Service
public class NotificationMarkService {

  private final NotificationRecipientRepository recipientRepo;
  private final JdbcTemplate calendarJdbc;

  public NotificationMarkService(
      NotificationRecipientRepository recipientRepo,
      @Qualifier("calendarJdbcTemplate") JdbcTemplate calendarJdbc) {
    this.recipientRepo = recipientRepo;
    this.calendarJdbc = calendarJdbc;
  }

  /**
   * Mark a single notification read for the actor. 404 if the actor isn't a recipient (or the
   * notification doesn't exist — either case is indistinguishable to the caller, by design).
   */
  @Transactional
  public void markRead(UUID notificationId, UserPrincipal actor) {
    if (!recipientRepo.existsByNotificationIdAndUserId(notificationId, actor.id())) {
      throw new NotFoundException("Notification", notificationId);
    }
    upsert(notificationId, actor.id());
  }

  /**
   * Bulk-mark every visible UNREAD notification as read. The shape is: load actor's notification
   * ids → bulk-upsert (the {@code ON CONFLICT} branch keeps already-read rows unchanged, so an
   * actor's "read-all" called twice the same minute is idempotent). Returns the count of rows that
   * newly transitioned to read.
   */
  @Transactional
  public int markAllRead(UserPrincipal actor) {
    List<UUID> visibleIds =
        recipientRepo.findByUserId(actor.id()).stream()
            .map(NotificationRecipient::getNotificationId)
            .distinct()
            .toList();
    if (visibleIds.isEmpty()) {
      return 0;
    }
    int newlyRead = 0;
    for (UUID id : visibleIds) {
      newlyRead += upsert(id, actor.id());
    }
    return newlyRead;
  }

  /**
   * Insert (or no-op) a single read row. Returns 1 if a new row landed; 0 if the row already
   * existed. {@code read_at} is DB-defaulted to {@code now()} on the original insert and preserved
   * on conflict.
   */
  private int upsert(UUID notificationId, UUID userId) {
    return calendarJdbc.update(
        "INSERT INTO notification_reads (notification_id, user_id) VALUES (?, ?) "
            + "ON CONFLICT (notification_id, user_id) DO NOTHING",
        notificationId,
        userId);
  }
}
