package com.childcarewow.calendar.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.EventOnHolidayException;
import com.childcarewow.calendar.exception.InvalidTimeRangeException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.holiday.HolidaySource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the CLASSROOM event create flow. Uses the real platform DB (for the
 * timezone + classroom existence checks) and the real calendar DB (for the holidays + events
 * tables). Each test runs in a {@code @Transactional} that rolls back so we don't accumulate state
 * across runs.
 */
@SpringBootTest
class EventServiceIT {

  // Seed UUIDs (docker/platform-seed.sql)
  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-000000000099");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");

  @Autowired EventService service;
  @Autowired EventRepository eventRepo;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-%'");
    calendarJdbc.update("DELETE FROM conflict_flags WHERE message LIKE 'Overlaps with: IT-%'");
  }

  @Test
  void happyPathCreatesClassroomEvent() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-storytime",
            "Storytime with Maya",
            BUTTERFLIES,
            OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
            OffsetDateTime.parse("2026-09-15T14:30:00-04:00"),
            false,
            null, // organizer defaults to actor
            true);

    EventView view = service.create(req, admin(OLIVIA));

    assertThat(view.id()).isNotNull();
    assertThat(view.type()).isEqualTo(EventType.CLASSROOM);
    assertThat(view.title()).isEqualTo("IT-storytime");
    assertThat(view.classroomId()).isEqualTo(BUTTERFLIES);
    assertThat(view.organizerUserId()).isEqualTo(OLIVIA);
    assertThat(view.createdByUserId()).isEqualTo(OLIVIA);
    assertThat(view.inviteParents()).isTrue();
    assertThat(view.softFlags()).isEmpty();
    assertThat(view.createdAt()).isNotNull();

    // Persisted: round-trip via the repo
    Event reloaded = eventRepo.findById(view.id()).orElseThrow();
    assertThat(reloaded.getOrgId()).isEqualTo(ORG);
    assertThat(reloaded.getSchoolId()).isEqualTo(SUNRISE);
  }

  @Test
  void organizerDefaultsToActor() {
    CreateEventRequest req = baseRequest(OLIVIA, BUTTERFLIES, "IT-no-organizer");
    EventView view = service.create(req, admin(OLIVIA));
    assertThat(view.organizerUserId()).isEqualTo(OLIVIA);
  }

  @Test
  void explicitOrganizerOverridesActor() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-explicit-organizer",
            null,
            BUTTERFLIES,
            OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
            OffsetDateTime.parse("2026-09-15T16:00:00-04:00"),
            false,
            MAYA,
            false);
    EventView view = service.create(req, admin(OLIVIA));
    assertThat(view.organizerUserId()).isEqualTo(MAYA);
    assertThat(view.createdByUserId()).isEqualTo(OLIVIA);
  }

  @Test
  void rejectsEndBeforeStart() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-bad-time",
            null,
            BUTTERFLIES,
            OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
            OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
            false,
            null,
            false);
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(InvalidTimeRangeException.class);
  }

  @Test
  void rejectsEqualStartAndEnd() {
    OffsetDateTime t = OffsetDateTime.parse("2026-09-15T15:00:00-04:00");
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-zero-duration",
            null,
            BUTTERFLIES,
            t,
            t,
            false,
            null,
            false);
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(InvalidTimeRangeException.class);
  }

  @Test
  void rejectsClassroomEventWithoutClassroomId() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-missing-classroom",
            null,
            null,
            OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
            OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
            false,
            null,
            false);
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("classroomId");
  }

  @Test
  void rejectsSchoolTypeUntil5_3() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.SCHOOL,
            SUNRISE,
            "IT-school",
            null,
            null,
            OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
            OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
            false,
            null,
            false);
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("SCHOOL is not yet supported");
  }

  // -- CUSTOM type ------------------------------------------------------------

  @Test
  void customEventWithAttendeesAndStudentsWritesJoinTables() {
    UUID maya = UUID.fromString("33333333-0000-0000-0000-000000000004");
    UUID tom = UUID.fromString("33333333-0000-0000-0000-000000000005");
    UUID aanya = UUID.fromString("55555555-0000-0000-0000-000000000001");
    UUID jordan = UUID.fromString("55555555-0000-0000-0000-000000000002");

    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CUSTOM,
            SUNRISE,
            "IT-field-trip",
            "Field trip to the park",
            null, // CUSTOM doesn't use classroomId
            OffsetDateTime.parse("2026-09-20T10:00:00-04:00"),
            OffsetDateTime.parse("2026-09-20T12:00:00-04:00"),
            false,
            null,
            true,
            java.util.List.of(maya, tom),
            java.util.List.of(aanya, jordan));

    EventView view = service.create(req, admin(OLIVIA));

    assertThat(view.id()).isNotNull();
    assertThat(view.type()).isEqualTo(EventType.CUSTOM);
    assertThat(view.attendeeUserIds()).containsExactlyInAnyOrder(maya, tom);
    assertThat(view.studentIds()).containsExactlyInAnyOrder(aanya, jordan);

    // Join tables actually populated
    Integer attendees =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM event_attendees WHERE event_id = ?", Integer.class, view.id());
    Integer students =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM event_students WHERE event_id = ?", Integer.class, view.id());
    assertThat(attendees).isEqualTo(2);
    assertThat(students).isEqualTo(2);
  }

  @Test
  void customEventWithEmptyArraysWritesNoJoinRows() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CUSTOM,
            SUNRISE,
            "IT-empty-custom",
            null,
            null,
            OffsetDateTime.parse("2026-09-21T10:00:00-04:00"),
            OffsetDateTime.parse("2026-09-21T11:00:00-04:00"),
            false,
            null,
            false);

    EventView view = service.create(req, admin(OLIVIA));
    assertThat(view.attendeeUserIds()).isEmpty();
    assertThat(view.studentIds()).isEmpty();

    Integer attendees =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM event_attendees WHERE event_id = ?", Integer.class, view.id());
    Integer students =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM event_students WHERE event_id = ?", Integer.class, view.id());
    assertThat(attendees).isZero();
    assertThat(students).isZero();
  }

  @Test
  void customEventRejectsUnknownAttendee() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CUSTOM,
            SUNRISE,
            "IT-bad-attendee",
            null,
            null,
            OffsetDateTime.parse("2026-09-22T10:00:00-04:00"),
            OffsetDateTime.parse("2026-09-22T11:00:00-04:00"),
            false,
            null,
            false,
            java.util.List.of(UNKNOWN),
            java.util.List.of());
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void customEventRejectsUnknownStudent() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CUSTOM,
            SUNRISE,
            "IT-bad-student",
            null,
            null,
            OffsetDateTime.parse("2026-09-22T10:00:00-04:00"),
            OffsetDateTime.parse("2026-09-22T11:00:00-04:00"),
            false,
            null,
            false,
            java.util.List.of(),
            java.util.List.of(UNKNOWN));
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void rejectsUnknownClassroom() {
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-bad-classroom",
            null,
            UNKNOWN,
            OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
            OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
            false,
            null,
            false);
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void rejectsClassroomBelongingToDifferentSchool() {
    // Butterflies is in Sunrise; targeting it from Maplewood should fail.
    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CLASSROOM,
            MAPLEWOOD,
            "IT-cross-school",
            null,
            BUTTERFLIES,
            OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
            OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
            false,
            null,
            false);
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  @Transactional
  void rejectsCreationOnApprovedHoliday() {
    LocalDate holiday = LocalDate.of(2026, 11, 26); // Thanksgiving (sample)
    insertApprovedHoliday(SUNRISE, holiday, "IT-thxgive");

    CreateEventRequest req =
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-on-holiday",
            null,
            BUTTERFLIES,
            // 2:00 PM EST -> still falls on the same school-local date.
            OffsetDateTime.parse("2026-11-26T14:00:00-05:00"),
            OffsetDateTime.parse("2026-11-26T15:00:00-05:00"),
            false,
            null,
            false);
    assertThatThrownBy(() -> service.create(req, admin(OLIVIA)))
        .isInstanceOf(EventOnHolidayException.class);
  }

  @Test
  void overlappingEventsCreateBidirectionalDoubleBookingFlag() {
    // First event
    EventView firstView =
        service.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-first",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2026-10-01T14:00:00-04:00"),
                OffsetDateTime.parse("2026-10-01T15:00:00-04:00"),
                false,
                MAYA,
                false),
            admin(OLIVIA));

    // Overlapping event in same classroom, same time window
    EventView secondView =
        service.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-second",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2026-10-01T14:30:00-04:00"),
                OffsetDateTime.parse("2026-10-01T15:30:00-04:00"),
                false,
                MAYA,
                false),
            admin(OLIVIA));

    // Second event's response must include a DOUBLE_BOOKING flag pointing at the first.
    assertThat(secondView.softFlags()).hasSize(1);
    assertThat(secondView.softFlags().get(0).conflictType()).isEqualTo("DOUBLE_BOOKING");
    assertThat(secondView.softFlags().get(0).conflictingEntityId()).isEqualTo(firstView.id());
  }

  // -- helpers ----------------------------------------------------------------

  private static UserPrincipal admin(UUID id) {
    return new UserPrincipal(
        id,
        "Test Admin",
        "admin@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE),
        Set.of(),
        Set.of(),
        "Owner");
  }

  private static CreateEventRequest baseRequest(
      UUID actorAsOrganizer, UUID classroomId, String title) {
    return new CreateEventRequest(
        EventType.CLASSROOM,
        SUNRISE,
        title,
        null,
        classroomId,
        OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
        OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
        false,
        null,
        false);
  }

  private void insertApprovedHoliday(UUID schoolId, LocalDate date, String name) {
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "approved_at, approved_by_user_id, created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, true, now(), ?, ?)",
        UUID.randomUUID(),
        ORG,
        schoolId,
        date,
        name,
        HolidaySource.CUSTOM.name(),
        OLIVIA,
        OLIVIA);
  }
}
