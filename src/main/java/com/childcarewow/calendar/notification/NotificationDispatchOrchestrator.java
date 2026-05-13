package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.notification.EmailDispatcher.DispatchResult;
import com.childcarewow.calendar.platform.PlatformUserEmailResolver;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composition layer over the four notification-related services that landed in Parts 11.2 / 11.4 /
 * 11.5 / 11.7. Given a notification id, fans dispatch out to every recipient on every channel the
 * notification's kind warrants, and records the per-(notification, recipient, channel) audit row.
 *
 * <p>The pieces this stitches:
 *
 * <ul>
 *   <li>{@link NotificationRepository} + {@link NotificationRecipientRepository} — loads the
 *       notification + recipients.
 *   <li>{@link PlatformUserEmailResolver} — recipient {@code user_id} → email address from {@code
 *       platform.users}.
 *   <li>{@link EmailRenderer} — per-{@link NotificationKind} subject + HTML body.
 *   <li>{@link EmailDispatcher} — actual SMTP send with the architecture-spec-§7.4 dev allowlist.
 *   <li>{@link NotificationDeliveryService} — records the result in {@code
 *       notification_deliveries}.
 * </ul>
 *
 * <p><b>Channel routing per kind.</b> All kinds get an APP-channel delivery row (in-app surfaces
 * via {@code GET /api/v1/notifications/me} — the row is "delivered" as soon as it exists).
 * Email-worthy kinds additionally get an EMAIL channel attempt:
 *
 * <ul>
 *   <li>{@link NotificationKind#EVENT_INVITE}
 *   <li>{@link NotificationKind#EVENT_UPDATED}
 *   <li>{@link NotificationKind#EVENT_CANCELLED}
 *   <li>{@link NotificationKind#TASK_OVERDUE}
 * </ul>
 *
 * Task kinds other than OVERDUE stay in-app only — they're frequent enough that piping every status
 * flip to email would be noise. Future Part 12.x can lift this list to a per-user preference table.
 *
 * <p><b>Pause handling.</b> When {@code notification.paused = true} (the holiday-suppression
 * pre-creation gate from {@code NotificationService.checkPauseReason}), every channel records a
 * PAUSED delivery with the notification's {@code paused_reason}. No email is sent. The Part 11.8
 * {@link HolidaySuppressionResumeJob} flips {@code paused=false} after the holiday; the operator
 * (or a future scheduler) re-invokes {@link #dispatch} for the unpaused notification.
 *
 * <p><b>Wire-up is intentionally NOT done here.</b> This Part adds the composition primitive; the
 * decision of WHEN to call it (synchronously after {@code NotificationService} writes? via
 * {@code @TransactionalEventListener}? from a periodic scanner of un-dispatched notifications?) is
 * an operator concern that lives in a follow-up sub-Part. Production code currently doesn't invoke
 * the orchestrator — the methods are wired and tested but dormant until the operator chooses a
 * trigger.
 */
@Service
public class NotificationDispatchOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatchOrchestrator.class);

  /** Kinds that warrant a real EMAIL channel send on top of the in-app APP row. */
  static final Set<NotificationKind> EMAIL_KINDS =
      Set.of(
          NotificationKind.EVENT_INVITE,
          NotificationKind.EVENT_UPDATED,
          NotificationKind.EVENT_CANCELLED,
          NotificationKind.TASK_OVERDUE);

  private final NotificationRepository notificationRepo;
  private final NotificationRecipientRepository recipientRepo;
  private final EmailRenderer emailRenderer;
  private final EmailDispatcher emailDispatcher;
  private final NotificationDeliveryService deliveryService;
  private final PlatformUserEmailResolver emailResolver;

  public NotificationDispatchOrchestrator(
      NotificationRepository notificationRepo,
      NotificationRecipientRepository recipientRepo,
      EmailRenderer emailRenderer,
      EmailDispatcher emailDispatcher,
      NotificationDeliveryService deliveryService,
      PlatformUserEmailResolver emailResolver) {
    this.notificationRepo = notificationRepo;
    this.recipientRepo = recipientRepo;
    this.emailRenderer = emailRenderer;
    this.emailDispatcher = emailDispatcher;
    this.deliveryService = deliveryService;
    this.emailResolver = emailResolver;
  }

  /**
   * Fan dispatch to every recipient of {@code notificationId}, on every channel the notification's
   * kind warrants. Records an audit row per (notification, recipient, channel) regardless of
   * outcome. Returns a {@link DispatchSummary} with per-channel counts — useful for ITs +
   * dashboards.
   *
   * <p>Each delivery is attempt #1 (the audit-layer {@code shouldRetry} semantics from Part 11.7
   * apply to the caller's retry loop, not this single dispatch).
   *
   * <p><b>{@code REQUIRES_NEW}.</b> The orchestrator is invoked from {@code
   * NotificationDispatchListener} on the AFTER_COMMIT phase of an event-listener thread. Spring's
   * OSIV pattern keeps an EntityManager bound to that thread without an active transaction; a plain
   * {@code @Transactional} would inherit that bound session and the next {@code saveAndFlush} would
   * throw {@code TransactionRequiredException}. Forcing {@code REQUIRES_NEW} detaches the
   * listener-thread session and runs the audit-row writes in a fresh tx.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DispatchSummary dispatch(UUID notificationId) {
    Notification n =
        notificationRepo
            .findById(notificationId)
            .orElseThrow(() -> new NotFoundException("Notification", notificationId));

    List<UUID> recipients =
        recipientRepo.findByNotificationIdIn(List.of(notificationId)).stream()
            .map(NotificationRecipient::getUserId)
            .distinct()
            .toList();

    DispatchSummary summary = new DispatchSummary();
    if (recipients.isEmpty()) {
      log.info("Notification {} has no recipients; nothing to dispatch.", notificationId);
      return summary;
    }

    boolean paused = n.isPaused();
    String pausedReason = n.getPausedReason();
    boolean isEmailKind = EMAIL_KINDS.contains(n.getKind());
    EmailContent content = isEmailKind ? emailRenderer.render(n) : null;

    for (UUID recipient : recipients) {
      dispatchAppChannel(notificationId, recipient, paused, pausedReason, summary);
      if (isEmailKind) {
        dispatchEmailChannel(notificationId, recipient, paused, pausedReason, content, summary);
      }
    }
    return summary;
  }

  /**
   * APP channel: in-app row is "delivered" the moment {@code notifications} + {@code
   * notification_recipients} exist (the {@code GET /me} endpoint surfaces it). The deliveries-row
   * is an audit signal — SENT for un-paused, PAUSED carrying the reason for paused.
   */
  private void dispatchAppChannel(
      UUID notificationId,
      UUID recipient,
      boolean paused,
      String pausedReason,
      DispatchSummary summary) {
    if (paused) {
      deliveryService.recordPaused(notificationId, recipient, DeliveryChannel.APP, 1, pausedReason);
      summary.appPaused++;
    } else {
      deliveryService.recordSent(notificationId, recipient, DeliveryChannel.APP, 1);
      summary.appSent++;
    }
  }

  /**
   * EMAIL channel: resolve email → render → dispatch → record. Each outcome maps to one audit row.
   */
  private void dispatchEmailChannel(
      UUID notificationId,
      UUID recipient,
      boolean paused,
      String pausedReason,
      EmailContent content,
      DispatchSummary summary) {
    if (paused) {
      deliveryService.recordPaused(
          notificationId, recipient, DeliveryChannel.EMAIL, 1, pausedReason);
      summary.emailPaused++;
      return;
    }
    Optional<String> emailOpt = emailResolver.resolveEmail(recipient);
    if (emailOpt.isEmpty()) {
      deliveryService.recordFailed(
          notificationId,
          recipient,
          DeliveryChannel.EMAIL,
          1,
          "Recipient email not found in platform.users");
      summary.emailFailed++;
      return;
    }
    DispatchResult result =
        emailDispatcher.send(emailOpt.get(), content.subject(), content.htmlBody());
    switch (result) {
      case SENT -> {
        deliveryService.recordSent(notificationId, recipient, DeliveryChannel.EMAIL, 1);
        summary.emailSent++;
      }
      case BLOCKED_BY_ALLOWLIST -> {
        deliveryService.recordPaused(
            notificationId, recipient, DeliveryChannel.EMAIL, 1, "BLOCKED_BY_ALLOWLIST");
        summary.emailBlocked++;
      }
      case DISABLED -> {
        deliveryService.recordPaused(
            notificationId, recipient, DeliveryChannel.EMAIL, 1, "EMAIL_DISABLED");
        summary.emailDisabled++;
      }
      case FAILED -> {
        deliveryService.recordFailed(
            notificationId, recipient, DeliveryChannel.EMAIL, 1, "Dispatcher returned FAILED");
        summary.emailFailed++;
      }
    }
  }

  /**
   * Retry a single delivery whose current state is FAILED or PAUSED-by-holiday-since-cleared.
   * Increments the audit row's {@code attempt_count} by 1, re-runs the channel-specific send path,
   * and records a new audit row with the outcome.
   *
   * <p><b>Scope: EMAIL only.</b> APP-channel rows can't be FAILED (the audit row is just a stamp
   * for "row exists in notifications + recipients"), so the retry scheduler never feeds APP
   * deliveries here. We defensively short-circuit if a non-EMAIL row arrives.
   *
   * <p><b>Two retry triggers:</b>
   *
   * <ul>
   *   <li>FAILED row + {@code attempt_count < MAX} + backoff cutoff passed → re-attempt SMTP.
   *   <li>PAUSED row with {@code last_error LIKE 'Holiday: %'} + underlying notification's {@code
   *       paused = false} (holiday-resume job has fired) → re-attempt SMTP. Closes the gap that
   *       {@link HolidaySuppressionResumeJob} leaves open: it flips {@code paused=false} but
   *       doesn't itself dispatch.
   * </ul>
   *
   * <p>If the notification has since been re-paused (e.g. another holiday was approved between the
   * resume and the retry), the retry records a PAUSED row instead of attempting. Same shape as the
   * first-attempt pause path.
   *
   * @return the {@link RetryResult} indicating what happened
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RetryResult retryDelivery(UUID deliveryId) {
    NotificationDelivery existing = deliveryService.findById(deliveryId).orElse(null);
    if (existing == null) {
      log.warn("Retry requested for unknown delivery id={}", deliveryId);
      return RetryResult.NOT_FOUND;
    }
    if (existing.getStatus() != DeliveryStatus.FAILED
        && existing.getStatus() != DeliveryStatus.PAUSED) {
      return RetryResult.SKIPPED_NOT_RETRIABLE;
    }
    int newAttempt = existing.getAttemptCount() + 1;
    if (newAttempt > NotificationDeliveryService.MAX_ATTEMPTS) {
      return RetryResult.SKIPPED_MAX_ATTEMPTS;
    }
    if (existing.getChannel() != DeliveryChannel.EMAIL) {
      // APP-channel rows are audit-only — they never FAIL. Skip defensively.
      return RetryResult.SKIPPED_NOT_EMAIL;
    }

    Notification n = notificationRepo.findById(existing.getNotificationId()).orElse(null);
    if (n == null) {
      log.warn(
          "Retry skipped — notification {} no longer exists (delivery={})",
          existing.getNotificationId(),
          deliveryId);
      return RetryResult.NOT_FOUND;
    }

    // Notification was paused after the original attempt — record PAUSED, no send.
    if (n.isPaused()) {
      deliveryService.recordPaused(
          n.getId(),
          existing.getRecipientUserId(),
          DeliveryChannel.EMAIL,
          newAttempt,
          n.getPausedReason());
      return RetryResult.PAUSED;
    }

    Optional<String> emailOpt = emailResolver.resolveEmail(existing.getRecipientUserId());
    if (emailOpt.isEmpty()) {
      deliveryService.recordFailed(
          n.getId(),
          existing.getRecipientUserId(),
          DeliveryChannel.EMAIL,
          newAttempt,
          "Recipient email not found in platform.users");
      return RetryResult.FAILED;
    }

    EmailContent content = emailRenderer.render(n);
    DispatchResult result =
        emailDispatcher.send(emailOpt.get(), content.subject(), content.htmlBody());
    return switch (result) {
      case SENT -> {
        deliveryService.recordSent(
            n.getId(), existing.getRecipientUserId(), DeliveryChannel.EMAIL, newAttempt);
        yield RetryResult.SENT;
      }
      case BLOCKED_BY_ALLOWLIST -> {
        deliveryService.recordPaused(
            n.getId(),
            existing.getRecipientUserId(),
            DeliveryChannel.EMAIL,
            newAttempt,
            "BLOCKED_BY_ALLOWLIST");
        yield RetryResult.BLOCKED;
      }
      case DISABLED -> {
        deliveryService.recordPaused(
            n.getId(),
            existing.getRecipientUserId(),
            DeliveryChannel.EMAIL,
            newAttempt,
            "EMAIL_DISABLED");
        yield RetryResult.DISABLED;
      }
      case FAILED -> {
        deliveryService.recordFailed(
            n.getId(),
            existing.getRecipientUserId(),
            DeliveryChannel.EMAIL,
            newAttempt,
            "Dispatcher returned FAILED");
        yield RetryResult.FAILED;
      }
    };
  }

  /** Outcome of a single {@link #retryDelivery} call. */
  public enum RetryResult {
    SENT,
    FAILED,
    PAUSED,
    BLOCKED,
    DISABLED,
    NOT_FOUND,
    /** Renamed from {@code SKIPPED_NOT_FAILED} now that PAUSED-by-holiday is also retriable. */
    SKIPPED_NOT_RETRIABLE,
    SKIPPED_MAX_ATTEMPTS,
    SKIPPED_NOT_EMAIL
  }

  /** Per-channel counts. Mutable counters kept package-visible so tests can read them. */
  public static final class DispatchSummary {
    int appSent;
    int appPaused;
    int emailSent;
    int emailPaused;
    int emailBlocked;
    int emailDisabled;
    int emailFailed;

    public int appSent() {
      return appSent;
    }

    public int appPaused() {
      return appPaused;
    }

    public int emailSent() {
      return emailSent;
    }

    public int emailPaused() {
      return emailPaused;
    }

    public int emailBlocked() {
      return emailBlocked;
    }

    public int emailDisabled() {
      return emailDisabled;
    }

    public int emailFailed() {
      return emailFailed;
    }

    public int total() {
      return appSent
          + appPaused
          + emailSent
          + emailPaused
          + emailBlocked
          + emailDisabled
          + emailFailed;
    }
  }
}
