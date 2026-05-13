package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.notification.NotificationDispatchOrchestrator.RetryResult;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scans {@code notification_deliveries} every 30 minutes for FAILED rows still eligible to retry
 * and feeds them back through {@link NotificationDispatchOrchestrator#retryDelivery}. Closes the
 * retry-orchestration carry-forward tracked since Part 11.7.
 *
 * <p><b>Implicit backoff via the tick interval.</b> The query gates candidates on {@code created_at
 * < now() - BACKOFF_GUARD}, where {@code BACKOFF_GUARD} is the SAME 30-minute interval the cron
 * uses. This means a row that failed 5 minutes ago won't be picked up on the next tick; it has to
 * be at least 30 minutes old. Effective shape: attempt 1 fails → wait 30 min → retry 2 → wait 30
 * min → retry 3 → stop. Total wall-clock retry window: ~60 minutes.
 *
 * <p><b>Eligibility filter:</b> the query also enforces "no LATER attempt exists for the same
 * (notification, recipient, channel) tuple." Without this, a row that already succeeded on attempt
 * 2 would still be picked up on the next scan because the original attempt-1 FAILED row is still in
 * the audit log. The NOT EXISTS clause keeps the retry surface scoped to "latest attempt is
 * FAILED."
 *
 * <p><b>ShedLock</b> binds the {@code DELIVERY_RETRY} lock so multi-instance ECS doesn't
 * double-retry. {@code lockAtMostFor=PT15M} comfortably covers a typical retry batch (hundreds of
 * rows × a few seconds each).
 *
 * <p><b>Per-row failure isolation.</b> A single delivery's retry exception (orchestrator throws,
 * platform DB unreachable, etc.) is caught + logged; the job continues with the next row. The audit
 * log is the durable record — if retry can't happen this cycle, the next tick will find the same
 * FAILED row and try again.
 */
@Component
public class NotificationDeliveryRetryJob {

  private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryRetryJob.class);

  /** Minimum gap between an attempt and its retry. Matches the cron tick interval. */
  static final Duration BACKOFF_GUARD = Duration.ofMinutes(30);

  private final NotificationDeliveryService deliveryService;
  private final NotificationDispatchOrchestrator orchestrator;

  public NotificationDeliveryRetryJob(
      NotificationDeliveryService deliveryService, NotificationDispatchOrchestrator orchestrator) {
    this.deliveryService = deliveryService;
    this.orchestrator = orchestrator;
  }

  /**
   * 30-minute cron. {@code 0,30 * * * *} runs at minute 0 and 30 of every hour, every day.
   * Multi-instance coordination via ShedLock.
   */
  @Scheduled(cron = "0 0,30 * * * *", zone = "UTC")
  @SchedulerLock(name = "DELIVERY_RETRY", lockAtMostFor = "PT15M")
  public void retry() {
    retryUsingClock(Clock.systemUTC());
  }

  /**
   * Visible for tests. Iterates eligible FAILED rows + retries each via the orchestrator. Returns
   * the count of rows processed (for assertion + log).
   */
  public int retryUsingClock(Clock clock) {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(BACKOFF_GUARD);
    List<NotificationDelivery> candidates = deliveryService.findFailedRetriable(cutoff);
    if (candidates.isEmpty()) {
      return 0;
    }

    int processed = 0;
    for (NotificationDelivery d : candidates) {
      try {
        RetryResult result = orchestrator.retryDelivery(d.getId());
        log.info(
            "Retry delivery {}: prior attempt={} → result={}",
            d.getId(),
            d.getAttemptCount(),
            result);
        processed++;
      } catch (RuntimeException ex) {
        // Per-row isolation: keep iterating. The next tick will find this row again (no later
        // attempt exists, so the eligibility filter still picks it up).
        log.warn("Retry failed for delivery {}: {}", d.getId(), ex.getMessage(), ex);
      }
    }
    log.info("Retry tick processed {} delivery rows", processed);
    return processed;
  }
}
