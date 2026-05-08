package com.childcarewow.calendar.holiday;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class HolidayApproveBatchIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");

  @Autowired HolidayService holidayService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-hab-%'");
  }

  @Test
  void mixedBatchApprovesValidAndSkipsRest() {
    UUID a = insertPendingFederal(SUNRISE, LocalDate.of(2028, 3, 1), "IT-hab-A");
    UUID b = insertPendingFederal(SUNRISE, LocalDate.of(2028, 3, 2), "IT-hab-B");
    UUID c = insertPendingFederal(SUNRISE, LocalDate.of(2028, 3, 3), "IT-hab-C");
    UUID notFound = UUID.randomUUID();

    // d will collide: pre-create approved CUSTOM on 2028-03-04, then a pending federal on the same
    // date.
    LocalDate occupied = LocalDate.of(2028, 3, 4);
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, occupied, "IT-hab-Occupant", null), admin());
    UUID d = insertPendingFederal(SUNRISE, occupied, "IT-hab-D-pending");

    ApproveBatchResult result = holidayService.approveBatch(List.of(a, b, c, notFound, d), admin());

    assertThat(result.approved()).isEqualTo(3);
    assertThat(result.skipped())
        .extracting(ApproveBatchResult.Skip::id, ApproveBatchResult.Skip::reason)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(notFound, "NOT_FOUND"),
            org.assertj.core.groups.Tuple.tuple(d, "DUPLICATE_HOLIDAY"));

    // The 3 valid rows are approved in DB.
    Integer approvedRows =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays WHERE id IN (?, ?, ?) AND approved = true",
            Integer.class,
            a,
            b,
            c);
    assertThat(approvedRows).isEqualTo(3);
  }

  @Test
  void alreadyApprovedRowsAreReportedAsSkipNotApproved() {
    UUID approved =
        holidayService
            .create(
                new CreateHolidayRequest(SUNRISE, LocalDate.of(2028, 4, 1), "IT-hab-already", null),
                admin())
            .id();
    UUID pending = insertPendingFederal(SUNRISE, LocalDate.of(2028, 4, 2), "IT-hab-pending");

    ApproveBatchResult result = holidayService.approveBatch(List.of(approved, pending), admin());

    // pending → approved this run; approved → ALREADY_APPROVED skip.
    assertThat(result.approved()).isEqualTo(1);
    assertThat(result.skipped())
        .extracting(ApproveBatchResult.Skip::id, ApproveBatchResult.Skip::reason)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(approved, "ALREADY_APPROVED"));
  }

  @Test
  void onePerRowFailureDoesNotRollBackOthers() {
    UUID a = insertPendingFederal(SUNRISE, LocalDate.of(2028, 5, 1), "IT-hab-tx-A");
    UUID b = insertPendingFederal(SUNRISE, LocalDate.of(2028, 5, 2), "IT-hab-tx-B");
    UUID notFound = UUID.randomUUID();

    holidayService.approveBatch(List.of(a, notFound, b), admin());

    // Both real rows are approved despite the failure between them.
    Integer approvedCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM holidays WHERE id IN (?, ?) AND approved = true",
            Integer.class,
            a,
            b);
    assertThat(approvedCount).isEqualTo(2);
  }

  @Test
  void emptyListIsRejectedByValidation() {
    // Bean-validation @NotEmpty fires at the controller boundary in real requests; at the service
    // level, an empty list returns a zero-result. We assert the service-level path here — the
    // controller-level slice for @NotEmpty would belong in a future @WebMvcTest.
    ApproveBatchResult result = holidayService.approveBatch(List.of(), admin());
    assertThat(result.approved()).isZero();
    assertThat(result.skipped()).isEmpty();
  }

  @Test
  void hundredOneIdsAreAllProcessedNoCapAtServiceLayer() {
    // Cap is enforced at the controller via @Size(max=100). At the service layer, big lists run.
    // Use random UUIDs so they all skip with NOT_FOUND — the test verifies the loop completes.
    List<UUID> ids = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();
    ApproveBatchResult result = holidayService.approveBatch(ids, admin());
    assertThat(result.approved()).isZero();
    assertThat(result.skipped()).hasSize(101);
    assertThat(result.skipped())
        .extracting(ApproveBatchResult.Skip::reason)
        .containsOnly("NOT_FOUND");
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
        Set.of(),
        Set.of(),
        "Owner");
  }
}
