package com.childcarewow.calendar.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.childcarewow.calendar.crosscut.IdempotencyKeyRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class IdempotencyPurgeJobTest {

  private final IdempotencyKeyRepository repo = mock(IdempotencyKeyRepository.class);
  private final IdempotencyPurgeJob job = new IdempotencyPurgeJob(repo);

  @Test
  void purgeUsingClockDelegatesToRepoWithCutoff() {
    OffsetDateTime cutoff = OffsetDateTime.parse("2026-05-08T04:00:00Z");
    when(repo.deleteExpired(any())).thenReturn(7);
    int deleted = job.purgeUsingClock(cutoff);
    assertThat(deleted).isEqualTo(7);
    verify(repo).deleteExpired(eq(cutoff));
  }

  @Test
  void purgeNoOpWhenNothingExpired() {
    when(repo.deleteExpired(any())).thenReturn(0);
    int deleted = job.purgeUsingClock(OffsetDateTime.now());
    assertThat(deleted).isZero();
  }

  @Test
  void scheduledPurgeUsesCurrentClock() {
    when(repo.deleteExpired(any())).thenReturn(0);
    job.purge();
    verify(repo).deleteExpired(any());
  }
}
