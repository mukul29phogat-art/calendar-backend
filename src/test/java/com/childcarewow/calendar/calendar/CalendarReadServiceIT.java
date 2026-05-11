package com.childcarewow.calendar.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.CreateEventRequest;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.exception.ValidationException;
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
 * Real-DB IT for {@link CalendarReadService}. Validates the school-local date projection, the
 * 366-day window cap, and that the read pipeline correctly delegates to {@link EventService} for
 * visibility filtering.
 */
@SpringBootTest
class CalendarReadServiceIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");

  @Autowired CalendarReadService calendarReadService;
  @Autowired EventService eventService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-cal-%'");
  }

  @Test
  void readReturnsEventCalendarItemsWithSchoolLocalDate() {
    // Sunrise is America/New_York. Pick May (EDT, UTC-4) so the offset is unambiguous.
    eventService.create(
        new CreateEventRequest(
            EventType.CLASSROOM,
            SUNRISE,
            "IT-cal-storytime",
            null,
            BUTTERFLIES,
            OffsetDateTime.parse("2026-05-15T09:00:00-04:00"),
            OffsetDateTime.parse("2026-05-15T10:00:00-04:00"),
            false,
            null,
            false),
        admin());

    var items =
        calendarReadService.read(
            SUNRISE, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), admin());

    assertThat(items).hasSize(1);
    CalendarItem item = items.get(0);
    assertThat(item).isInstanceOf(EventCalendarItem.class);
    EventCalendarItem ev = (EventCalendarItem) item;
    assertThat(ev.date()).isEqualTo(LocalDate.of(2026, 5, 15));
    assertThat(ev.data().title()).isEqualTo("IT-cal-storytime");
  }

  @Test
  void emptyWindowReturnsEmptyList() {
    var items =
        calendarReadService.read(
            SUNRISE, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31), admin());
    assertThat(items).isEmpty();
  }

  @Test
  void windowBeyond366DaysRejects() {
    // 2026-01-01 to 2028-01-01 is 731 inclusive days — well over the 366 cap.
    assertThatThrownBy(
            () ->
                calendarReadService.read(
                    SUNRISE, LocalDate.of(2026, 1, 1), LocalDate.of(2028, 1, 1), admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("366");
  }

  @Test
  void toBeforeFromRejects() {
    assertThatThrownBy(
            () ->
                calendarReadService.read(
                    SUNRISE, LocalDate.of(2026, 5, 31), LocalDate.of(2026, 5, 1), admin()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("on or after");
  }

  @Test
  void missingScopeParamsReject() {
    assertThatThrownBy(
            () -> calendarReadService.read(null, LocalDate.now(), LocalDate.now(), admin()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> calendarReadService.read(SUNRISE, null, LocalDate.now(), admin()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> calendarReadService.read(SUNRISE, LocalDate.now(), null, admin()))
        .isInstanceOf(ValidationException.class);
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
