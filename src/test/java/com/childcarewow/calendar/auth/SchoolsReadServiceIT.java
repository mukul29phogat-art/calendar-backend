package com.childcarewow.calendar.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Exercises {@link SchoolsReadService} against the seeded platform DB. The seed has 1 org with 2
 * schools (Sunrise, Maplewood) and an Olivia (ORG_ADMIN) who's user_schools-linked to both.
 */
@SpringBootTest
class SchoolsReadServiceIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID UNKNOWN_ORG = UUID.fromString("99999999-9999-9999-9999-999999999999");

  @Autowired SchoolsReadService service;

  @Test
  void orgAdminSeesAllSchoolsInOrg() {
    UserPrincipal admin = principal(Role.ORG_ADMIN, ORG, Set.of());
    List<SchoolView> schools = service.findVisibleTo(admin);
    assertThat(schools)
        .extracting(SchoolView::name)
        .containsExactly("Maplewood Preschool", "Sunrise Preschool");
    assertThat(schools)
        .extracting(SchoolView::timezone)
        .containsExactly("America/Chicago", "America/New_York");
  }

  @Test
  void schoolAdminSeesOnlyAssignedSchools() {
    UserPrincipal ravi = principal(Role.SCHOOL_ADMIN, ORG, Set.of(SUNRISE)); // Ravi runs Sunrise
    List<SchoolView> schools = service.findVisibleTo(ravi);
    assertThat(schools).hasSize(1);
    assertThat(schools.get(0).name()).isEqualTo("Sunrise Preschool");
  }

  @Test
  void staffSeesOnlyAssignedSchools() {
    UserPrincipal maya = principal(Role.STAFF, ORG, Set.of(SUNRISE));
    List<SchoolView> schools = service.findVisibleTo(maya);
    assertThat(schools).hasSize(1);
    assertThat(schools.get(0).id()).isEqualTo(SUNRISE);
  }

  @Test
  void parentSeesOnlyAssignedSchools() {
    UserPrincipal priya = principal(Role.PARENT, ORG, Set.of(SUNRISE));
    List<SchoolView> schools = service.findVisibleTo(priya);
    assertThat(schools).hasSize(1);
    assertThat(schools.get(0).id()).isEqualTo(SUNRISE);
  }

  @Test
  void parentWithMultipleSchoolsSeesAll() {
    // Theoretically a parent with kids in two schools — synthesized test case.
    UserPrincipal multiSchoolParent = principal(Role.PARENT, ORG, Set.of(SUNRISE, MAPLEWOOD));
    List<SchoolView> schools = service.findVisibleTo(multiSchoolParent);
    assertThat(schools).hasSize(2);
  }

  @Test
  void emptySchoolIdsReturnsEmpty() {
    UserPrincipal staffWithoutSchool = principal(Role.STAFF, ORG, Set.of());
    assertThat(service.findVisibleTo(staffWithoutSchool)).isEmpty();
  }

  @Test
  void orgAdminInUnknownOrgReturnsEmpty() {
    UserPrincipal stranger = principal(Role.ORG_ADMIN, UNKNOWN_ORG, Set.of());
    assertThat(service.findVisibleTo(stranger)).isEmpty();
  }

  @Test
  void nullActorReturnsEmpty() {
    assertThat(service.findVisibleTo(null)).isEmpty();
  }

  private static UserPrincipal principal(Role role, UUID orgId, Set<UUID> schoolIds) {
    return new UserPrincipal(
        UUID.fromString("33333333-0000-0000-0000-000000000001"),
        "Test",
        "test@ccw.test",
        role,
        orgId,
        schoolIds,
        Set.of(),
        Set.of(),
        null);
  }
}
