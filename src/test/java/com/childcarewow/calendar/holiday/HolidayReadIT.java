package com.childcarewow.calendar.holiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.ForbiddenException;
import com.childcarewow.calendar.exception.NotFoundException;
import java.time.LocalDate;
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

@SpringBootTest
class HolidayReadIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OLIVIA =
      UUID.fromString("33333333-0000-0000-0000-000000000001"); // ORG_ADMIN
  private static final UUID RAVI =
      UUID.fromString("33333333-0000-0000-0000-000000000002"); // SCHOOL_ADMIN @ Sunrise
  private static final UUID PRIYA =
      UUID.fromString("33333333-0000-0000-0000-000000000006"); // PARENT @ Sunrise

  @Autowired HolidayService holidayService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  // Holiday IDs we own and clean up.
  private UUID approvedCustomId;
  private UUID approvedFederalId;
  private UUID pendingFederalId;
  private UUID maplewoodApprovedId;

  @BeforeEach
  void seed() {
    approvedCustomId = UUID.randomUUID();
    approvedFederalId = UUID.randomUUID();
    pendingFederalId = UUID.randomUUID();
    maplewoodApprovedId = UUID.randomUUID();

    // Approved CUSTOM at Sunrise on 2027-01-15
    insertHoliday(
        approvedCustomId, SUNRISE, LocalDate.of(2027, 1, 15), "IT-hr-CustomDay", "CUSTOM", true);
    // Approved FEDERAL at Sunrise on 2027-07-04
    insertHoliday(
        approvedFederalId, SUNRISE, LocalDate.of(2027, 7, 4), "IT-hr-July4", "FEDERAL", true);
    // PENDING FEDERAL at Sunrise on 2027-12-25
    insertHoliday(
        pendingFederalId, SUNRISE, LocalDate.of(2027, 12, 25), "IT-hr-Pending", "FEDERAL", false);
    // Approved CUSTOM at Maplewood — used to verify cross-school visibility
    insertHoliday(
        maplewoodApprovedId,
        MAPLEWOOD,
        LocalDate.of(2027, 5, 26),
        "IT-hr-Maplewood",
        "CUSTOM",
        true);
  }

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-hr-%'");
  }

  // -- list ------------------------------------------------------------------

  @Test
  void orgAdminSeesAllHolidaysIncludingPendingFederal() {
    List<HolidayView> rows = holidayService.findInSchool(SUNRISE, null, null, orgAdmin());
    assertThat(rows).hasSize(3);
    assertThat(rows)
        .extracting(HolidayView::id)
        .containsExactlyInAnyOrder(approvedCustomId, approvedFederalId, pendingFederalId);
  }

  @Test
  void schoolAdminSeesPendingFederalAtTheirSchool() {
    List<HolidayView> rows = holidayService.findInSchool(SUNRISE, null, null, schoolAdmin());
    assertThat(rows).extracting(HolidayView::id).contains(pendingFederalId);
  }

  @Test
  void parentDoesNotSeePendingFederalEvenWithoutFilter() {
    List<HolidayView> rows = holidayService.findInSchool(SUNRISE, null, null, parent());
    assertThat(rows).extracting(HolidayView::id).contains(approvedCustomId, approvedFederalId);
    assertThat(rows).extracting(HolidayView::id).doesNotContain(pendingFederalId);
  }

  @Test
  void parentApprovedFalseFilterIsIgnoredAndReturnsApprovedOnly() {
    // PARENT explicitly asks for approved=false. The service clamps to approved=true
    // unconditionally.
    List<HolidayView> rows = holidayService.findInSchool(SUNRISE, Boolean.FALSE, null, parent());
    assertThat(rows).extracting(HolidayView::approved).allMatch(Boolean::booleanValue);
    assertThat(rows).extracting(HolidayView::id).doesNotContain(pendingFederalId);
  }

  @Test
  void approvedTrueFilterExcludesPendingFromAdminList() {
    List<HolidayView> rows =
        holidayService.findInSchool(SUNRISE, Boolean.TRUE, null, schoolAdmin());
    assertThat(rows).extracting(HolidayView::id).doesNotContain(pendingFederalId);
    assertThat(rows).extracting(HolidayView::id).contains(approvedCustomId, approvedFederalId);
  }

  @Test
  void sourceFederalFilterReturnsFederalOnly() {
    List<HolidayView> rows =
        holidayService.findInSchool(SUNRISE, null, HolidaySource.FEDERAL, orgAdmin());
    assertThat(rows).extracting(HolidayView::source).allMatch(s -> s == HolidaySource.FEDERAL);
  }

  @Test
  void parentAtOtherSchoolGetsForbidden() {
    // Priya is registered at Sunrise — asking for Maplewood should be forbidden, not silently
    // empty.
    assertThatThrownBy(() -> holidayService.findInSchool(MAPLEWOOD, null, null, parent()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void schoolAdminAtOtherSchoolGetsForbidden() {
    assertThatThrownBy(() -> holidayService.findInSchool(MAPLEWOOD, null, null, schoolAdmin()))
        .isInstanceOf(ForbiddenException.class);
  }

  // -- findById --------------------------------------------------------------

  @Test
  void parentFindByIdHidesPendingFederalAs404() {
    assertThatThrownBy(() -> holidayService.findById(pendingFederalId, parent()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void parentFindByIdSeesApproved() {
    HolidayView v = holidayService.findById(approvedCustomId, parent());
    assertThat(v.approved()).isTrue();
  }

  @Test
  void schoolAdminFindByIdSeesPending() {
    HolidayView v = holidayService.findById(pendingFederalId, schoolAdmin());
    assertThat(v.approved()).isFalse();
    assertThat(v.source()).isEqualTo(HolidaySource.FEDERAL);
  }

  @Test
  void unknownHolidayIdReturns404() {
    assertThatThrownBy(() -> holidayService.findById(UUID.randomUUID(), orgAdmin()))
        .isInstanceOf(NotFoundException.class);
  }

  // -- helpers ---------------------------------------------------------------

  private static UserPrincipal orgAdmin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE, MAPLEWOOD),
        Set.of(),
        Set.of(),
        "Owner");
  }

  private static UserPrincipal schoolAdmin() {
    return new UserPrincipal(
        RAVI,
        "Ravi",
        "ravi@ccw.test",
        Role.SCHOOL_ADMIN,
        ORG,
        Set.of(SUNRISE),
        Set.of(),
        Set.of(),
        "Sunrise Director");
  }

  private static UserPrincipal parent() {
    return new UserPrincipal(
        PRIYA,
        "Priya",
        "priya@parent.test",
        Role.PARENT,
        ORG,
        Set.of(SUNRISE),
        Set.of(),
        Set.of(),
        null);
  }

  private void insertHoliday(
      UUID id, UUID schoolId, LocalDate date, String name, String source, boolean approved) {
    if (approved) {
      calendarJdbc.update(
          "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
              + "approved_at, approved_by_user_id, created_by_user_id) "
              + "VALUES (?, ?, ?, ?, ?, ?, true, now(), ?, ?)",
          id,
          ORG,
          schoolId,
          date,
          name,
          source,
          OLIVIA,
          OLIVIA);
    } else {
      calendarJdbc.update(
          "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
              + "created_by_user_id) "
              + "VALUES (?, ?, ?, ?, ?, ?, false, ?)",
          id,
          ORG,
          schoolId,
          date,
          name,
          source,
          OLIVIA);
    }
  }
}
