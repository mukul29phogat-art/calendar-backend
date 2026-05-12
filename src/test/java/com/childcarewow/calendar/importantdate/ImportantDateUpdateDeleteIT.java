package com.childcarewow.calendar.importantdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
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
 * Real-DB IT for Part 10.2 — {@code PUT /api/v1/important-dates/{id}} + {@code DELETE
 * /api/v1/important-dates/{id}}. Mirror of the event/task update + soft-delete patterns from Series
 * 5/8.
 */
@SpringBootTest
class ImportantDateUpdateDeleteIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID OAKWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID ZOE = UUID.fromString("55555555-0000-0000-0000-000000000001");

  @Autowired ImportantDateService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM important_dates WHERE label LIKE 'IT-idud-%'");
  }

  @Test
  void updateRoundsTripChangedFields() {
    UUID id = seedImportant("IT-idud-orig-label", LocalDate.of(2027, 6, 1), false);

    ImportantDateView updated =
        service.update(
            id,
            new CreateImportantDateRequest(
                "IT-idud-new-label",
                LocalDate.of(2027, 6, 8),
                SUNRISE,
                ImportantKind.IMPORTANT,
                null,
                true),
            admin());

    assertThat(updated.label()).isEqualTo("IT-idud-new-label");
    assertThat(updated.date()).isEqualTo(LocalDate.of(2027, 6, 8));
    assertThat(updated.visibleToParents()).isTrue();
  }

  @Test
  void schoolIdImmutable() {
    UUID id = seedImportant("IT-idud-immutable-school", LocalDate.of(2027, 6, 1), false);

    assertThatThrownBy(
            () ->
                service.update(
                    id,
                    new CreateImportantDateRequest(
                        "IT-idud-still-here",
                        LocalDate.of(2027, 6, 1),
                        OAKWOOD,
                        ImportantKind.IMPORTANT,
                        null,
                        false),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("schoolId");
  }

  @Test
  void updateToBirthdayWithoutStudentIdRejected() {
    UUID id = seedImportant("IT-idud-to-birthday", LocalDate.of(2027, 6, 1), false);

    assertThatThrownBy(
            () ->
                service.update(
                    id,
                    new CreateImportantDateRequest(
                        "IT-idud-no-student",
                        LocalDate.of(2027, 6, 1),
                        SUNRISE,
                        ImportantKind.BIRTHDAY,
                        null,
                        false),
                    admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("studentId");
  }

  @Test
  void deleteSoftDeletesRow() {
    UUID id = seedImportant("IT-idud-to-delete", LocalDate.of(2027, 6, 1), false);

    service.delete(id, admin());

    Boolean softDeleted =
        calendarJdbc.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM important_dates WHERE id = ?", Boolean.class, id);
    assertThat(softDeleted).isTrue();
  }

  @Test
  void doubleDeleteReturns404() {
    UUID id = seedImportant("IT-idud-double-delete", LocalDate.of(2027, 6, 1), false);
    service.delete(id, admin());

    assertThatThrownBy(() -> service.delete(id, admin())).isInstanceOf(NotFoundException.class);
  }

  @Test
  void updateOnSoftDeletedReturns404() {
    UUID id = seedImportant("IT-idud-update-deleted", LocalDate.of(2027, 6, 1), false);
    service.delete(id, admin());

    assertThatThrownBy(
            () ->
                service.update(
                    id,
                    new CreateImportantDateRequest(
                        "IT-idud-cant-touch",
                        LocalDate.of(2027, 6, 1),
                        SUNRISE,
                        ImportantKind.IMPORTANT,
                        null,
                        false),
                    admin()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void updateChangingStudentValidatesExistence() {
    UUID id = seedImportant("IT-idud-bday-existing", LocalDate.of(2027, 6, 1), false);
    // Promote to BIRTHDAY with a real student — should succeed.
    service.update(
        id,
        new CreateImportantDateRequest(
            "IT-idud-bday-existing",
            LocalDate.of(2027, 6, 1),
            SUNRISE,
            ImportantKind.BIRTHDAY,
            ZOE,
            true),
        admin());

    // Now change studentId to a non-existent one → 400.
    assertThatThrownBy(
            () ->
                service.update(
                    id,
                    new CreateImportantDateRequest(
                        "IT-idud-bday-existing",
                        LocalDate.of(2027, 6, 1),
                        SUNRISE,
                        ImportantKind.BIRTHDAY,
                        UUID.fromString("55555555-0000-0000-0000-000000000099"),
                        true),
                    admin()))
        .isInstanceOf(ValidationException.class);
  }

  // -- helpers ---------------------------------------------------------------

  private UUID seedImportant(String label, LocalDate date, boolean visibleToParents) {
    return service
        .create(
            new CreateImportantDateRequest(
                label, date, SUNRISE, ImportantKind.IMPORTANT, null, visibleToParents),
            admin())
        .id();
  }

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE, OAKWOOD),
        Set.of(),
        Set.of(),
        "Owner");
  }
}
