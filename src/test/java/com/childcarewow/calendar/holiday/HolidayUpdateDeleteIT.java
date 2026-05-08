package com.childcarewow.calendar.holiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.CreateEventRequest;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.event.EventView;
import com.childcarewow.calendar.exception.DuplicateHolidayException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.exception.ValidationException;
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

@SpringBootTest
class HolidayUpdateDeleteIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");

  @Autowired HolidayService holidayService;
  @Autowired EventService eventService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM conflict_flags WHERE conflicting_entity_id IN "
            + "(SELECT id FROM holidays WHERE name LIKE 'IT-hud-%')");
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-hud-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-hud-%'");
  }

  // -- update ----------------------------------------------------------------

  @Test
  void renameAndNotesPreserveDateAndRecomputesFlags() {
    UUID hid = createHoliday(SUNRISE, LocalDate.of(2027, 4, 1), "IT-hud-original", null).id();

    HolidayView v =
        holidayService.update(
            hid,
            new CreateHolidayRequest(
                SUNRISE, LocalDate.of(2027, 4, 1), "IT-hud-renamed", "More notes"),
            admin());

    assertThat(v.name()).isEqualTo("IT-hud-renamed");
    assertThat(v.notes()).isEqualTo("More notes");
    assertThat(v.date()).isEqualTo(LocalDate.of(2027, 4, 1));
  }

  @Test
  void editingDateMovesFlagFromOldDateToNewDate() {
    LocalDate oldDate = LocalDate.of(2027, 5, 1);
    LocalDate newDate = LocalDate.of(2027, 6, 1);

    // Two events: one on the old date (loses flag after move), one on the new date (gains flag).
    EventView eOld =
        eventService.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-hud-on-old",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2027-05-01T10:00:00-04:00"),
                OffsetDateTime.parse("2027-05-01T11:00:00-04:00"),
                false,
                null,
                false),
            admin());
    EventView eNew =
        eventService.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-hud-on-new",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2027-06-01T10:00:00-04:00"),
                OffsetDateTime.parse("2027-06-01T11:00:00-04:00"),
                false,
                null,
                false),
            admin());

    UUID hid = createHoliday(SUNRISE, oldDate, "IT-hud-mover", null).id();

    // Initially flag is on eOld.
    assertThat(holidayFlagOn(eOld.id(), hid)).isEqualTo(1);
    assertThat(holidayFlagOn(eNew.id(), hid)).isZero();

    // Move the holiday.
    holidayService.update(
        hid, new CreateHolidayRequest(SUNRISE, newDate, "IT-hud-mover", null), admin());

    // After: flag has moved.
    assertThat(holidayFlagOn(eOld.id(), hid)).isZero();
    assertThat(holidayFlagOn(eNew.id(), hid)).isEqualTo(1);
  }

  @Test
  void editingNameUpdatesFlagMessage() {
    LocalDate date = LocalDate.of(2027, 7, 14);
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-hud-pre-edit",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2027-07-14T10:00:00-04:00"),
                OffsetDateTime.parse("2027-07-14T11:00:00-04:00"),
                false,
                null,
                false),
            admin());

    UUID hid = createHoliday(SUNRISE, date, "IT-hud-old-name", null).id();

    // Rename → recompute should rewrite the message.
    holidayService.update(
        hid, new CreateHolidayRequest(SUNRISE, date, "IT-hud-new-name", null), admin());

    String message =
        calendarJdbc.queryForObject(
            "SELECT message FROM conflict_flags WHERE entity_id = ? AND conflicting_entity_id = ?",
            String.class,
            ev.id(),
            hid);
    assertThat(message).contains("IT-hud-new-name").doesNotContain("IT-hud-old-name");
  }

  @Test
  void schoolIdIsImmutableOnEdit() {
    UUID hid = createHoliday(SUNRISE, LocalDate.of(2027, 8, 1), "IT-hud-immov", null).id();
    assertThatThrownBy(
            () ->
                holidayService.update(
                    hid,
                    new CreateHolidayRequest(
                        MAPLEWOOD, LocalDate.of(2027, 8, 1), "IT-hud-immov", null),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("schoolId");
  }

  @Test
  void editingDateOntoExistingApprovedHolidayThrowsDuplicate() {
    LocalDate occupied = LocalDate.of(2027, 9, 1);
    LocalDate sourceDate = LocalDate.of(2027, 9, 5);

    createHoliday(SUNRISE, occupied, "IT-hud-occupant", null);
    UUID hid = createHoliday(SUNRISE, sourceDate, "IT-hud-mover-2", null).id();

    assertThatThrownBy(
            () ->
                holidayService.update(
                    hid,
                    new CreateHolidayRequest(SUNRISE, occupied, "IT-hud-mover-2", null),
                    admin()))
        .isInstanceOf(DuplicateHolidayException.class);
  }

  @Test
  void updateUnknownIdReturns404() {
    assertThatThrownBy(
            () ->
                holidayService.update(
                    UUID.randomUUID(),
                    new CreateHolidayRequest(SUNRISE, LocalDate.of(2027, 10, 1), "IT-hud-x", null),
                    admin()))
        .isInstanceOf(NotFoundException.class);
  }

  // -- delete ----------------------------------------------------------------

  @Test
  void deleteSoftDeletesAndClearsFlagsOnAffectedEvents() {
    LocalDate date = LocalDate.of(2027, 11, 1);
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-hud-pre-delete",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2027-11-01T10:00:00-05:00"),
                OffsetDateTime.parse("2027-11-01T11:00:00-05:00"),
                false,
                null,
                false),
            admin());

    UUID hid = createHoliday(SUNRISE, date, "IT-hud-deletable", null).id();
    assertThat(holidayFlagOn(ev.id(), hid)).isEqualTo(1);

    holidayService.delete(hid);

    assertThat(holidayFlagOn(ev.id(), hid)).isZero();

    // Soft-delete, not hard-delete.
    OffsetDateTime deletedAt =
        calendarJdbc.queryForObject(
            "SELECT deleted_at FROM holidays WHERE id = ?", OffsetDateTime.class, hid);
    assertThat(deletedAt).isNotNull();
  }

  @Test
  void deleteUnknownIdReturns404() {
    assertThatThrownBy(() -> holidayService.delete(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void doubleDeleteReturns404() {
    UUID hid = createHoliday(SUNRISE, LocalDate.of(2027, 12, 1), "IT-hud-twice", null).id();
    holidayService.delete(hid);
    assertThatThrownBy(() -> holidayService.delete(hid)).isInstanceOf(NotFoundException.class);
  }

  // -- helpers ---------------------------------------------------------------

  private HolidayView createHoliday(UUID schoolId, LocalDate date, String name, String notes) {
    return holidayService.create(new CreateHolidayRequest(schoolId, date, name, notes), admin());
  }

  private Integer holidayFlagOn(UUID eventId, UUID holidayId) {
    return calendarJdbc.queryForObject(
        "SELECT COUNT(*) FROM conflict_flags "
            + "WHERE entity_type = 'EVENT' AND entity_id = ? "
            + "AND conflict_type = 'HOLIDAY' AND conflicting_entity_id = ?",
        Integer.class,
        eventId,
        holidayId);
  }

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE, MAPLEWOOD),
        Set.of(BUTTERFLIES),
        Set.of(),
        "Owner");
  }
}
