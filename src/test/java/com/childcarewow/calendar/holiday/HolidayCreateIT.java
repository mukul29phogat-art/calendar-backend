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
class HolidayCreateIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
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
            + "(SELECT id FROM holidays WHERE name LIKE 'IT-hc-%')");
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-hc-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-hc-%'");
  }

  @Test
  void happyPathCreatesApprovedCustomHoliday() {
    LocalDate date = LocalDate.of(2026, 11, 26);
    HolidayView v =
        holidayService.create(
            new CreateHolidayRequest(SUNRISE, date, "IT-hc-Thanksgiving", "School closed"),
            admin());

    assertThat(v.id()).isNotNull();
    assertThat(v.source()).isEqualTo(HolidaySource.CUSTOM);
    assertThat(v.approved()).isTrue();
    assertThat(v.approvedAt()).isNotNull();
    assertThat(v.approvedByUserId()).isEqualTo(OLIVIA);
    assertThat(v.date()).isEqualTo(date);
    assertThat(v.name()).isEqualTo("IT-hc-Thanksgiving");
    assertThat(v.notes()).isEqualTo("School closed");

    Holiday row =
        calendarJdbc.queryForObject(
            "SELECT * FROM holidays WHERE id = ?",
            (rs, n) -> {
              Holiday h = new Holiday();
              h.setId((UUID) rs.getObject("id"));
              h.setSchoolId((UUID) rs.getObject("school_id"));
              h.setApproved(rs.getBoolean("approved"));
              h.setSource(HolidaySource.valueOf(rs.getString("source")));
              return h;
            },
            v.id());
    assertThat(row.isApproved()).isTrue();
    assertThat(row.getSource()).isEqualTo(HolidaySource.CUSTOM);
  }

  @Test
  void duplicateApprovedOnSameSchoolAndDateReturns409() {
    LocalDate date = LocalDate.of(2026, 7, 4);
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-hc-July4-first", null), admin());

    assertThatThrownBy(
            () ->
                holidayService.create(
                    new CreateHolidayRequest(SUNRISE, date, "IT-hc-July4-dup", null), admin()))
        .isInstanceOf(DuplicateHolidayException.class);
  }

  @Test
  void retroactiveHolidayFlagsExistingEventOnThatDate() {
    LocalDate date = LocalDate.of(2026, 11, 11);

    // Pre-existing event on the date — created BEFORE the holiday so it isn't blocked.
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-hc-pre-event",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2026-11-11T10:00:00-05:00"),
                OffsetDateTime.parse("2026-11-11T11:00:00-05:00"),
                false,
                null,
                false),
            admin());

    HolidayView holiday =
        holidayService.create(
            new CreateHolidayRequest(SUNRISE, date, "IT-hc-Veterans-Day", null), admin());

    Integer flagCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'EVENT' AND entity_id = ? "
                + "AND conflict_type = 'HOLIDAY' AND conflicting_entity_id = ?",
            Integer.class,
            ev.id(),
            holiday.id());
    assertThat(flagCount).as("HOLIDAY flag inserted on the pre-existing event").isEqualTo(1);

    String message =
        calendarJdbc.queryForObject(
            "SELECT message FROM conflict_flags WHERE entity_id = ? AND conflicting_entity_id = ?",
            String.class,
            ev.id(),
            holiday.id());
    assertThat(message).contains("IT-hc-Veterans-Day");
  }

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES),
        Set.of(),
        "Owner");
  }
}
