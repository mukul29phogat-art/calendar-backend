package com.childcarewow.calendar.platform;

import com.childcarewow.calendar.exception.PlatformUnavailableException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Caffeine-cached implementation of {@link PlatformEntityValidator}. 5-minute TTL, 10K max entries
 * per cache, ~100 bytes/entry → ~1 MB heap budget for the four caches combined.
 *
 * <p>Hit/miss counters are exposed via Micrometer at {@code platform_validator_cache_hits} and
 * {@code platform_validator_cache_misses}. Cache invalidation on platform-side mutations is out of
 * scope (platform owns its data; we accept up to 5 min staleness — runbook documents the
 * trade-off).
 *
 * <p>If the platform DB is unreachable the lookup throws {@link PlatformUnavailableException} →
 * HTTP 503. Fail-closed: a caller can't accidentally proceed past validation.
 */
@Service
public class PlatformEntityValidatorImpl implements PlatformEntityValidator {

  private static final Duration TTL = Duration.ofMinutes(5);
  private static final long MAX_SIZE = 10_000L;

  private final JdbcTemplate platformJdbc;

  private final Cache<UUID, Boolean> schoolCache = newCache();
  private final Cache<UUID, Boolean> classroomCache = newCache();
  private final Cache<UUID, Boolean> userCache = newCache();
  private final Cache<UUID, Boolean> studentCache = newCache();
  private final Cache<ClassroomSchoolKey, Boolean> classroomBelongsCache = newCache();

  private final Counter hits;
  private final Counter misses;

  public PlatformEntityValidatorImpl(
      @Qualifier("platformJdbcTemplate") JdbcTemplate platformJdbc, MeterRegistry registry) {
    this.platformJdbc = platformJdbc;
    this.hits = registry.counter("platform_validator_cache_hits");
    this.misses = registry.counter("platform_validator_cache_misses");
  }

  @Override
  public boolean schoolExists(UUID schoolId) {
    return checkCached(schoolCache, schoolId, "schools");
  }

  @Override
  public boolean classroomExists(UUID classroomId) {
    return checkCached(classroomCache, classroomId, "classrooms");
  }

  @Override
  public boolean userExists(UUID userId) {
    return checkCached(userCache, userId, "users");
  }

  @Override
  public boolean studentExists(UUID studentId) {
    return checkCached(studentCache, studentId, "students");
  }

  @Override
  public boolean classroomBelongsToSchool(UUID classroomId, UUID schoolId) {
    ClassroomSchoolKey key = new ClassroomSchoolKey(classroomId, schoolId);
    Boolean cached = classroomBelongsCache.getIfPresent(key);
    if (cached != null) {
      hits.increment();
      return cached;
    }
    misses.increment();
    boolean exists = queryClassroomBelongs(classroomId, schoolId);
    classroomBelongsCache.put(key, exists);
    return exists;
  }

  private boolean checkCached(Cache<UUID, Boolean> cache, UUID id, String table) {
    Boolean cached = cache.getIfPresent(id);
    if (cached != null) {
      hits.increment();
      return cached;
    }
    misses.increment();
    boolean exists = queryExists(table, id);
    cache.put(id, exists);
    return exists;
  }

  private boolean queryExists(String table, UUID id) {
    try {
      Integer count =
          platformJdbc.queryForObject(
              "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
      return count != null && count > 0;
    } catch (DataAccessResourceFailureException e) {
      throw new PlatformUnavailableException(
          "Platform DB unreachable while checking " + table + "." + id, e);
    }
  }

  private boolean queryClassroomBelongs(UUID classroomId, UUID schoolId) {
    try {
      Integer count =
          platformJdbc.queryForObject(
              "SELECT count(*) FROM classrooms WHERE id = ? AND school_id = ?",
              Integer.class,
              classroomId,
              schoolId);
      return count != null && count > 0;
    } catch (DataAccessResourceFailureException e) {
      throw new PlatformUnavailableException(
          "Platform DB unreachable while checking classroom→school link", e);
    }
  }

  private static <K> Cache<K, Boolean> newCache() {
    return Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(MAX_SIZE).build();
  }

  /** Composite key for the classroom-belongs-to-school cache. */
  private record ClassroomSchoolKey(UUID classroomId, UUID schoolId) {}
}
