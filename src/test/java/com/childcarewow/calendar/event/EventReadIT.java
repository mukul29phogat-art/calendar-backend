package com.childcarewow.calendar.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for the read endpoints — {@code findById} and {@code findInWindow} — covering
 * the four-role × three-event-type visibility matrix per architecture spec § 6.4 / § 7.1.
 */
@SpringBootTest
class EventReadIT {

  // Seed UUIDs
  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID OLIVIA =
      UUID.fromString("33333333-0000-0000-0000-000000000001"); // ORG_ADMIN
  private static final UUID RAVI =
      UUID.fromString("33333333-0000-0000-0000-000000000002"); // SCHOOL_ADMIN Sunrise
  private static final UUID MAYA =
      UUID.fromString("33333333-0000-0000-0000-000000000004"); // STAFF Butterflies
  private static final UUID TOM =
      UUID.fromString("33333333-0000-0000-0000-000000000005"); // STAFF Caterpillars
  private static final UUID PRIYA =
      UUID.fromString("33333333-0000-0000-0000-000000000006"); // PARENT (Aanya)
  private static final UUID AANYA =
      UUID.fromString("55555555-0000-0000-0000-000000000001"); // student in Butterflies

  @Autowired EventService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  private UUID classroomEventId;
  private UUID schoolEventId;
  private UUID customEventId;

  @BeforeEach
  void seedEvents() {
    cleanup();
    // CLASSROOM event in Butterflies, inviteParents=true
    classroomEventId =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-read-classroom",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2026-08-01T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-08-01T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();

    // SCHOOL event at Sunrise, inviteParents=true
    schoolEventId =
        service
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-read-school",
                    null,
                    null,
                    OffsetDateTime.parse("2026-08-02T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-08-02T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();

    // CUSTOM event at Sunrise with Maya as attendee + Aanya as student, inviteParents=true
    customEventId =
        service
            .create(
                new CreateEventRequest(
                    EventType.CUSTOM,
                    SUNRISE,
                    "IT-read-custom",
                    null,
                    null,
                    OffsetDateTime.parse("2026-08-03T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-08-03T10:00:00-04:00"),
                    false,
                    null,
                    true,
                    List.of(MAYA),
                    List.of(AANYA)),
                admin())
            .id();
  }

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-read-%'");
  }

  // -- findById visibility ---------------------------------------------------

  @Test
  void orgAdminCanReadAnyEvent() {
    UserPrincipal admin = admin();
    assertThat(service.findById(classroomEventId, admin).id()).isEqualTo(classroomEventId);
    assertThat(service.findById(schoolEventId, admin).id()).isEqualTo(schoolEventId);
    assertThat(service.findById(customEventId, admin).id()).isEqualTo(customEventId);
  }

