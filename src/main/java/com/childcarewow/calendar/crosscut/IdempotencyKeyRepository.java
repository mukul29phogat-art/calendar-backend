package com.childcarewow.calendar.crosscut;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

  /**
   * Hard-deletes idempotency rows whose {@code expires_at} is in the past. Returns affected row
   * count for logging from the daily purge job.
   */
  @Modifying
  @Transactional
  @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :cutoff")
  int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);
}
