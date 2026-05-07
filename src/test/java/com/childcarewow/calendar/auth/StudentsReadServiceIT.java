package com.childcarewow.calendar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.exception.ValidationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class StudentsReadServiceIT {

  // From docker/platform-seed.sql
  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID SUNBEAMS = UUID.fromString("44444444-0000-0000-0000-000000000003");
  private static final UUID AANYA = UUID.fromString("55555555-0000-0000-0000-000000000001");
  private static final UUID JORDAN = UUID.fromString("55555555-0000-0000-0000-000000000002");
  private static final UUID LILA = UUID.fromString("55555555-0000-0000-0000-000000000003");
  private static final UUID NOAH = UUID.fromString("55555555-0000-0000-0000-000000000004");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  private static final UUID DANIEL = UUID.fromString("33333333-0000-0000-0000-000000000007");

  @Autowired StudentsReadService service;

  @Autowired
  @Qualifier("platformJdbcTemplate")
  JdbcTemplate platformJdbc;

  @AfterEach
  void cleanup() {
    platformJdbc.update(
        "UPDATE students SET deleted_at = NULL WHERE name IN ('Aanya Singh','Lila Cho')");
  }

  // -- non-parent scope -------------------------------------------------------

  @Test
  void staffSeesAllStudentsInClassroom() {
    UserPrincipal staff = staff(MAYA, Set.of(SUNRISE), Set.of(BUTTERFLIES));
    List<StudentView> result = service.findByScope(null, BUTTERFLIES, staff);
    assertThat(result).extracting(StudentView::id).containsExactly(AANYA);
    assertThat(result.get(0).name()).isEqualTo("Aanya Singh");
    assertThat(result.get(0).classroomId()).isEqualTo(BUTTERFLIES);
  }

  @Test
  void staffSeesAllStudentsInSchoolWhenSchoolFilterUsed() {
    UserPrincipal staff = staff(MAYA, Set.of(SUNRISE), Set.of(BUTTERFLIES));
    List<StudentView> result = service.findByScope(SUNRISE, null, staff);
    assertThat(result)
        .extracting(StudentView::name)
        .containsExactly("Aanya Singh", "Jordan Becker");
  }

  @Test
  void classroomTakesPrecedenceOverSchoolWhenBothProvided() {
    UserPrincipal staff = staff(MAYA, Set.of(SUNRISE), Set.of(BUTTERFLIES));
    // Classroom in a different school than the schoolId param — should still scope to classroom.
    List<StudentView> result = service.findByScope(MAPLEWOOD, BUTTERFLIES, staff);
    assertThat(result).extracting(StudentView::id).containsExactly(AANYA);
  }

  // -- parent visibility ------------------------------------------------------

  @Test
  void parentSeesOnlyOwnChildrenInClassroom() {
    UserPrincipal priya = parent(PRIYA, Set.of(SUNRISE), Set.of(AANYA));
    List<StudentView> result = service.findByScope(null, BUTTERFLIES, priya);
    assertThat(result).extracting(StudentView::id).containsExactly(AANYA);
  }

  @Test
  void parentTargetingClassroomThatExcludesTheirChildSeesNothing() {
    // Priya's child is in Butterflies; she queries Caterpillars → empty.
    UserPrincipal priya = parent(PRIYA, Set.of(SUNRISE), Set.of(AANYA));
    assertThat(service.findByScope(null, CATERPILLARS, priya)).isEmpty();
  }

  @Test
  void parentSchoolFilterStillNarrowsToOwnChildren() {
    UserPrincipal daniel = parent(DANIEL, Set.of(MAPLEWOOD), Set.of(LILA));
    List<StudentView> result = service.findByScope(MAPLEWOOD, null, daniel);
    assertThat(result).extracting(StudentView::id).containsExactly(LILA);
    // Noah is also at Maplewood but NOT Daniel's child — must be excluded.
    assertThat(result).extracting(StudentView::id).doesNotContain(NOAH);
  }

  @Test
  void parentWithNoChildrenReturnsEmpty() {
    UserPrincipal lonely = parent(PRIYA, Set.of(SUNRISE), Set.of()); // no enrolled children
    assertThat(service.findByScope(SUNRISE, null, lonely)).isEmpty();
  }

  // -- soft-delete + edge cases -----------------------------------------------

  @Test
  void softDeletedStudentExcluded() {
    platformJdbc.update("UPDATE students SET deleted_at = now() WHERE id = ?", JORDAN);
    UserPrincipal staff = staff(MAYA, Set.of(SUNRISE), Set.of(BUTTERFLIES));
    // Use a different scope to avoid cache hit from a prior test in this class
    List<StudentView> result = service.findByScope(SUNRISE, CATERPILLARS, staff);
    assertThat(result).isEmpty();
    platformJdbc.update("UPDATE students SET deleted_at = NULL WHERE id = ?", JORDAN);
  }

  @Test
  void missingBothScopeParamsThrowsValidation() {
    UserPrincipal staff = staff(MAYA, Set.of(SUNRISE), Set.of());
    assertThatThrownBy(() -> service.findByScope(null, null, staff))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("schoolId or classroomId");
  }

  @Test
  void nullActorReturnsEmpty() {
    assertThat(service.findByScope(SUNRISE, null, null)).isEmpty();
  }

  @Test
  void cachedSecondCallReturnsSameInstance() {
    UserPrincipal staff = staff(MAYA, Set.of(SUNRISE), Set.of(BUTTERFLIES));
    List<StudentView> first = service.findByScope(null, SUNBEAMS, staff);
    List<StudentView> second = service.findByScope(null, SUNBEAMS, staff);
    assertThat(second).isSameAs(first);
  }

  // -- helpers ----------------------------------------------------------------

  private static UserPrincipal staff(UUID id, Set<UUID> schools, Set<UUID> classrooms) {
    return new UserPrincipal(
        id, "Test", "test@ccw.test", Role.STAFF, ORG, schools, classrooms, Set.of(), null);
  }

  private static UserPrincipal parent(UUID id, Set<UUID> schools, Set<UUID> children) {
    return new UserPrincipal(
        id, "Parent", "parent@ccw.test", Role.PARENT, ORG, schools, Set.of(), children, null);
  }
}
