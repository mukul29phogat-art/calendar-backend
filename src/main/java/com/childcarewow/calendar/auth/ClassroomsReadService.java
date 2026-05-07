package com.childcarewow.calendar.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads classrooms for a school, with the assigned-staff array populated. Soft-deleted rows are
 * filtered out.
 *
 * <p>Two-query approach (one for classrooms, one for the staff map) rather than a single {@code
 * array_agg} aggregate. The aggregate would be one round-trip but its result type ({@code
 * java.sql.Array → UUID[]}) requires more JDBC plumbing; two queries on a small response (≤ 10
 * classrooms per school in practice) is simpler and the second query only fires when there's at
 * least one classroom. Revisit if performance review (Series 12) flags this.
 */
@Service
public class ClassroomsReadService {

  private static final String SQL_CLASSROOMS =
      "SELECT id, school_id, name FROM classrooms "
          + "WHERE school_id = :schoolId AND deleted_at IS NULL "
          + "ORDER BY name";

  private static final String SQL_STAFF =
      "SELECT classroom_id, user_id FROM classroom_staff WHERE classroom_id IN (:ids)";

  private final NamedParameterJdbcTemplate platformJdbc;
  private final Cache<UUID, List<ClassroomView>> cache =
      Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(60, TimeUnit.SECONDS).build();

  public ClassroomsReadService(
      @Qualifier("platformNamedJdbcTemplate") NamedParameterJdbcTemplate platformJdbc) {
    this.platformJdbc = platformJdbc;
  }

  public List<ClassroomView> findBySchool(UUID schoolId) {
    if (schoolId == null) {
      return List.of();
    }
    return cache.get(schoolId, this::loadFromDb);
  }

  private List<ClassroomView> loadFromDb(UUID schoolId) {
    List<ClassroomRow> rows =
        platformJdbc.query(
            SQL_CLASSROOMS,
            new MapSqlParameterSource().addValue("schoolId", schoolId),
            (rs, n) ->
                new ClassroomRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("school_id")),
                    rs.getString("name")));

    if (rows.isEmpty()) {
      return List.of();
    }

    List<UUID> classroomIds = rows.stream().map(ClassroomRow::id).toList();
    Map<UUID, List<UUID>> staffByClassroom = loadStaffMap(classroomIds);

    List<ClassroomView> result = new ArrayList<>(rows.size());
    for (ClassroomRow r : rows) {
      List<UUID> staff =
          staffByClassroom.getOrDefault(r.id(), List.of()).stream().sorted().toList();
      result.add(new ClassroomView(r.id(), r.schoolId(), r.name(), staff));
    }
    return result;
  }

  private Map<UUID, List<UUID>> loadStaffMap(List<UUID> classroomIds) {
    Map<UUID, List<UUID>> map = new HashMap<>();
    platformJdbc.query(
        SQL_STAFF,
        new MapSqlParameterSource().addValue("ids", classroomIds),
        rs -> {
          UUID classroomId = UUID.fromString(rs.getString("classroom_id"));
          UUID userId = UUID.fromString(rs.getString("user_id"));
          map.computeIfAbsent(classroomId, k -> new ArrayList<>()).add(userId);
        });
    return map;
  }

  /** Internal row shape for the first query — kept private to the service. */
  private record ClassroomRow(UUID id, UUID schoolId, String name) {}
}
