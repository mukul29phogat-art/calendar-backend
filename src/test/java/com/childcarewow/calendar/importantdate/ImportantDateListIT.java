package com.childcarewow.calendar.importantdate;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-DB IT for Part 10.3 — {@code GET /api/v1/important-dates} parent-visibility matrix. Seeds
 * four rows at SUNRISE and queries with admin / staff / parent-of-Aanya principals; asserts the
 * expected per-role visibility.
 *
 * <ul>
 *   <li>Admin / staff: all 4 rows.
 *   <li>Parent-of-Aanya: own-child birthday + the {@code visible=true} IMPORTANT row. Other child's
 *       birthday is hidden even though {@code visible_to_parents=true}.
 * </ul>
 */
@SpringBootTest
class ImportantDateListIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  private static final UUID AANYA = UUID.fromString("55555555-0000-0000-0000-000000000001");
  private static final UUID JORDAN = UUID.fromString("55555555-0000-0000-0000-000000000002");

  @Autowired ImportantDateService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM important_dates WHERE label LIKE 'IT-idl-%'");
  }

  @Test
  void adminSeesEveryRowInWindow() {
    seedFixtureRows();

    List<ImportantDateView> rows =
        service.list(SUNRISE, LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), admin());

    assertThat(rows).hasSize(4);
  }

  @Test
  void staffSeesEveryRowInWindow() {
    seedFixtureRows();

    List<ImportantDateView> rows =
        service.list(SUNRISE, LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), staff());

    assertThat(rows).hasSize(4);
  }

  @Test
  void parentSeesOwnChildBirthdayAndVisibleImportant() {
    seedFixtureRows();

    List<ImportantDateView> rows =
        service.list(SUNRISE, LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), parentOfAanya());

    // Expected: Aanya's birthday + the visible IMPORTANT row. NOT Jordan's birthday (other child)
    // and NOT the hidden IMPORTANT row.
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(ImportantDateView::label).toList())
        .containsExactlyInAnyOrder("IT-idl-aanya-birthday", "IT-idl-picture-day-public");
  }

  @Test
  void parentDoesNotSeeOtherChildBirthdayEvenWhenVisibleToParents() {
    // Pin the playbook's common-failure-point: parent of student A queries → does NOT see student
    // B's birthday, even though the row has visible_to_parents=true.
    seedFixtureRows();

    List<ImportantDateView> rows =
        service.list(SUNRISE, LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), parentOfAanya());

    assertThat(rows.stream().map(ImportantDateView::label).toList())
        .doesNotContain("IT-idl-jordan-birthday");
  }

  @Test
  void parentSeesNothingWhenAllRowsAreInvisible() {
    // Seed two rows, both with visibleToParents=false. Parent gets empty list even though Aanya is
    // their child.
    service.create(
        new CreateImportantDateRequest(
            "IT-idl-hidden-bday",
            LocalDate.of(2027, 7, 10),
            SUNRISE,
            ImportantKind.BIRTHDAY,
            AANYA,
            false),
        admin());
    service.create(
        new CreateImportantDateRequest(
            "IT-idl-hidden-important",
            LocalDate.of(2027, 7, 20),
            SUNRISE,
            ImportantKind.IMPORTANT,
            null,
            false),
        admin());

    List<ImportantDateView> rows =
        service.list(SUNRISE, LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), parentOfAanya());

    assertThat(rows).isEmpty();
  }

  // -- helpers ---------------------------------------------------------------

  private void seedFixtureRows() {
    // Row 1: Aanya's birthday, visible to parents → parent sees this.
    service.create(
        new CreateImportantDateRequest(
            "IT-idl-aanya-birthday",
            LocalDate.of(2027, 7, 10),
            SUNRISE,
            ImportantKind.BIRTHDAY,
            AANYA,
            true),
        admin());
    // Row 2: Jordan's birthday, visible to parents → parent of Aanya does NOT see this (other
    // child's row).
    service.create(
        new CreateImportantDateRequest(
            "IT-idl-jordan-birthday",
            LocalDate.of(2027, 7, 15),
            SUNRISE,
            ImportantKind.BIRTHDAY,
            JORDAN,
            true),
        admin());
    // Row 3: Picture Day, visible to parents → every parent at school sees this.
    service.create(
        new CreateImportantDateRequest(
            "IT-idl-picture-day-public",
            LocalDate.of(2027, 7, 20),
            SUNRISE,
            ImportantKind.IMPORTANT,
            null,
            true),
        admin());
    // Row 4: Staff inservice, not visible to parents → admins/staff see, parents don't.
    service.create(
        new CreateImportantDateRequest(
            "IT-idl-staff-inservice",
            LocalDate.of(2027, 7, 25),
            SUNRISE,
            ImportantKind.IMPORTANT,
            null,
            false),
        admin());
  }

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE),
        Set.of(),
        Set.of(),
        "Owner");
  }

  private static UserPrincipal staff() {
    return new UserPrincipal(
        MAYA,
        "Maya",
        "maya@ccw.test",
        Role.STAFF,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES),
        Set.of(),
        "Lead Teacher");
  }

  private static UserPrincipal parentOfAanya() {
    return new UserPrincipal(
        PRIYA,
        "Priya",
        "priya@parent.test",
        Role.PARENT,
        ORG,
        Set.of(SUNRISE),
        Set.of(),
        Set.of(AANYA),
        null);
  }
}
