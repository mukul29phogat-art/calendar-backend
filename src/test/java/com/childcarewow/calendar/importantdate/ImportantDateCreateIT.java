package com.childcarewow.calendar.importantdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.ValidationException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-DB IT for Part 10.1 — {@code POST /api/v1/important-dates}. Exercises the create surface for
 * both kinds (BIRTHDAY + IMPORTANT) and the per-kind required-field validation.
 */
@SpringBootTest
class ImportantDateCreateIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID ZOE = UUID.fromString("55555555-0000-0000-0000-000000000001");

  @Autowired ImportantDateService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM important_dates WHERE label LIKE 'IT-id-%'");
  }

  @Test
  void createBirthdayPersistsAllFields() {
    ImportantDateView view =
        service.create(
            new CreateImportantDateRequest(
                "IT-id-zoe-birthday",
                LocalDate.of(2027, 4, 15),
                SUNRISE,
                ImportantKind.BIRTHDAY,
                ZOE,
                true),
            admin());

    assertThat(view.kind()).isEqualTo(ImportantKind.BIRTHDAY);
    assertThat(view.studentId()).isEqualTo(ZOE);
    assertThat(view.visibleToParents()).isTrue();

    String dbLabel =
        calendarJdbc.queryForObject(
            "SELECT label FROM important_dates WHERE id = ?", String.class, view.id());
    assertThat(dbLabel).isEqualTo("IT-id-zoe-birthday");
  }

  @Test
  void createImportantPersistsWithoutStudentId() {
    ImportantDateView view =
        service.create(
            new CreateImportantDateRequest(
                "IT-id-picture-day",
                LocalDate.of(2027, 5, 12),
                SUNRISE,
                ImportantKind.IMPORTANT,
                null,
                false),
            admin());

    assertThat(view.kind()).isEqualTo(ImportantKind.IMPORTANT);
    assertThat(view.studentId()).isNull();
    assertThat(view.visibleToParents()).isFalse();
  }

  @Test
  void visibleToParentsDefaultsFalseWhenOmitted() {
    // Architecture spec §5.5 — admins must opt-in. Null on the wire → false in DB.
    ImportantDateView view =
        service.create(
            new CreateImportantDateRequest(
                "IT-id-default-false",
                LocalDate.of(2027, 5, 12),
                SUNRISE,
                ImportantKind.IMPORTANT,
                null,
                null),
            admin());

    assertThat(view.visibleToParents()).isFalse();
  }

  @Test
  void birthdayWithoutStudentIdRejected() {
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateImportantDateRequest(
                        "IT-id-no-student",
                        LocalDate.of(2027, 4, 15),
                        SUNRISE,
                        ImportantKind.BIRTHDAY,
                        null,
                        true),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("studentId");
  }

  @Test
  void unknownStudentIdRejected() {
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateImportantDateRequest(
                        "IT-id-unknown-student",
                        LocalDate.of(2027, 4, 15),
                        SUNRISE,
                        ImportantKind.BIRTHDAY,
                        UUID.fromString("55555555-0000-0000-0000-000000000099"),
                        true),
                    admin()))
        .isInstanceOf(com.childcarewow.calendar.exception.ValidationException.class);
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
}
