package com.childcarewow.calendar.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exercises {@link ClassroomsReadService} against the seeded platform DB. Sunrise has Butterflies
 * (Maya) + Caterpillars (Tom); Maplewood has Sunbeams (Maya) + Stars (no staff).
 */
@SpringBootTest
class ClassroomsReadServiceIT {

  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-000000000099");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID SUNBEAMS = UUID.fromString("44444444-0000-0000-0000-000000000003");
  private static final UUID STARS = UUID.fromString("44444444-0000-0000-0000-000000000004");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");

  @Autowired ClassroomsReadService service;

  @Autowired
  @Qualifier("platformJdbcTemplate")
  JdbcTemplate platformJdbc;

  @AfterEach
  void resetSoftDelete() {
    // Reset deleted_at for any classroom we soft-deleted in tests.
    platformJdbc.update("UPDATE classrooms SET deleted_at = NULL WHERE name = 'Stars'");
  }

  @Test
  void listsAllSunriseClassroomsSortedByName() {
    List<ClassroomView> result = service.findBySchool(SUNRISE);
    assertThat(result)
        .extracting(ClassroomView::name)
        .containsExactly("Butterflies", "Caterpillars");
    assertThat(result).extracting(ClassroomView::schoolId).containsOnly(SUNRISE);
  }

  @Test
  void populatesStaffUserIdsSorted() {
    List<ClassroomView> result = service.findBySchool(SUNRISE);
    ClassroomView butterflies =
        result.stream().filter(c -> c.id().equals(BUTTERFLIES)).findFirst().orElseThrow();
    ClassroomView caterpillars =
        result.stream().filter(c -> c.id().equals(CATERPILLARS)).findFirst().orElseThrow();
    assertThat(butterflies.staffUserIds()).containsExactly(MAYA);
    assertThat(caterpillars.staffUserIds()).containsExactly(TOM);
  }

  @Test
  void classroomWithNoStaffReturnsEmptyList() {
    List<ClassroomView> result = service.findBySchool(MAPLEWOOD);
    ClassroomView stars =
        result.stream().filter(c -> c.id().equals(STARS)).findFirst().orElseThrow();
    assertThat(stars.staffUserIds()).isEmpty();
    ClassroomView sunbeams =
        result.stream().filter(c -> c.id().equals(SUNBEAMS)).findFirst().orElseThrow();
    assertThat(sunbeams.staffUserIds()).containsExactly(MAYA);
  }

  @Test
  void unknownSchoolReturnsEmpty() {
    assertThat(service.findBySchool(UNKNOWN)).isEmpty();
  }

  @Test
  void nullSchoolReturnsEmpty() {
    assertThat(service.findBySchool(null)).isEmpty();
  }

  /**
   * Verifies the {@code deleted_at IS NULL} filter actually fires by inserting a soft-deleted
   * classroom into a brand-new school (cache miss guaranteed) and asserting the row doesn't appear
   * in the result.
   */
  @Test
  void softDeletedClassroomIsExcluded() {
    UUID isolatedSchool = UUID.fromString("22222222-2222-2222-2222-22222222dead");
    UUID newClassroom = UUID.randomUUID();

    // Set up: a school + one soft-deleted classroom in that school (no cache pollution).
    platformJdbc.update(
        "INSERT INTO schools (id, org_id, name, timezone) "
            + "VALUES (?, '11111111-1111-1111-1111-111111111111', 'IsolatedTestSchool', 'UTC')",
        isolatedSchool);
    platformJdbc.update(
        "INSERT INTO classrooms (id, org_id, school_id, name, deleted_at) "
            + "VALUES (?, '11111111-1111-1111-1111-111111111111', ?, 'SoftDeleted', now())",
        newClassroom,
        isolatedSchool);

    try {
      List<ClassroomView> result = service.findBySchool(isolatedSchool);
      assertThat(result).isEmpty();
    } finally {
      platformJdbc.update("DELETE FROM classrooms WHERE id = ?", newClassroom);
      platformJdbc.update("DELETE FROM schools WHERE id = ?", isolatedSchool);
    }
  }

  @Test
  void cachedSecondCallReturnsSameInstance() {
    List<ClassroomView> first = service.findBySchool(SUNRISE);
    List<ClassroomView> second = service.findBySchool(SUNRISE);
    assertThat(second).isSameAs(first);
  }
}
