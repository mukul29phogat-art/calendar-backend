package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.auth.UserPrincipal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service backing {@code GET /api/v1/notifications/me} (Part 11.2). Loads the actor's visible
 * notifications, batches the recipient + read-by lookups, and exposes a list view + unread count.
 *
 * <p><b>Visibility.</b> A notification is visible to user U iff U is in {@code
 * notification_recipients} for that notification. The service starts from the per-user recipient
 * rows and never reads notifications the user wasn't a recipient of — the user can't see "all
 * notifications for my school," only "all notifications addressed to me." This matches the FE
 * prototype's bell shape: each user has their own private inbox.
 *
 * <p><b>Batching.</b> Three queries total — one for recipient ids (filtered by actor), one for
 * notifications by id, one for ALL recipient + read-by rows in the batch via {@code
 * findByNotificationIdIn}. Avoids N+1 across notifications.
 */
@Service
public class NotificationReadService {

  private final NotificationRepository notificationRepo;
  private final NotificationRecipientRepository recipientRepo;
  private final NotificationReadRepository readRepo;

  public NotificationReadService(
      NotificationRepository notificationRepo,
      NotificationRecipientRepository recipientRepo,
      NotificationReadRepository readRepo) {
    this.notificationRepo = notificationRepo;
    this.recipientRepo = recipientRepo;
    this.readRepo = readRepo;
  }

  /**
   * Returns the actor's notifications newest-first as {@link NotificationView}s. Caller can read
   * {@link InboxView#unreadCount()} to populate the {@code X-Unread-Count} response header.
   */
  @Transactional(readOnly = true)
  public InboxView loadFor(UserPrincipal actor) {
    if (actor == null || actor.id() == null) {
      return new InboxView(List.of(), 0L);
    }

    // 1. Per-user recipient rows → ids of notifications the actor can see.
    List<UUID> visibleIds =
        recipientRepo.findByUserId(actor.id()).stream()
            .map(NotificationRecipient::getNotificationId)
            .distinct()
            .toList();
    if (visibleIds.isEmpty()) {
      return new InboxView(List.of(), 0L);
    }

    // 2. Batched notification load, newest first.
    List<Notification> notifications = notificationRepo.findByIdInOrderByCreatedAtDesc(visibleIds);

    // 3. Batched recipients + read-by lookups for the visible set.
    Map<UUID, List<UUID>> recipientsByNotif = groupRecipients(visibleIds);
    Map<UUID, List<UUID>> readByByNotif = groupReadBy(visibleIds);

    List<NotificationView> views = new ArrayList<>(notifications.size());
    long unread = 0;
    for (Notification n : notifications) {
      List<UUID> recipients = recipientsByNotif.getOrDefault(n.getId(), List.of());
      List<UUID> readBy = readByByNotif.getOrDefault(n.getId(), List.of());
      views.add(NotificationView.fromEntity(n, recipients, readBy));
      if (!readBy.contains(actor.id())) {
        unread++;
      }
    }
    return new InboxView(views, unread);
  }

  private Map<UUID, List<UUID>> groupRecipients(Collection<UUID> notificationIds) {
    return recipientRepo.findByNotificationIdIn(notificationIds).stream()
        .collect(
            Collectors.groupingBy(
                NotificationRecipient::getNotificationId,
                Collectors.mapping(NotificationRecipient::getUserId, Collectors.toList())));
  }

  private Map<UUID, List<UUID>> groupReadBy(Collection<UUID> notificationIds) {
    Map<UUID, List<UUID>> out = new HashMap<>();
    Set<NotificationReadId> seen = new HashSet<>();
    for (NotificationRead r : readRepo.findByNotificationIdIn(notificationIds)) {
      // De-duplicate in case a stray double-row sneaks in (the table has a composite PK so the DB
      // already enforces uniqueness; this is belt-and-braces for the in-memory shape).
      NotificationReadId key = new NotificationReadId(r.getNotificationId(), r.getUserId());
      if (!seen.add(key)) {
        continue;
      }
      out.computeIfAbsent(r.getNotificationId(), id -> new ArrayList<>()).add(r.getUserId());
    }
    return out;
  }

  /** Read-side container — list of views + total unread count for the actor. */
  public record InboxView(List<NotificationView> notifications, long unreadCount) {}
}
