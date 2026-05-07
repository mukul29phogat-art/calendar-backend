package com.childcarewow.calendar.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the {@code platform.users} table via the platform DB (D11 — calendar service does not own
 * user data). Cached for 60 seconds in Caffeine; that's short enough that role changes propagate
 * quickly while long enough to absorb burst traffic from the assignee-selector picker being
 * re-rendered.
 */
@Service
public class UsersReadService {

  private static final RowMapper<UserView> ROW_MAPPER =
      (rs, n) ->
          new UserView(
              UUID.fromString(rs.getString("id")),
              rs.getString("name"),
              rs.getString("email"),
              Role.valueOf(rs.getString("role")),
              rs.getString("designation"));

  private static final String SQL =
      "SELECT u.id, u.name, u.email, u.role, u.designation "
          + "FROM users u "
          + "JOIN user_schools us ON us.user_id = u.id "
          + "WHERE us.school_id = :schoolId "
          + "AND (:role IS NULL OR u.role = :role) "
          + "ORDER BY u.name";

  private final NamedParameterJdbcTemplate platformJdbc;
  private final Cache<CacheKey, List<UserView>> cache =
      Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(60, TimeUnit.SECONDS).build();

  public UsersReadService(
      @Qualifier("platformNamedJdbcTemplate") NamedParameterJdbcTemplate platformJdbc) {
    this.platformJdbc = platformJdbc;
  }

  /**
   * Returns the users assigned to the given school, optionally filtered by role. Results are sorted
   * by {@code name} for stable rendering in the FE.
   */
  public List<UserView> findByScope(UUID schoolId, Role role) {
    CacheKey key = new CacheKey(schoolId, role);
    return cache.get(
        key,
        k -> {
          // Bind role with an explicit VARCHAR type. Postgres cannot infer the parameter type
          // for null-valued parameters in the (:role IS NULL OR u.role = :role) pattern; without
          // the type hint the query fails with "could not determine data type of parameter".
          MapSqlParameterSource params =
              new MapSqlParameterSource()
                  .addValue("schoolId", k.schoolId())
                  .addValue(
                      "role", k.role() == null ? null : k.role().name(), java.sql.Types.VARCHAR);
          return platformJdbc.query(SQL, params, ROW_MAPPER);
        });
  }

  private record CacheKey(UUID schoolId, Role role) {}
}
