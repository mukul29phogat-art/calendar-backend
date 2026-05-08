package com.childcarewow.calendar.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.EventOnHolidayException;
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

/**
 * Verifies the holiday-blocks-creation enforcement end-to-end (architecture spec § 5 / § 9). Uses a
 * SQL fixture for the holiday because the holidays controller is Part 6.1 — not yet built.
 */
@SpringBootTest
class HolidayBlockIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID SUNBEAMS = UUID.fromString("44444444-0000-0000-0000-000000000003");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");

  @Autowired EventService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-hb-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-hb-%'");
  }

  @Test
  void approvedHolidayBlocksCreationWithExceptionCarryingName() {
    LocalDate xmas = LocalDate.of(2026, 12, 25);
    insertApprovedHoliday(SUNRISE, xmas, "IT-hb-Christmas");

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateEventRequest(
                        EventType.CLASSROOM,
                        SUNRISE,
                        "IT-hb-on-xmas",
                        null,
                        BUTTERFLIES,
                        OffsetDateTime.parse("2026-12-25T14:00:00-05:00"),
                        OffsetDateTime.parse("2026-12-25T15:00:00-05:00"),
                        false,
                        null,
                        false),
                    admin()))
        .isInstanceOf(EventOnHolidayException.class)
        .hasMessageContaining("IT-hb-Christmas");
  }

  @Test
  void sameDateAtDifferentSchoolIsAllowed() {
    LocalDate xmas = LocalDate.of(2026, 12, 25);
    insertApprovedHoliday(SUNRISE, xmas, "IT-hb-xmas-sunrise");

    // Same date but at Maplewood — no holiday on that (school, date) → succeeds.
    EventView view =
        service.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                MAPLEWOOD,
                "IT-hb-at-maplewood",
                null,
                SUNBEAMS,
                OffsetDateTime.parse("2026-12-25T09:00:00-06:00"),
                OffsetDateTime.parse("2026-12-25T10:00:00-06:00"),
                false,
                null,
                false),
            admin());
    assertThat(view.id()).isNotNull();
  }

  @Test
  void unapprovedHolidayDoesNotBlock() {
    LocalDate xmas = LocalDate.of(2026, 12, 25);
    // Insert with approved=false → must NOT block (federal-pending state).
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, false, ?)",
        UUID.randomUUID(),
        ORG,
        SUNRISE,
        xmas,
        "IT-hb-pending-fed",
        HolidaySource.FEDERAL.name(),
        OLIVIA);

    EventView view =
        service.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-hb-bypass-pending",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2026-12-25T14:00:00-05:00"),
                OffsetDateTime.parse("2026-12-25T15:00:00-05:00"),
                false,
                null,
                false),
            admin());
    assertThat(view.id()).isNotNull();
  }

  @Test
  void softDeletedHolidayDoesNotBlock() {
    LocalDate xmas = LocalDate.of(2026, 12, 25);
    UUID hid = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "approved_at, approved_by_user_id, created_by_user_id, deleted_at) "
            + "VALUES (?, ?, ?, ?, ?, 'CUSTOM', true, now(), ?, ?, now())",
        hid,
        ORG,
        SUNRISE,
        xmas,
        "IT-hb-soft-deleted",
        OLIVIA,
        OLIVIA);

    EventView view =
        service.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-hb-bypass-deleted",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2026-12-25T14:00:00-05:00"),
                OffsetDateTime.parse("2026-12-25T15:00:00-05:00"),
                false,
                null,
                false),
            admin());
    assertThat(view.id()).isNotNull();
  }

  // -- helpers ----------------------------------------------------------------

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE, MAPLEWOOD),
        Set.of(BUTTERFLIES, SUNBEAMS),
        Set.of(),
        "Owner");
  }

  private void insertApprovedHoliday(UUID schoolId, LocalDate date, String name) {
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "approved_at, approved_by_user_id, created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, 'CUSTOM', true, now(), ?, ?)",
        UUID.randomUUID(),
        ORG,
        schoolId,
        date,
        name,
        OLIVIA,
        OLIVIA);
  }
}
