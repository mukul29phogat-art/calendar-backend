package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.auth.UserPrincipal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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

  public NotificationController(NotificationReadService readService) {
    this.readService = readService;
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
}
