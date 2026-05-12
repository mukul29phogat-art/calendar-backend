package com.childcarewow.calendar.platform;

import com.childcarewow.calendar.exception.PlatformUnavailableException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves a calendar-owned {@code user_id} to the email address on {@code platform.users}. Used by
 * {@link com.childcarewow.calendar.notification.NotificationDispatchOrchestrator} (the dispatch
 * pipeline composition layer) to fan out emails to the resolved address per recipient.
 *
 * <p><b>Cache.</b> 5-minute TTL × 10K entries. Same posture as {@link PlatformEntityValidatorImpl}.
 * The cache stores {@link Optional} so we can negatively-cache "user does not exist" without
 * re-hitting the DB on every retry — important because the dispatcher iterates a recipient list
 * that may include stale ids.
 *
 * <p><b>Failure semantics.</b> Platform-DB outage surfaces as {@link PlatformUnavailableException}
 * (HTTP 503). Per D11, the calendar DB has no FK to {@code platform.users} so a stale recipient id
 * is plausible — the resolver returns {@code Optional.empty()} for "user not found", letting the
 * orchestrator record a FAILED delivery row rather than blowing up the whole dispatch.
 */
@Service
public class PlatformUserEmailResolver {

  private static final Duration TTL = Duration.ofMinutes(5);
  private static final long MAX_SIZE = 10_000L;

  private final JdbcTemplate platformJdbc;
  private final Cache<UUID, Optional<String>> emailCache =
      Caffeine.newBuilder().maximumSize(MAX_SIZE).expireAfterWrite(TTL).build();
  private final Counter hits;
  private final Counter misses;

  public PlatformUserEmailResolver(
      @Qualifier("platformJdbcTemplate") JdbcTemplate platformJdbc, MeterRegistry registry) {
    this.platformJdbc = platformJdbc;
    this.hits = registry.counter("platform_email_resolver_cache_hits");
    this.misses = registry.counter("platform_email_resolver_cache_misses");
  }

  /**
   * Returns the user's email, or {@link Optional#empty()} if the user doesn't exist in {@code
   * platform.users} (stale recipient id from before a platform-side delete). Throws on DB outage —
   * never returns empty just because the DB is down.
   */
  public Optional<String> resolveEmail(UUID userId) {
    Optional<String> cached = emailCache.getIfPresent(userId);
    if (cached != null) {
      hits.increment();
      return cached;
    }
    misses.increment();
    Optional<String> fresh = lookup(userId);
    emailCache.put(userId, fresh);
    return fresh;
  }

  private Optional<String> lookup(UUID userId) {
    try {
      String email =
          platformJdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
      return Optional.ofNullable(email);
    } catch (EmptyResultDataAccessException ex) {
      // User not found — negatively cache. The orchestrator records a FAILED delivery row.
      return Optional.empty();
    } catch (DataAccessResourceFailureException ex) {
      throw new PlatformUnavailableException("Platform DB unreachable for email lookup", ex);
    }
  }
}
