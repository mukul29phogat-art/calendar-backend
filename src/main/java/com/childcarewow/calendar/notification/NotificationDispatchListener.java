package com.childcarewow.calendar.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges {@link NotificationCreatedEvent} (published by {@link NotificationService} after each
 * notification + recipients write) to {@link NotificationDispatchOrchestrator#dispatch}, the real
 * outbound dispatch primitive.
 *
 * <p><b>Phase = AFTER_COMMIT.</b> The listener runs only once the user-facing transaction (creating
 * an event / task / etc.) commits successfully. If the transaction rolls back — say a validation
 * failure mid-create — the listener does NOT fire, so an aborted operation doesn't leak email out.
 *
 * <p><b>Failure isolation.</b> The listener catches every exception, logs, and returns. SMTP /
 * platform-DB / orchestrator failures don't propagate to the original caller (the commit-listener
 * runs in its own thread context outside the tx anyway). Per the orchestrator's contract, FAILED
 * deliveries are already recorded as audit rows — the listener doesn't need to surface them.
 *
 * <p><b>Why this is a separate class.</b> Keeping the listener distinct from {@code
 * NotificationService} avoids a circular bean dependency (the orchestrator's email pipeline doesn't
 * need to know about the notification writer). It also makes the wiring testable in isolation — the
 * IT can verify that publishing the event triggers dispatch without depending on the full
 * NotificationService write surface.
 */
@Component
public class NotificationDispatchListener {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatchListener.class);

  private final NotificationDispatchOrchestrator orchestrator;

  public NotificationDispatchListener(NotificationDispatchOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onNotificationCreated(NotificationCreatedEvent event) {
    try {
      NotificationDispatchOrchestrator.DispatchSummary summary =
          orchestrator.dispatch(event.notificationId());
      if (summary.total() == 0) {
        log.debug(
            "Notification {} had no recipients; orchestrator returned zero deliveries.",
            event.notificationId());
      } else {
        log.info(
            "Dispatched notification {}: app sent={}/paused={}, email sent={}/paused={}/blocked={}/disabled={}/failed={}",
            event.notificationId(),
            summary.appSent(),
            summary.appPaused(),
            summary.emailSent(),
            summary.emailPaused(),
            summary.emailBlocked(),
            summary.emailDisabled(),
            summary.emailFailed());
      }
    } catch (RuntimeException ex) {
      // The user's tx already committed — letting this exception propagate would surface a 5xx
      // for an action the user already saw succeed. The orchestrator stamps FAILED audit rows for
      // recoverable failures; this catch-all handles the unexpected (e.g. orchestrator throws
      // before recording, or the notification row was deleted between commit and dispatch).
      log.warn(
          "Dispatch listener failed for notification {}: {}",
          event.notificationId(),
          ex.getMessage(),
          ex);
    }
  }
}
