package com.childcarewow.calendar.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.EventOnHolidayException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.holiday.HolidaySource;
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
 * Integration tests for {@link EventService#update}. Covers same-date edits skipping the holiday
 * check, date-change holiday rejection, soft-flag recompute, and the join-table clear-and-rebuild
 * pattern.
 */
@SpringBootTest
class EventUpdateIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");
  private static final UUID AANYA = UUID.fromString("55555555-0000-0000-0000-000000000001");

  @Autowired EventService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  private UUID eventId;

  @BeforeEach
  void seedEvent() {
    cleanup();
    eventId =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-update-base",
                    "original",
                    BUTTERFLIES,
                    OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
                    OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
                    false,
                    null,
                    false),
                admin())
            .id();
  }

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-update-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-update-%'");
  }

  @Test
  void updatesTitleAndDescriptionInPlace() {
    EventView updated =
        service.update(
            eventId,
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-update-renamed",
                "after edit",
                BUTTERFLIES,
                OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
                OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
                false,
                null,
                true), // also flips inviteParents
            admin());

    assertThat(updated.id()).isEqualTo(eventId);
    assertThat(updated.title()).isEqualTo("IT-update-renamed");
    assertThat(updated.description()).isEqualTo("after edit");
    assertThat(updated.inviteParents()).isTrue();
  }

  @Test
  void sameDateEditDoesNotRecheckHoliday() {
    // Add a holiday for the *current* date AFTER creation. Same-date edits should skip the
    // holiday check (per validateEventInput) — otherwise this update would throw.
    insertApprovedHoliday(SUNRISE, java.time.LocalDate.of(2026, 9, 15), "IT-update-on-same-day");

    EventView updated =
        service.update(
            eventId,
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-update-renamed",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
                OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
                false,
                null,
                false),
            admin());
    assertThat(updated.title()).isEqualTo("IT-update-renamed");
  }

  @Test
  void dateChangeToHolidayRejected() {
    insertApprovedHoliday(SUNRISE, java.time.LocalDate.of(2026, 11, 26), "IT-update-thxgive");

    assertThatThrownBy(
            () ->
                service.update(
                    eventId,
                    new CreateEventRequest(
                        EventType.CLASSROOM,
                        SUNRISE,
                        "IT-update-moved",
                        null,
                        BUTTERFLIES,
                        OffsetDateTime.parse("2026-11-26T14:00:00-05:00"),
                        OffsetDateTime.parse("2026-11-26T15:00:00-05:00"),
                        false,
                        null,
                        false),
                    admin()))
        .isInstanceOf(EventOnHolidayException.class);
  }

  @Test
  void timeChangeRecomputesSoftFlags() {
    // Create a second event in the same classroom that DOESN'T overlap our base.
    UUID otherId =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-update-other",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2026-09-15T16:00:00-04:00"),
                    OffsetDateTime.parse("2026-09-15T17:00:00-04:00"),
                    false,
                    null,
                    false),
                admin())
            .id();

    // Now move our base event so it overlaps the second one. Recompute should produce a flag.
    EventView updated =
        service.update(
            eventId,
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-update-base",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2026-09-15T16:30:00-04:00"),
                OffsetDateTime.parse("2026-09-15T17:30:00-04:00"),
                false,
                null,
                false),
            admin());

    assertThat(updated.softFlags())
        .extracting(EventView.SoftFlagView::conflictingEntityId)
        .contains(otherId);
  }

  @Test
  void typeFlipFromCustomToClassroomClearsAttendeesAndStudents() {
    // Create a CUSTOM event with attendees + students.
    UUID customId =
        service
            .create(
                new CreateEventRequest(
                    EventType.CUSTOM,
                    SUNRISE,
                    "IT-update-custom",
                    null,
                    null,
                    OffsetDateTime.parse("2026-09-16T10:00:00-04:00"),
                    OffsetDateTime.parse("2026-09-16T11:00:00-04:00"),
                    false,
                    null,
                    false,
                    List.of(MAYA, TOM),
                    List.of(AANYA)),
                admin())
            .id();

    Integer beforeAttendees =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM event_attendees WHERE event_id = ?", Integer.class, customId);
    assertThat(beforeAttendees).isEqualTo(2);

    // Flip to CLASSROOM — join rows should be cleared.
    service.update(
        customId,
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-update-custom",
            null,
            BUTTERFLIES,
            OffsetDateTime.parse("2026-09-16T10:00:00-04:00"),
            OffsetDateTime.parse("2026-09-16T11:00:00-04:00"),
            false,
            null,
            false),
        admin());

    Integer afterAttendees =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM event_attendees WHERE event_id = ?", Integer.class, customId);
    Integer afterStudents =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM event_students WHERE event_id = ?", Integer.class, customId);
    assertThat(afterAttendees).isZero();
    assertThat(afterStudents).isZero();
  }

  @Test
  void unknownIdReturns404() {
    assertThatThrownBy(
            () ->
                service.update(
                    UUID.randomUUID(),
                    new CreateEventRequest(
                        EventType.CLASSROOM,
                        SUNRISE,
                        "IT-update-ghost",
                        null,
                        BUTTERFLIES,
                        OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
                        OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
                        false,
                        null,
                        false),
                    admin()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void loadForPolicyCheckReturnsEntityForKnownId() {
    Event loaded = service.loadForPolicyCheck(eventId);
    assertThat(loaded.getId()).isEqualTo(eventId);
    assertThat(loaded.getTitle()).isEqualTo("IT-update-base");
  }

  // -- helpers ----------------------------------------------------------------

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES, CATERPILLARS),
        Set.of(),
        "Owner");
  }

  private void insertApprovedHoliday(UUID schoolId, java.time.LocalDate date, String name) {
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
