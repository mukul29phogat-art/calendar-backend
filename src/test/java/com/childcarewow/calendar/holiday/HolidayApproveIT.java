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
class HolidayApproveIT {

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
            + "(SELECT id FROM holidays WHERE name LIKE 'IT-ha-%')");
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-ha-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-ha-%'");
  }

  @Test
  void approvingPendingFederalMarksApprovedAndRecomputesFlags() {
    LocalDate date = LocalDate.of(2027, 7, 4);

    // Pre-existing event on the date — created before approval, so it isn't blocked by the
    // pending federal row (Part 5.7 unapproved-doesn't-block).
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-ha-pre",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2027-07-04T10:00:00-04:00"),
                OffsetDateTime.parse("2027-07-04T11:00:00-04:00"),
                false,
                null,
                false),
            admin());

    UUID hid = insertPendingFederal(SUNRISE, date, "IT-ha-July4");

    HolidayView v = holidayService.approve(hid, admin());

    assertThat(v.approved()).isTrue();
    assertThat(v.approvedAt()).isNotNull();
    assertThat(v.approvedByUserId()).isEqualTo(OLIVIA);

    // Recompute fired → event got a HOLIDAY flag pointing at the now-approved holiday.
    Integer flagCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'EVENT' AND entity_id = ? AND conflicting_entity_id = ?",
            Integer.class,
            ev.id(),
            hid);
    assertThat(flagCount).isEqualTo(1);
  }

  @Test
  void approvingAlreadyApprovedIsIdempotentAndDoesNotMutate() {
    LocalDate date = LocalDate.of(2027, 12, 25);
    HolidayView original =
        holidayService.create(
            new CreateHolidayRequest(SUNRISE, date, "IT-ha-already-approved", null), admin());

    OffsetDateTime firstApprovedAt = original.approvedAt();
    HolidayView second = holidayService.approve(original.id(), admin());

    assertThat(second.approved()).isTrue();
    // approvedAt unchanged — we returned the existing row instead of overwriting.
    assertThat(second.approvedAt()).isEqualTo(firstApprovedAt);
  }

  @Test
  void approvingPendingFederalCollidingWithApprovedCustomRaisesDuplicate() {
    LocalDate date = LocalDate.of(2028, 1, 1);

    // Approved CUSTOM occupies the slot first.
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-ha-CustomNewYear", null), admin());

    // Pending federal lands on the same (school, date) — allowed by 6.1's duplicate rule because
    // pending rows can coexist. But approving it would collide.
    UUID pendingId = insertPendingFederal(SUNRISE, date, "IT-ha-FedNewYear");

    assertThatThrownBy(() -> holidayService.approve(pendingId, admin()))
        .isInstanceOf(DuplicateHolidayException.class);
  }

  @Test
  void approvingUnknownIdReturns404() {
    assertThatThrownBy(() -> holidayService.approve(UUID.randomUUID(), admin()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void approvingSoftDeletedHolidayReturns404() {
    LocalDate date = LocalDate.of(2028, 2, 14);
    UUID hid = insertPendingFederal(SUNRISE, date, "IT-ha-soft-deleted");
    calendarJdbc.update("UPDATE holidays SET deleted_at = now() WHERE id = ?", hid);

    assertThatThrownBy(() -> holidayService.approve(hid, admin()))
        .isInstanceOf(NotFoundException.class);
  }

  // -- helpers ---------------------------------------------------------------

  private UUID insertPendingFederal(UUID schoolId, LocalDate date, String name) {
    UUID id = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
            + "created_by_user_id) "
            + "VALUES (?, ?, ?, ?, ?, 'FEDERAL', false, ?)",
        id,
        ORG,
        schoolId,
        date,
        name,
        OLIVIA);
    return id;
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
