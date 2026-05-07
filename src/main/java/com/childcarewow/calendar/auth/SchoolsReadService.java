package com.childcarewow.calendar.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
 * Reads {@code platform.schools} via the platform DB, with role-aware visibility:
 *
 * <ul>
 *   <li>{@link Role#ORG_ADMIN} sees every school in their {@code orgId}.
 *   <li>Other roles see only the schools listed in {@code actor.schoolIds()}.
 * </ul>
 *
 * <p>Cached for 60 seconds in Caffeine. Schools rarely move between orgs (or rename), so a
 * one-minute staleness window is acceptable; the cache is keyed by the actor scope (org-wide vs the
 * specific school-id set) so two actors with different scopes don't collide.
 */
@Service
public class SchoolsReadService {

  private static final RowMapper<SchoolView> ROW_MAPPER =
      (rs, n) ->
          new SchoolView(
              UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("timezone"));

  private static final String SQL_BY_ORG =
      "SELECT id, name, timezone FROM schools WHERE org_id = :orgId ORDER BY name";

  private static final String SQL_BY_IDS =
      "SELECT id, name, timezone FROM schools WHERE id IN (:ids) ORDER BY name";

  private final NamedParameterJdbcTemplate platformJdbc;
  private final Cache<CacheKey, List<SchoolView>> cache =
      Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(60, TimeUnit.SECONDS).build();

  public SchoolsReadService(
      @Qualifier("platformNamedJdbcTemplate") NamedParameterJdbcTemplate platformJdbc) {
    this.platformJdbc = platformJdbc;
  }

  public List<SchoolView> findVisibleTo(UserPrincipal actor) {
    if (actor == null) {
      return List.of();
    }
    if (actor.role() == Role.ORG_ADMIN) {
      return cache.get(
          CacheKey.org(actor.orgId()),
          k -> {
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("orgId", k.orgId());
            return platformJdbc.query(SQL_BY_ORG, params, ROW_MAPPER);
          });
    }
    Set<UUID> ids = actor.schoolIds();
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return cache.get(
        CacheKey.ids(ids),
        k -> {
          MapSqlParameterSource params = new MapSqlParameterSource().addValue("ids", k.ids());
          return platformJdbc.query(SQL_BY_IDS, params, ROW_MAPPER);
        });
  }

  /**
   * Cache key with two flavours: org-wide for ORG_ADMIN (keyed by orgId) and id-set for everyone
   * else (keyed by the school-id collection). Constructed via factories so the unused field is
   * always null and {@link Object#equals} works on the right comparison.
   */
  private record CacheKey(UUID orgId, Set<UUID> ids) {
    static CacheKey org(UUID orgId) {
      return new CacheKey(orgId, null);
    }

    static CacheKey ids(Set<UUID> ids) {
      return new CacheKey(null, Set.copyOf(ids));
    }
  }
}
