package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.CreateEventRequest;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.event.EventView;
import com.childcarewow.calendar.exception.EventOnHolidayException;
import com.childcarewow.calendar.holiday.CreateHolidayRequest;
import com.childcarewow.calendar.holiday.HolidayService;
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
 * End-to-end verification of the holiday → notification pause pipeline (Part 6.8). Combines real
 * {@link HolidayService} (Part 6.1/6.4) and {@link EventService} (Part 5.x) to prove that the
 * pause-check inside {@link NotificationService} (Part 5.8) actually fires now that the holidays
 * controller can create approved rows through the real path.
 *
 * <p><b>v1 non-retroactive behavior.</b> The pause check runs <em>at dispatch time</em>, not
 * retroactively. If an event's notification has already been written before its date became a
 * holiday, that row stays unpaused. Test {@link
 * #eventCreatedBeforeHolidayLeavesExistingNotificationUnpaused} pins this contract — the playbook's
 * Common Failure Points block explicitly calls out retroactive pause as out of scope for v1 (would
 * require extending {@code SoftFlagService.recomputeForHoliday} to also patch pending-but-unsent
 * notifications). If we ever want retroactive pause, this test is the canary that fails first.
 */
@SpringBootTest
class HolidayNotificationPauseIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");

  @Autowired EventService eventService;
  @Autowired HolidayService holidayService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-hnp-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-hnp-%'");
    calendarJdbc.update(
        "DELETE FROM conflict_flags WHERE conflicting_entity_id IN "
            + "(SELECT id FROM holidays WHERE name LIKE 'IT-hnp-%')");
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-hnp-%'");
    calendarJdbc.update("DELETE FROM holidays WHERE name LIKE 'IT-hnp-%'");
  }

  @Test
  void holidayCreatedFirstBlocksEventCreationAndNoNotificationWritten() {
    LocalDate date = LocalDate.of(2026, 12, 25);
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-hnp-Christmas-Before", null), admin());

    assertThatThrownBy(
            () -> eventService.create(schoolEventRequest("IT-hnp-blocked-event", date), admin()))
        .isInstanceOf(EventOnHolidayException.class);

    // Nothing got written for the rejected create — neither event nor notification.
    Integer evCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM events WHERE title = ?", Integer.class, "IT-hnp-blocked-event");
    Integer notifCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE related_entity_title = ?",
            Integer.class,
            "IT-hnp-blocked-event");
    assertThat(evCount).isZero();
    assertThat(notifCount).isZero();
  }

  @Test
  void eventCreatedBeforeHolidayLeavesExistingNotificationUnpaused() {
    LocalDate date = LocalDate.of(2026, 12, 26);

    EventView ev =
        eventService.create(schoolEventRequest("IT-hnp-event-then-holiday", date), admin());

    // Pre-holiday: notification exists, paused=false, message is the plain invite text.
    assertThat(countNotifications(ev.id())).isEqualTo(1);
    assertThat(pausedFor(ev.id())).isFalse();
    assertThat(messageFor(ev.id())).doesNotContain("[paused:");

    // Now create an approved holiday on the same date.
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-hnp-Christmas-After", null), admin());

    // The pre-existing notification row is NOT retroactively paused — v1 contract.
    assertThat(pausedFor(ev.id())).isFalse();
    assertThat(messageFor(ev.id())).doesNotContain("[paused:");
  }

  @Test
  void dispatchAfterHolidayCreationLandsPausedWithReasonAndPrefix() {
    LocalDate date = LocalDate.of(2026, 12, 27);

    // 1. Create event with inviteParents=false — no notification written.
    UUID id =
        eventService
            .create(schoolEventNoInvite("IT-hnp-flip-on-after-holiday", date), admin())
            .id();
    assertThat(countNotifications(id)).isZero();

    // 2. Create approved holiday on the event's date.
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-hnp-Christmas-Eve-Eve", null), admin());

    // 3. PUT the event with inviteParents=true. The off→on flip writes EVENT_INVITE; the dispatch
    // path runs the pause check and sees the now-approved holiday.
    eventService.update(id, schoolEventRequest("IT-hnp-flip-on-after-holiday", date), admin());

    assertThat(countNotifications(id)).isEqualTo(1);
    assertThat(pausedFor(id)).isTrue();
    assertThat(pausedReasonFor(id)).isEqualTo("Holiday: IT-hnp-Christmas-Eve-Eve");
    assertThat(messageFor(id)).startsWith("[paused: Holiday: IT-hnp-Christmas-Eve-Eve] ");
    assertThat(kindFor(id)).isEqualTo("EVENT_INVITE");
  }

  @Test
  void pausedNotificationStillInsertsAllRecipientsSoUnpauseJobCanFlipFlag() {
    LocalDate date = LocalDate.of(2026, 12, 28);

    UUID id =
        eventService.create(schoolEventNoInvite("IT-hnp-paused-recipients", date), admin()).id();
    holidayService.create(
        new CreateHolidayRequest(SUNRISE, date, "IT-hnp-Day-After-Christmas", null), admin());
    eventService.update(id, schoolEventRequest("IT-hnp-paused-recipients", date), admin());

    // Sunrise has Priya as its only PARENT. The notification is paused, but the recipient row
    // must still be inserted so a future unpause job can flip the flag without rebuilding the
    // recipient set.
    assertThat(pausedFor(id)).isTrue();
    Integer recipientCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_recipients nr "
                + "JOIN notifications n ON n.id = nr.notification_id "
                + "WHERE n.related_entity_id = ?",
            Integer.class,
            id);
    assertThat(recipientCount).as("recipients still inserted on paused notification").isEqualTo(1);

    UUID recipient =
        calendarJdbc.queryForObject(
            "SELECT nr.user_id FROM notification_recipients nr "
                + "JOIN notifications n ON n.id = nr.notification_id "
                + "WHERE n.related_entity_id = ?",
            UUID.class,
            id);
    assertThat(recipient).isEqualTo(PRIYA);
  }

  // -- request builders ------------------------------------------------------

  private CreateEventRequest schoolEventRequest(String title, LocalDate date) {
    return new CreateEventRequest(
        EventType.SCHOOL,
        SUNRISE,
        title,
        null,
        null,
        date.atTime(9, 0).atOffset(java.time.ZoneOffset.ofHours(-5)),
        date.atTime(10, 0).atOffset(java.time.ZoneOffset.ofHours(-5)),
        false,
        null,
        true);
  }

  private CreateEventRequest schoolEventNoInvite(String title, LocalDate date) {
    return new CreateEventRequest(
        EventType.SCHOOL,
        SUNRISE,
        title,
        null,
        null,
        date.atTime(9, 0).atOffset(java.time.ZoneOffset.ofHours(-5)),
        date.atTime(10, 0).atOffset(java.time.ZoneOffset.ofHours(-5)),
        false,
        null,
        false);
  }

  // -- assertions helpers ----------------------------------------------------

  private Integer countNotifications(UUID eventId) {
    return calendarJdbc.queryForObject(
        "SELECT COUNT(*) FROM notifications WHERE related_entity_id = ?", Integer.class, eventId);
  }

  private Boolean pausedFor(UUID eventId) {
    return calendarJdbc.queryForObject(
        "SELECT paused FROM notifications WHERE related_entity_id = ?", Boolean.class, eventId);
  }

  private String pausedReasonFor(UUID eventId) {
    return calendarJdbc.queryForObject(
        "SELECT paused_reason FROM notifications WHERE related_entity_id = ?",
        String.class,
        eventId);
  }

  private String messageFor(UUID eventId) {
    return calendarJdbc.queryForObject(
        "SELECT message FROM notifications WHERE related_entity_id = ?", String.class, eventId);
  }

  private String kindFor(UUID eventId) {
    return calendarJdbc.queryForObject(
        "SELECT kind FROM notifications WHERE related_entity_id = ?", String.class, eventId);
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
