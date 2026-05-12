package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.auth.UserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification read endpoints. The write surface (creating notification rows) is internal to the
 * existing event/task/holiday services via {@link NotificationService}; this controller exposes the
 * user-facing inbox.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  /** Response header name carrying the actor's unread count. Matches the FE bell's expected key. */
  static final String UNREAD_COUNT_HEADER = "X-Unread-Count";

  private final NotificationReadService readService;
  private final NotificationMarkService markService;

  public NotificationController(
      NotificationReadService readService, NotificationMarkService markService) {
    this.readService = readService;
    this.markService = markService;
  }

  /**
   * {@code GET /api/v1/notifications/me} (Part 11.2). Returns the actor's notifications
   * newest-first plus the unread count as a response header. The actor is implicit (JWT-derived
   * principal); there is no path id — every authenticated user sees only their own inbox.
   */
  @GetMapping("/me")
  public ResponseEntity<List<NotificationView>> listMyNotifications(
      @AuthenticationPrincipal UserPrincipal actor) {
    NotificationReadService.InboxView inbox = readService.loadFor(actor);
    HttpHeaders headers = new HttpHeaders();
    headers.set(UNREAD_COUNT_HEADER, Long.toString(inbox.unreadCount()));
    return ResponseEntity.ok().headers(headers).body(inbox.notifications());
  }

  /**
   * {@code POST /api/v1/notifications/{id}/read} (Part 11.3). Idempotent upsert; 404 if the actor
   * isn't a recipient of the notification.
   */
  @PostMapping("/{id}/read")
  public ResponseEntity<Void> markRead(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID id) {
    markService.markRead(id, actor);
    return ResponseEntity.noContent().build();
  }

  /**
   * {@code POST /api/v1/notifications/read-all} (Part 11.3). Marks every visible UNREAD
   * notification as read for the actor. Idempotent if called twice in the same session.
   */
  @PostMapping("/read-all")
  public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal UserPrincipal actor) {
    markService.markAllRead(actor);
    return ResponseEntity.noContent().build();
  }
}
