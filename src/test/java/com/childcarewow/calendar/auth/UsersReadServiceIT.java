package com.childcarewow.calendar.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test against the seeded platform DB. Sunrise has 5 users (Olivia, Ravi, Maya, Tom,
 * Priya), Maplewood has 3 (Olivia, Sara, Daniel) — Olivia (ORG_ADMIN) is in both.
 */
@SpringBootTest
class UsersReadServiceIT {

  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID UNKNOWN_SCHOOL =
      UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Autowired UsersReadService service;

  @Test
  void listsAllUsersAssignedToSunrise() {
    List<UserView> users = service.findByScope(SUNRISE, null);
    // 5 seed users at Sunrise: Olivia (also at Maplewood), Ravi, Maya, Tom, Priya
    assertThat(users).hasSize(5);
    // Sorted by name → Maya Diallo, Olivia Park, Priya Singh, Ravi Mehta, Tom Becker
    assertThat(users)
        .extracting(UserView::name)
        .containsExactly("Maya Diallo", "Olivia Park", "Priya Singh", "Ravi Mehta", "Tom Becker");
  }

  @Test
  void filtersByStaffRoleAtSunrise() {
    List<UserView> staff = service.findByScope(SUNRISE, Role.STAFF);
    assertThat(staff).extracting(UserView::name).containsExactly("Maya Diallo", "Tom Becker");
    assertThat(staff).allSatisfy(u -> assertThat(u.role()).isEqualTo(Role.STAFF));
  }

  @Test
  void filtersByOrgAdminRole() {
    List<UserView> admins = service.findByScope(SUNRISE, Role.ORG_ADMIN);
    assertThat(admins).hasSize(1);
    assertThat(admins.get(0).name()).isEqualTo("Olivia Park");
    assertThat(admins.get(0).designation()).isEqualTo("Owner");
  }

  @Test
  void filtersByParentRole() {
    List<UserView> parents = service.findByScope(MAPLEWOOD, Role.PARENT);
    assertThat(parents).hasSize(1);
    assertThat(parents.get(0).name()).isEqualTo("Daniel Cho");
    // Parents have no designation
    assertThat(parents.get(0).designation()).isNull();
  }

  @Test
  void unknownSchoolReturnsEmpty() {
    assertThat(service.findByScope(UNKNOWN_SCHOOL, null)).isEmpty();
  }

  @Test
  void cachedSecondCallReturnsSameInstance() {
    List<UserView> first = service.findByScope(SUNRISE, Role.STAFF);
    List<UserView> second = service.findByScope(SUNRISE, Role.STAFF);
    // Caffeine cache hit returns the SAME list instance, not a copy
    assertThat(second).isSameAs(first);
  }
}
