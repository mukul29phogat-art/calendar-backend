package com.childcarewow.calendar.idempotency;

import com.childcarewow.calendar.crosscut.IdempotencyKeyRepository;
import java.time.OffsetDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily purge of expired idempotency rows. Runs at 04:00 UTC.
 *
 * <p>Coordinated across multi-instance ECS via ShedLock (wired in Part 6.7). The {@code
 * IDEMPOTENCY_PURGE} lock binds to the calendar-DB {@code shedlock} table; a single tick fires on
 * one instance only. {@code lockAtMostFor=PT5M} is several orders of magnitude above the typical
 * sub-second purge duration — bounded so a crashed instance can't permanently hold the lock.
 */
@Component
public class IdempotencyPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

  private final IdempotencyKeyRepository repo;

  public IdempotencyPurgeJob(IdempotencyKeyRepository repo) {
    this.repo = repo;
  }

  @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
  @SchedulerLock(name = "IDEMPOTENCY_PURGE", lockAtMostFor = "PT5M")
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
