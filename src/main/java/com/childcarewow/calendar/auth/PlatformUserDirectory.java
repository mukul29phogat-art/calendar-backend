package com.childcarewow.calendar.auth;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Loads a {@link UserPrincipal} from the platform database. Refs are bare UUIDs (per D11), so we
 * use plain SQL via {@link JdbcTemplate} instead of JPA across DBs.
 *
 * <p>Caching is intentionally NOT here yet — per-request lookup is the simplest correct behavior
 * for now. Caffeine + 60s TTL lands together with the {@code PlatformEntityValidator} cache in Part
 * 2.3, where the architecture spec § 7.1 v4.1 polish item 8 is implemented holistically.
 */
@Service
public class PlatformUserDirectory {

  private final JdbcTemplate platformJdbc;

  public PlatformUserDirectory(@Qualifier("platformJdbcTemplate") JdbcTemplate platformJdbc) {
    this.platformJdbc = platformJdbc;
  }

  /**
   * Loads the full principal for {@code userId}. Throws {@link UnknownPrincipalException} if the
   * user doesn't exist in the platform DB.
   */
  public UserPrincipal load(UUID userId) {
    try {
      return platformJdbc.queryForObject(
          """
          SELECT id, org_id, email, role, designation
          FROM users
          WHERE id = ?
          """,
          (rs, rowNum) -> {
            UUID id = (UUID) rs.getObject("id");
            UUID orgId = (UUID) rs.getObject("org_id");
            String email = rs.getString("email");
            Role role = Role.valueOf(rs.getString("role"));
            String designation = rs.getString("designation");
            Set<UUID> schoolIds = loadSchoolIds(id);
            Set<UUID> classroomIds = loadClassroomIds(id);
            Set<UUID> childStudentIds = role == Role.PARENT ? loadChildStudentIds(id) : Set.of();
            return new UserPrincipal(
                id, email, role, orgId, schoolIds, classroomIds, childStudentIds, designation);
          },
          userId);
    } catch (EmptyResultDataAccessException e) {
      throw new UnknownPrincipalException(userId, e);
    }
  }

  private Set<UUID> loadSchoolIds(UUID userId) {
    return new HashSet<>(
        platformJdbc.query(
            "SELECT school_id FROM user_schools WHERE user_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject("school_id"),
            userId));
  }

  private Set<UUID> loadClassroomIds(UUID userId) {
    return new HashSet<>(
        platformJdbc.query(
            "SELECT classroom_id FROM classroom_staff WHERE user_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject("classroom_id"),
            userId));
  }

  private Set<UUID> loadChildStudentIds(UUID parentUserId) {
    return new HashSet<>(
        platformJdbc.query(
            "SELECT student_id FROM student_parents WHERE user_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject("student_id"),
            parentUserId));
  }
}