  @Test
  void schoolAdminSeesEventsInTheirSchools() {
    UserPrincipal raviAtSunrise =
        principal(RAVI, Role.SCHOOL_ADMIN, Set.of(SUNRISE), Set.of(), Set.of());
    assertThat(service.findById(classroomEventId, raviAtSunrise).id()).isEqualTo(classroomEventId);

    UserPrincipal stranger =
        principal(RAVI, Role.SCHOOL_ADMIN, Set.of(MAPLEWOOD), Set.of(), Set.of());
    assertThatThrownBy(() -> service.findById(classroomEventId, stranger))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void staffSeesClassroomEventsInOwnClassroomsOnly() {
    UserPrincipal mayaAtButterflies =
        principal(MAYA, Role.STAFF, Set.of(SUNRISE), Set.of(BUTTERFLIES), Set.of());
    assertThat(service.findById(classroomEventId, mayaAtButterflies).id())
        .isEqualTo(classroomEventId);

    UserPrincipal tomAtCaterpillars =
        principal(TOM, Role.STAFF, Set.of(SUNRISE), Set.of(CATERPILLARS), Set.of());
    assertThatThrownBy(() -> service.findById(classroomEventId, tomAtCaterpillars))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void staffSeesSchoolEventsAtOwnSchool() {
    UserPrincipal mayaAtSunrise =
        principal(MAYA, Role.STAFF, Set.of(SUNRISE), Set.of(BUTTERFLIES), Set.of());
    assertThat(service.findById(schoolEventId, mayaAtSunrise).id()).isEqualTo(schoolEventId);

    UserPrincipal mayaAtMaplewood =
        principal(MAYA, Role.STAFF, Set.of(MAPLEWOOD), Set.of(), Set.of());
    assertThatThrownBy(() -> service.findById(schoolEventId, mayaAtMaplewood))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void staffSeesCustomEventOnlyAsOrganizerOrAttendee() {
    UserPrincipal mayaAttendee = principal(MAYA, Role.STAFF, Set.of(SUNRISE), Set.of(), Set.of());
    assertThat(service.findById(customEventId, mayaAttendee).id()).isEqualTo(customEventId);

    UserPrincipal tomNotAttendee =
        principal(TOM, Role.STAFF, Set.of(SUNRISE), Set.of(CATERPILLARS), Set.of());
    assertThatThrownBy(() -> service.findById(customEventId, tomNotAttendee))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void parentSeesClassroomEventForChildsClassroom() {
    UserPrincipal priya = principal(PRIYA, Role.PARENT, Set.of(SUNRISE), Set.of(), Set.of(AANYA));
    assertThat(service.findById(classroomEventId, priya).id()).isEqualTo(classroomEventId);
  }

  @Test
  void parentDoesNotSeeClassroomEventForOtherClassroom() {
    // Aanya is in Butterflies; create a Caterpillars event and assert Priya can't see it.
    UUID otherClassroomEvent =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-read-other-classroom",
                    null,
                    CATERPILLARS,
                    OffsetDateTime.parse("2026-08-04T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-08-04T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();

    UserPrincipal priya = principal(PRIYA, Role.PARENT, Set.of(SUNRISE), Set.of(), Set.of(AANYA));
    assertThatThrownBy(() -> service.findById(otherClassroomEvent, priya))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void parentDoesNotSeeEventWithInviteParentsFalse() {
    UUID privateEvent =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-read-private",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2026-08-05T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-08-05T10:00:00-04:00"),
                    false,
                    null,
                    false), // inviteParents = false
                admin())
            .id();

    UserPrincipal priya = principal(PRIYA, Role.PARENT, Set.of(SUNRISE), Set.of(), Set.of(AANYA));
    assertThatThrownBy(() -> service.findById(privateEvent, priya))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void parentExcludedByUserIdCannotSee() {
    UUID exclusiveEvent =
        service
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-read-excl-parent",
                    null,
                    null,
                    OffsetDateTime.parse("2026-08-06T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-08-06T10:00:00-04:00"),
                    false,
                    null,
                    true,
                    null,
                    null,
                    List.of(PRIYA)),
                admin())
            .id();

    UserPrincipal priya = principal(PRIYA, Role.PARENT, Set.of(SUNRISE), Set.of(), Set.of(AANYA));
    assertThatThrownBy(() -> service.findById(exclusiveEvent, priya))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void parentExcludedByChildStudentIdCannotSee() {
    UUID exclusiveEvent =
        service
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-read-excl-student",
                    null,
                    null,
                    OffsetDateTime.parse("2026-08-07T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-08-07T10:00:00-04:00"),
                    false,
                    null,
                    true,
                    null,
                    null,
                    List.of(AANYA)),
                admin())
            .id();

    UserPrincipal priya = principal(PRIYA, Role.PARENT, Set.of(SUNRISE), Set.of(), Set.of(AANYA));
    assertThatThrownBy(() -> service.findById(exclusiveEvent, priya))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void parentSeesCustomEventWhenChildIsParticipant() {
    UserPrincipal priya = principal(PRIYA, Role.PARENT, Set.of(SUNRISE), Set.of(), Set.of(AANYA));
    assertThat(service.findById(customEventId, priya).id()).isEqualTo(customEventId);
  }

  // -- findInWindow ---------------------------------------------------------

  @Test
  void windowReturnsAllEventsForOrgAdmin() {
    UserPrincipal admin = admin();
    List<EventView> result =
        service.findInWindow(
            SUNRISE,
            OffsetDateTime.parse("2026-08-01T00:00:00Z"),
            OffsetDateTime.parse("2026-08-31T23:59:59Z"),
            null,
            admin);
    assertThat(result)
        .extracting(EventView::id)
        .containsExactlyInAnyOrder(classroomEventId, schoolEventId, customEventId);
  }

  @Test
  void windowFiltersByType() {
    UserPrincipal admin = admin();
    List<EventView> classroomOnly =
        service.findInWindow(
            SUNRISE,
            OffsetDateTime.parse("2026-08-01T00:00:00Z"),
            OffsetDateTime.parse("2026-08-31T23:59:59Z"),
            EventType.CLASSROOM,
            admin);
    assertThat(classroomOnly).extracting(EventView::id).containsExactly(classroomEventId);
  }

  @Test
  void windowAppliesParentVisibilityFilter() {
    UserPrincipal priya = principal(PRIYA, Role.PARENT, Set.of(SUNRISE), Set.of(), Set.of(AANYA));
    List<EventView> result =
        service.findInWindow(
            SUNRISE,
            OffsetDateTime.parse("2026-08-01T00:00:00Z"),
            OffsetDateTime.parse("2026-08-31T23:59:59Z"),
            null,
            priya);
    // All three: classroom (Aanya in Butterflies), school (Sunrise), custom (Aanya is student).
    assertThat(result)
        .extracting(EventView::id)
        .containsExactlyInAnyOrder(classroomEventId, schoolEventId, customEventId);
  }

  @Test
  void unknownIdReturns404() {
    UserPrincipal admin = admin();
    assertThatThrownBy(() -> service.findById(UUID.randomUUID(), admin))
        .isInstanceOf(NotFoundException.class);
  }

  // -- helpers ---------------------------------------------------------------

  private static UserPrincipal admin() {
    return principal(OLIVIA, Role.ORG_ADMIN, Set.of(SUNRISE, MAPLEWOOD), Set.of(), Set.of());
  }

  private static UserPrincipal principal(
      UUID id, Role role, Set<UUID> schools, Set<UUID> classrooms, Set<UUID> children) {
    return new UserPrincipal(
        id,
        "Test",
        "test@ccw.test",
        role,
        ORG,
        schools,
        classrooms,
        children,
        role == Role.ORG_ADMIN ? "Owner" : null);
  }
}
