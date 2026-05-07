package com.childcarewow.calendar.auth;

import com.childcarewow.calendar.exception.ValidationException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads {@code platform.students} with role-aware visibility:
 *
 * <ul>
 *   <li><b>Non-parent</b>: filter by {@code classroomId} (preferred) or {@code schoolId}.
 *   <li><b>Parent</b>: result is intersected with {@code actor.childStudentIds()} regardless of
 *       which scope filter the FE passed. Parents cannot see other parents' children — even if they
 *       target a classroom their kid is in.
 * </ul>
 *
 * <p>Soft-deleted rows ({@code deleted_at IS NOT NULL}) are excluded.
 *
 * <p>Cached for 60 seconds in Caffeine. Cache key includes the parent's child-id set so two parents
 * at the same classroom don't share a cache entry.
 */
@Service
public class StudentsReadService {

  private static final RowMapper<StudentView> ROW_MAPPER =
      (rs, n) -> {
        Date dobSql = rs.getDate("dob");
        return new StudentView(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("school_id")),
            UUID.fromString(rs.getString("classroom_id")),
            rs.getString("name"),
            dobSql == null ? null : dobSql.toLocalDate());
      };

  private static final String SQL_BASE =
      "SELECT id, school_id, classroom_id, name, dob FROM students WHERE deleted_at IS NULL";

  private final NamedParameterJdbcTemplate platformJdbc;
  private final Cache<CacheKey, List<StudentView>> cache =
      Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(60, TimeUnit.SECONDS).build();

  public StudentsReadService(
      @Qualifier("platformNamedJdbcTemplate") NamedParameterJdbcTemplate platformJdbc) {
    this.platformJdbc = platformJdbc;
  }

  /**
   * Returns the students matching the given scope, after applying the parent visibility filter.
   * Exactly one of {@code schoolId} / {@code classroomId} must be provided.
   */
  public List<StudentView> findByScope(UUID schoolId, UUID classroomId, UserPrincipal actor) {
    if (actor == null) {
      return List.of();
    }
    if (schoolId == null && classroomId == null) {
      throw new ValidationException("scope", "Either schoolId or classroomId is required");
    }

    boolean isParent = actor.role() == Role.PARENT;
    Set<UUID> parentScope = isParent ? actor.childStudentIds() : null;
    if (isParent && (parentScope == null || parentScope.isEmpty())) {
      // Parent with no enrolled children — the read is structurally empty regardless of filter.
      return List.of();
    }

    return cache.get(
        new CacheKey(schoolId, classroomId, parentScope),
        k -> loadFromDb(k.schoolId(), k.classroomId(), k.parentChildIds()));
  }

  private List<StudentView> loadFromDb(UUID schoolId, UUID classroomId, Set<UUID> parentChildIds) {
    StringBuilder sql = new StringBuilder(SQL_BASE);
    MapSqlParameterSource params = new MapSqlParameterSource();
    if (classroomId != null) {
      sql.append(" AND classroom_id = :classroomId");
      params.addValue("classroomId", classroomId);
    } else {
      sql.append(" AND school_id = :schoolId");
      params.addValue("schoolId", schoolId);
    }
    if (parentChildIds != null) {
      sql.append(" AND id IN (:childIds)");
      params.addValue("childIds", parentChildIds);
    }
    sql.append(" ORDER BY name");
    return platformJdbc.query(sql.toString(), params, ROW_MAPPER);
  }

  /** Cache key including the parent child-id set so two parents don't share an entry. */
  private record CacheKey(UUID schoolId, UUID classroomId, Set<UUID> parentChildIds) {
    private CacheKey {
      // Defensive copy so an upstream mutation can't shift our cache key's identity.
      parentChildIds = parentChildIds == null ? null : Set.copyOf(parentChildIds);
    }
  }
}
