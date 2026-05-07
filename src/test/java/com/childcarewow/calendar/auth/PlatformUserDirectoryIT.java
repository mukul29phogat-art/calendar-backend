package com.childcarewow.calendar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Per-role end-to-end check that {@link PlatformUserDirectory} loads the right principal shape from
 * the platform-DB seed (defined in docker/platform-seed.sql).
 */
@SpringBootTest
class PlatformUserDirectoryIT {

  // Seed UUIDs from docker/platform-seed.sql
  private static final UUID OLIVIA_ORG_ADMIN =
      UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID RAVI_SCHOOL_ADMIN =
      UUID.fromString("33333333-0000-0000-0000-000000000002");
  private static final UUID MAYA_STAFF = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID PRIYA_PARENT = UUID.fromString("33333333-0000-0000-0000-000000000006");

  private static final UUID SUNRISE_SCHOOL =
      UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD_SCHOOL =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES_CLASSROOM =
      UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID SUNBEAMS_CLASSROOM =
      UUID.fromString("44444444-0000-0000-0000-000000000003");
  private static final UUID AANYA_STUDENT = UUID.fromString("55555555-0000-0000-0000-000000000001");

  @Autowired PlatformUserDirectory directory;

  @Test
  void orgAdminHasBothSchools() {
    UserPrincipal p = directory.load(OLIVIA_ORG_ADMIN);
    assertThat(p.role()).isEqualTo(Role.ORG_ADMIN);
    assertThat(p.email()).isEqualTo("olivia@ccw-demo.test");
    assertThat(p.designation()).isEqualTo("Owner");
    assertThat(p.schoolIds()).containsExactlyInAnyOrder(SUNRISE_SCHOOL, MAPLEWOOD_SCHOOL);
    assertThat(p.classroomIds()).isEmpty();
    assertThat(p.childStudentIds()).isEmpty();
  }

  @Test
  void schoolAdminHasOneSchoolNoClassrooms() {
    UserPrincipal p = directory.load(RAVI_SCHOOL_ADMIN);
    assertThat(p.role()).isEqualTo(Role.SCHOOL_ADMIN);
    assertThat(p.designation()).isEqualTo("Sunrise Director");
    assertThat(p.schoolIds()).containsExactly(SUNRISE_SCHOOL);
    assertThat(p.classroomIds()).isEmpty();
  }

  @Test
  void staffHasTwoClassrooms() {
    UserPrincipal p = directory.load(MAYA_STAFF);
    assertThat(p.role()).isEqualTo(Role.STAFF);
    assertThat(p.classroomIds())
        .containsExactlyInAnyOrder(BUTTERFLIES_CLASSROOM, SUNBEAMS_CLASSROOM);
  }

  @Test
  void parentHasChildStudent() {
    UserPrincipal p = directory.load(PRIYA_PARENT);
    assertThat(p.role()).isEqualTo(Role.PARENT);
    assertThat(p.childStudentIds()).containsExactly(AANYA_STUDENT);
  }

  @Test
  void unknownUuidThrows() {
    UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000000");
    assertThatThrownBy(() -> directory.load(unknown)).isInstanceOf(UnknownPrincipalException.class);
  }
}
