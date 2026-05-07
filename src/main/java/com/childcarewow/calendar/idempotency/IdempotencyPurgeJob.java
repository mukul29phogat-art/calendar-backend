package com.childcarewow.calendar.idempotency;

import com.childcarewow.calendar.crosscut.IdempotencyKeyRepository;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily purge of expired idempotency rows. Runs at 04:00 UTC.
 *
 * <p><b>ShedLock deferred.</b> The architecture spec calls for ShedLock-backed coordination so only
 * one ECS instance purges per day. We're single-instance during dev (LocalStack); ShedLock lands in
 * Series 11.4 alongside the actual ECS deployment, when there's a real second instance to
 * coordinate with. Until then, the cron runs once on whichever instance is up.
 */
@Component
public class IdempotencyPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

  private final IdempotencyKeyRepository repo;

  public IdempotencyPurgeJob(IdempotencyKeyRepository repo) {
    this.repo = repo;
  }

  @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
  public void purge() {
    purgeUsingClock(OffsetDateTime.now());
  }

  /** Visible for tests — pass an arbitrary cutoff instant. */
  public int purgeUsingClock(OffsetDateTime cutoff) {
    int deleted = repo.deleteExpired(cutoff);
    if (deleted > 0) {
      log.info("Purged {} expired idempotency rows (cutoff={})", deleted, cutoff);
    }
    return deleted;
  }
}
