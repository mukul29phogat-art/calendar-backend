package com.childcarewow.calendar.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.Event;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.exception.ForbiddenException;
import com.childcarewow.calendar.task.Task;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 19 actions × 4 roles ≈ 60 cases, grouped: resource-less actions parameterized; resource-bearing
 * (Event/Task) actions parameterized separately because they need the resource shape; edge cases
 * (null actor, null resource, unknown action) one-off.
 *
 * <p>Pure unit tests — no Spring context.
 */
class PolicyServiceImplTest {

  private final PolicyServiceImpl policy = new PolicyServiceImpl();

  // Stable UUIDs reused across cases
  private static final UUID ACTOR_ID = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID OTHER_USER = UUID.fromString("33333333-0000-0000-0000-000000000099");
  private static final UUID SCHOOL_IN = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID SCHOOL_OUT = UUID.fromString("22222222-2222-2222-2222-222222222299");
  private static final UUID CLASSROOM_IN = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CLASSROOM_OUT = UUID.fromString("44444444-0000-0000-0000-000000000099");

  // ---------------------------------------------------------------------------
  // Resource-less action × role matrix
  // ---------------------------------------------------------------------------
  @ParameterizedTest(name = "[{index}] role={0} action={1} expected={2}")
  @CsvSource({
    // event.create — non-PARENT
    "ORG_ADMIN,    event.create,             true",
    "SCHOOL_ADMIN, event.create,             true",
    "STAFF,        event.create,             true",
    "PARENT,       event.create,             false",
    // event.create.schoolType — admins only
    "ORG_ADMIN,    event.create.schoolType,  true",
    "SCHOOL_ADMIN, event.create.schoolType,  true",
    "STAFF,        event.create.schoolType,  false",
    "PARENT,       event.create.schoolType,  false",
    // task.view — non-PARENT (D10)
    "ORG_ADMIN,    task.view,                true",
    "SCHOOL_ADMIN, task.view,                true",
    "STAFF,        task.view,                true",
    "PARENT,       task.view,                false",
    // task.create — non-PARENT
    "ORG_ADMIN,    task.create,              true",
    "SCHOOL_ADMIN, task.create,              true",
    "STAFF,        task.create,              true",
    "PARENT,       task.create,              false",
    // task.viewAllScope — admins only
    "ORG_ADMIN,    task.viewAllScope,        true",
    "SCHOOL_ADMIN, task.viewAllScope,        true",
    "STAFF,        task.viewAllScope,        false",
    "PARENT,       task.viewAllScope,        false",
    // holiday.manage / holiday.approve / importantDate.manage — admins only
    "ORG_ADMIN,    holiday.manage,           true",
    "SCHOOL_ADMIN, holiday.manage,           true",
    "STAFF,        holiday.manage,           false",
    "PARENT,       holiday.manage,           false",
    "ORG_ADMIN,    holiday.approve,          true",
    "SCHOOL_ADMIN, holiday.approve,          true",
    "STAFF,        holiday.approve,          false",
    "PARENT,       holiday.approve,          false",
    "ORG_ADMIN,    importantDate.manage,     true",
    "SCHOOL_ADMIN, importantDate.manage,     true",
    "STAFF,        importantDate.manage,     false",
    "PARENT,       importantDate.manage,     false",
    // calendar.softFlag.see — non-PARENT
    "ORG_ADMIN,    calendar.softFlag.see,    true",
    "SCHOOL_ADMIN, calendar.softFlag.see,    true",
    "STAFF,        calendar.softFlag.see,    true",
    "PARENT,       calendar.softFlag.see,    false",
    // addMenu.* — non-PARENT for show/event/task; admin-only for holiday/importantDate
    "STAFF,        addMenu.show,             true",
    "PARENT,       addMenu.show,             false",
    "STAFF,        addMenu.event,            true",
    "PARENT,       addMenu.event,            false",
    "STAFF,        addMenu.task,             true",
    "PARENT,       addMenu.task,             false",
    "ORG_ADMIN,    addMenu.holiday,          true",
    "SCHOOL_ADMIN, addMenu.holiday,          true",
    "STAFF,        addMenu.holiday,          false",
    "PARENT,       addMenu.holiday,          false",
    "ORG_ADMIN,    addMenu.importantDate,    true",
    "SCHOOL_ADMIN, addMenu.importantDate,    true",
    "STAFF,        addMenu.importantDate,    false",
    "PARENT,       addMenu.importantDate,    false",
    // notifications.see — everyone
    "ORG_ADMIN,    notifications.see,        true",
    "SCHOOL_ADMIN, notifications.see,        true",
    "STAFF,        notifications.see,        true",
    "PARENT,       notifications.see,        true",
  })
  void resourceLessActions(String roleName, String action, boolean expected) {
    UserPrincipal actor = principal(Role.valueOf(roleName), Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(actor, action)).isEqualTo(expected);
  }

