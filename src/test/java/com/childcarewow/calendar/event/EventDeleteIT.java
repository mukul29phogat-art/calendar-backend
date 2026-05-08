package com.childcarewow.calendar.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
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
class EventDeleteIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");

  @Autowired EventService service;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-delete-%'");
  }

  @Test
  void softDeletesAndExcludesFromReads() {
    UUID id =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-delete-base",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
                    OffsetDateTime.parse("2026-09-15T15:00:00-04:00"),
                    false,
                    null,
                    false),
                admin())
            .id();

    service.delete(id);

    // findById now 404s because the deletedAt filter excludes the row.
    assertThatThrownBy(() -> service.findById(id, admin())).isInstanceOf(NotFoundException.class);

    // The row is still in the table though — verify deleted_at populated.
    java.time.OffsetDateTime deletedAt =
        calendarJdbc.queryForObject(
            "SELECT deleted_at FROM events WHERE id = ?", java.time.OffsetDateTime.class, id);
    assertThat(deletedAt).isNotNull();

    // Window query also excludes it.
    var window =
        service.findInWindow(
            SUNRISE,
            OffsetDateTime.parse("2026-09-01T00:00:00Z"),
            OffsetDateTime.parse("2026-09-30T23:59:59Z"),
            null,
            admin());
    assertThat(window).extracting(EventView::id).doesNotContain(id);
  }

  @Test
  void deleteClearsBidirectionalDoubleBookingFlags() {
    // Two overlapping events → DOUBLE_BOOKING pair.
    UUID a =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-delete-a",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2026-09-20T14:00:00-04:00"),
                    OffsetDateTime.parse("2026-09-20T15:00:00-04:00"),
                    false,
                    null,
                    false),
                admin())
            .id();
    UUID b =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-delete-b",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2026-09-20T14:30:00-04:00"),
                    OffsetDateTime.parse("2026-09-20T15:30:00-04:00"),
                    false,
                    null,
                    false),
                admin())
            .id();

    Integer flagsBefore =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'EVENT' AND conflict_type = 'DOUBLE_BOOKING' "
                + "AND (entity_id = ? OR conflicting_entity_id = ?)",
            Integer.class,
            a,
            a);
    assertThat(flagsBefore).as("pair: A→B and B→A").isEqualTo(2);

    // Delete A — both flags should be gone.
    service.delete(a);

    Integer flagsForA =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'EVENT' AND conflict_type = 'DOUBLE_BOOKING' "
                + "AND (entity_id = ? OR conflicting_entity_id = ?)",
            Integer.class,
            a,
            a);
    assertThat(flagsForA).isZero();

    // B's surviving-side flag (which pointed at A) is also gone.
    Integer flagsForB =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM conflict_flags "
                + "WHERE entity_type = 'EVENT' AND conflict_type = 'DOUBLE_BOOKING' "
                + "AND entity_id = ?",
            Integer.class,
            b);
    assertThat(flagsForB).isZero();
  }

  @Test
  void unknownIdReturns404() {
    assertThatThrownBy(() -> service.delete(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void doubleDeleteReturns404() {
    UUID id =
        service
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-delete-twice",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2026-09-25T14:00:00-04:00"),
                    OffsetDateTime.parse("2026-09-25T15:00:00-04:00"),
                    false,
                    null,
                    false),
                admin())
            .id();
    service.delete(id);
    // Second delete: already soft-deleted, so the deletedAt filter excludes it -> 404.
    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
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
