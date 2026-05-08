package com.childcarewow.calendar.holiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.ForbiddenException;
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

/**
 * Verifies the {@code GET /api/v1/holidays?source=FEDERAL&approved=false} query path used by the
 * federal-approval panel (architecture spec § 6.3.5). Per playbook line 2871, the filter mechanics
 * already work — this IT pins the specific query path so a future repo refactor can't accidentally
 * regress it.
 *
 * <p>Also covers the playbook common-failure-point line 2885: soft-deleted federals must not leak
 * into the list.
 */
@SpringBootTest
class PendingFederalListIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID RAVI = UUID.fromString("33333333-0000-0000-0000-000000000002");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");

  @Autowired HolidayService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  private UUID pendingFederalSunrise;
  private UUID pendingFederalSunrise2;
  private UUID approvedFederalSunrise;
  private UUID pendingCustomSunrise;
  private UUID pendingFederalMaplewood;
  private UUID softDeletedPendingFederalSunrise;

  @BeforeEach
  void seed() {
    pendingFederalSunrise =
        insert(SUNRISE, LocalDate.of(2028, 1, 1), "IT-pf-NewYear", "FEDERAL", false, false);
    pendingFederalSunrise2 =
        insert(SUNRISE, LocalDate.of(2028, 7, 4), "IT-pf-July4", "FEDERAL", false, false);
    approvedFederalSunrise =
        insert(SUNRISE, LocalDate.of(2028, 12, 25), "IT-pf-Xmas", "FEDERAL", true, false);
    // CUSTOM with approved=false would never happen in practice (CUSTOM auto-approves), but covers
    // the source filter
    pendingCustomSunrise =
        insert(SUNRISE, LocalDate.of(2028, 6, 1), "IT-pf-CustomPending", "CUSTOM", false, false);
    pendingFederalMaplewood =
        insert(MAPLEWOOD, LocalDate.of(2028, 11, 11), "IT-pf-Veterans", "FEDERAL", false, false);
    softDeletedPendingFederalSunrise =
        insert(SUNRISE, LocalDate.of(2028, 9, 6), "IT-pf-Labor", "FEDERAL", false, true);
  }

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-pf-%'");
  }

  @Test
  void orgAdminPendingFederalsAtSunrise() {
    List<HolidayView> rows =
        service.findInSchool(SUNRISE, Boolean.FALSE, HolidaySource.FEDERAL, orgAdmin());

    assertThat(rows)
        .extracting(HolidayView::id)
        .containsExactlyInAnyOrder(pendingFederalSunrise, pendingFederalSunrise2);
    // No approved federal, no pending CUSTOM, no soft-deleted federal.
    assertThat(rows)
        .extracting(HolidayView::id)
        .doesNotContain(
            approvedFederalSunrise, pendingCustomSunrise, softDeletedPendingFederalSunrise);
  }

  @Test
  void orgAdminCanQueryPendingFederalsAtMaplewoodToo() {
    List<HolidayView> rows =
        service.findInSchool(MAPLEWOOD, Boolean.FALSE, HolidaySource.FEDERAL, orgAdmin());
    assertThat(rows).extracting(HolidayView::id).containsExactly(pendingFederalMaplewood);
  }

  @Test
  void schoolAdminAtSunriseCannotListPendingFederalsAtMaplewood() {
    assertThatThrownBy(
            () ->
                service.findInSchool(
                    MAPLEWOOD, Boolean.FALSE, HolidaySource.FEDERAL, schoolAdmin()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void parentRoleApprovedFalseFilterIsClampedAndReturnsApprovedOnly() {
    // The approval panel is admin-only on the FE — but if a parent crafts the query directly,
    // the service must still clamp approved=true unconditionally per Part 6.2.
    List<HolidayView> rows =
        service.findInSchool(SUNRISE, Boolean.FALSE, HolidaySource.FEDERAL, parent());
    assertThat(rows).extracting(HolidayView::approved).allMatch(Boolean::booleanValue);
    assertThat(rows).extracting(HolidayView::id).contains(approvedFederalSunrise);
    assertThat(rows)
        .extracting(HolidayView::id)
        .doesNotContain(pendingFederalSunrise, pendingFederalSunrise2);
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

  private UUID insert(
      UUID schoolId,
      LocalDate date,
      String name,
      String source,
      boolean approved,
      boolean softDeleted) {
    UUID id = UUID.randomUUID();
    if (approved) {
      calendarJdbc.update(
          "INSERT INTO holidays (id, org_id, school_id, date, name, source, approved, "
              + "approved_at, approved_by_user_id, created_by_user_id"
              + (softDeleted ? ", deleted_at" : "")
              + ") VALUES (?, ?, ?, ?, ?, ?, true, now(), ?, ?"
              + (softDeleted ? ", now()" : "")
              + ")",
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
              + "created_by_user_id"
              + (softDeleted ? ", deleted_at" : "")
              + ") VALUES (?, ?, ?, ?, ?, ?, false, ?"
              + (softDeleted ? ", now()" : "")
              + ")",
          id,
          ORG,
          schoolId,
          date,
          name,
          source,
          OLIVIA);
    }
    return id;
  }
}