  // ---------------------------------------------------------------------------
  // Resource-bearing: events
  // ---------------------------------------------------------------------------
  @Test
  void orgAdminCanEditAnyEvent() {
    UserPrincipal a = principal(Role.ORG_ADMIN, Set.of(), Set.of());
    Event e = event(EventType.SCHOOL, SCHOOL_OUT, null);
    assertThat(policy.can(a, "event.edit", e)).isTrue();
    assertThat(policy.can(a, "event.delete", e)).isTrue();
  }

  @Test
  void schoolAdminCanEditEventInTheirSchoolOnly() {
    UserPrincipal a = principal(Role.SCHOOL_ADMIN, Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(a, "event.edit", event(EventType.SCHOOL, SCHOOL_IN, null))).isTrue();
    assertThat(policy.can(a, "event.edit", event(EventType.SCHOOL, SCHOOL_OUT, null))).isFalse();
  }

  @Test
  void staffCanEditClassroomEventOnlyIfClassroomAssigned() {
    UserPrincipal a = principal(Role.STAFF, Set.of(SCHOOL_IN), Set.of(CLASSROOM_IN));
    assertThat(policy.can(a, "event.edit", event(EventType.CLASSROOM, SCHOOL_IN, CLASSROOM_IN)))
        .isTrue();
    assertThat(policy.can(a, "event.edit", event(EventType.CLASSROOM, SCHOOL_IN, CLASSROOM_OUT)))
        .isFalse();
    // CLASSROOM with null classroomId — defensive false
    assertThat(policy.can(a, "event.edit", event(EventType.CLASSROOM, SCHOOL_IN, null))).isFalse();
  }

  @Test
  void staffCanEditCustomEventInTheirSchool() {
    UserPrincipal a = principal(Role.STAFF, Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(a, "event.edit", event(EventType.CUSTOM, SCHOOL_IN, null))).isTrue();
    assertThat(policy.can(a, "event.edit", event(EventType.CUSTOM, SCHOOL_OUT, null))).isFalse();
  }

  @Test
  void staffCannotEditSchoolWideEvents() {
    UserPrincipal a = principal(Role.STAFF, Set.of(SCHOOL_IN), Set.of(CLASSROOM_IN));
    assertThat(policy.can(a, "event.edit", event(EventType.SCHOOL, SCHOOL_IN, null))).isFalse();
  }

  @Test
  void parentCannotEditAnyEvent() {
    UserPrincipal a = principal(Role.PARENT, Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(a, "event.edit", event(EventType.CLASSROOM, SCHOOL_IN, CLASSROOM_IN)))
        .isFalse();
    assertThat(policy.can(a, "event.delete", event(EventType.CUSTOM, SCHOOL_IN, null))).isFalse();
  }

