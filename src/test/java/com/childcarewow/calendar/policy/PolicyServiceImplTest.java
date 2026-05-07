package com.childcarewow.calendar.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.ForbiddenException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 4 roles × 3 actions = 12-case matrix per playbook spec, plus assertCan-throws and unknown-action
 * coverage. Pure unit tests — no Spring context.
 */
class PolicyServiceImplTest {

  private final PolicyServiceImpl policy = new PolicyServiceImpl();

  @ParameterizedTest(name = "[{index}] role={0} action={1} expected={2}")
  @CsvSource({
    // event.create: any non-PARENT
    "ORG_ADMIN,    event.create,    true",
    "SCHOOL_ADMIN, event.create,    true",
    "STAFF,        event.create,    true",
    "PARENT,       event.create,    false",
    // task.view: any non-PARENT (parents never see tasks per D10)
    "ORG_ADMIN,    task.view,       true",
    "SCHOOL_ADMIN, task.view,       true",
    "STAFF,        task.view,       true",
    "PARENT,       task.view,       false",
    // holiday.manage: only ORG_ADMIN or SCHOOL_ADMIN
    "ORG_ADMIN,    holiday.manage,  true",
    "SCHOOL_ADMIN, holiday.manage,  true",
    "STAFF,        holiday.manage,  false",
    "PARENT,       holiday.manage,  false",
  })
  void roleActionMatrix(String roleName, String action, boolean expected) {
    UserPrincipal actor = principalWithRole(Role.valueOf(roleName));
    assertThat(policy.can(actor, action)).isEqualTo(expected);
  }

  @Test
  void assertCanThrowsForbiddenOnDenial() {
    UserPrincipal parent = principalWithRole(Role.PARENT);
    assertThatThrownBy(() -> policy.assertCan(parent, "holiday.manage"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertCanReturnsSilentlyOnAllow() {
    UserPrincipal owner = principalWithRole(Role.ORG_ADMIN);
    policy.assertCan(owner, "event.create");
  }

  @Test
  void nullActorIsAlwaysDenied() {
    assertThat(policy.can(null, "event.create")).isFalse();
  }

  @Test
  void unknownActionDeniesByDefault() {
    UserPrincipal owner = principalWithRole(Role.ORG_ADMIN);
    assertThat(policy.can(owner, "totally.fake.action")).isFalse();
  }

  private static UserPrincipal principalWithRole(Role role) {
    return new UserPrincipal(
        UUID.fromString("33333333-0000-0000-0000-000000000001"),
        "Test User",
        "test@ccw.test",
        role,
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        Set.of(),
        Set.of(),
        Set.of(),
        null);
  }
}