  @Test
  void eventOverloadDelegatesToResourceLessForOtherActions() {
    UserPrincipal admin = principal(Role.ORG_ADMIN, Set.of(), Set.of());
    Event e = event(EventType.CUSTOM, SCHOOL_IN, null);
    // event.create is resource-less but the overload should still answer correctly via delegation
    assertThat(policy.can(admin, "event.create", e)).isTrue();
    UserPrincipal parent = principal(Role.PARENT, Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(parent, "event.create", e)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Resource-bearing: tasks
  // ---------------------------------------------------------------------------
  @Test
  void orgAdminCanEditAnyTask() {
    UserPrincipal a = principal(Role.ORG_ADMIN, Set.of(), Set.of());
    assertThat(policy.can(a, "task.edit", task(SCHOOL_OUT, OTHER_USER))).isTrue();
    assertThat(policy.can(a, "task.delete", task(SCHOOL_OUT, OTHER_USER))).isTrue();
  }

  @Test
  void schoolAdminCanEditTaskInTheirSchoolOnly() {
    UserPrincipal a = principal(Role.SCHOOL_ADMIN, Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(a, "task.edit", task(SCHOOL_IN, OTHER_USER))).isTrue();
    assertThat(policy.can(a, "task.edit", task(SCHOOL_OUT, OTHER_USER))).isFalse();
  }

  @Test
  void staffCanEditTaskOnlyIfAssignedToThem() {
    UserPrincipal a = principal(Role.STAFF, Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(a, "task.edit", task(SCHOOL_IN, ACTOR_ID))).isTrue();
    assertThat(policy.can(a, "task.edit", task(SCHOOL_IN, OTHER_USER))).isFalse();
    assertThat(policy.can(a, "task.delete", task(SCHOOL_IN, null))).isFalse();
  }

  @Test
  void parentCannotEditAnyTask() {
    UserPrincipal a = principal(Role.PARENT, Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(a, "task.edit", task(SCHOOL_IN, ACTOR_ID))).isFalse();
  }

  @Test
  void taskOverloadDelegatesToResourceLessForOtherActions() {
    UserPrincipal admin = principal(Role.ORG_ADMIN, Set.of(), Set.of());
    Task t = task(SCHOOL_IN, ACTOR_ID);
    assertThat(policy.can(admin, "task.create", t)).isTrue();
    UserPrincipal parent = principal(Role.PARENT, Set.of(SCHOOL_IN), Set.of());
    assertThat(policy.can(parent, "task.view", t)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Edges
  // ---------------------------------------------------------------------------
  @Test
  void resourceBearingActionWithoutResourceDefaultsToFalse() {
    UserPrincipal a = principal(Role.ORG_ADMIN, Set.of(), Set.of());
    assertThat(policy.can(a, "event.edit")).isFalse();
    assertThat(policy.can(a, "event.delete")).isFalse();
    assertThat(policy.can(a, "task.edit")).isFalse();
    assertThat(policy.can(a, "task.delete")).isFalse();
  }

  @Test
  void nullActorAlwaysDenies() {
    Event e = event(EventType.CUSTOM, SCHOOL_IN, null);
    Task t = task(SCHOOL_IN, ACTOR_ID);
    assertThat(policy.can(null, "event.create")).isFalse();
    assertThat(policy.can(null, "event.edit", e)).isFalse();
    assertThat(policy.can(null, "task.edit", t)).isFalse();
  }

  @Test
  void nullResourceDeniesEvenForOrgAdmin() {
    UserPrincipal a = principal(Role.ORG_ADMIN, Set.of(), Set.of());
    assertThat(policy.can(a, "event.edit", (Event) null)).isFalse();
    assertThat(policy.can(a, "task.edit", (Task) null)).isFalse();
  }

  @Test
  void unknownActionDeniesByDefault() {
    UserPrincipal a = principal(Role.ORG_ADMIN, Set.of(), Set.of());
    assertThat(policy.can(a, "totally.fake.action")).isFalse();
  }

  @Test
  void assertCanOverloadsThrowOnDenial() {
    UserPrincipal parent = principal(Role.PARENT, Set.of(SCHOOL_IN), Set.of());
    Event e = event(EventType.CUSTOM, SCHOOL_IN, null);
    Task t = task(SCHOOL_IN, ACTOR_ID);
    assertThatThrownBy(() -> policy.assertCan(parent, "holiday.manage"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> policy.assertCan(parent, "event.edit", e))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> policy.assertCan(parent, "task.edit", t))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertCanOverloadsReturnSilentlyOnAllow() {
    UserPrincipal admin = principal(Role.ORG_ADMIN, Set.of(), Set.of());
    Event e = event(EventType.CUSTOM, SCHOOL_IN, null);
    Task t = task(SCHOOL_IN, OTHER_USER);
    policy.assertCan(admin, "event.create");
    policy.assertCan(admin, "event.edit", e);
    policy.assertCan(admin, "task.edit", t);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------
  private static UserPrincipal principal(Role role, Set<UUID> schoolIds, Set<UUID> classroomIds) {
    return new UserPrincipal(
        ACTOR_ID,
        "Test User",
        "test@ccw.test",
        role,
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        schoolIds,
        classroomIds,
        Set.of(),
        null);
  }

  private static Event event(EventType type, UUID schoolId, UUID classroomId) {
    Event e = new Event();
    e.setOrgId(UUID.randomUUID());
    e.setSchoolId(schoolId);
    e.setType(type);
    e.setTitle("Test event");
    e.setClassroomId(classroomId);
    e.setCreatedByUserId(UUID.randomUUID());
    return e;
  }

  private static Task task(UUID schoolId, UUID assigneeUserId) {
    Task t = new Task();
    t.setOrgId(UUID.randomUUID());
    t.setSchoolId(schoolId);
    t.setTitle("Test task");
    t.setAssigneeUserId(assigneeUserId);
    t.setCreatedByUserId(UUID.randomUUID());
    return t;
  }
}
