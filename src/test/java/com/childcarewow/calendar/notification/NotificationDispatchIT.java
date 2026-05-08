package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.CreateEventRequest;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.event.EventView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class NotificationDispatchIT {

  // Seed UUIDs
  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CATERPILLARS = UUID.fromString("44444444-0000-0000-0000-000000000002");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  private static final UUID DANIEL = UUID.fromString("33333333-0000-0000-0000-000000000007");
  private static final UUID AANYA =
      UUID.fromString("55555555-0000-0000-0000-000000000001"); // Butterflies, Priya's child
  private static final UUID LILA =
      UUID.fromString("55555555-0000-0000-0000-000000000003"); // Sunbeams, Daniel's child

  @Autowired EventService eventService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @AfterEach
  void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-nd-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-nd-%'");
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-nd-%'");
  }

  @Test
  void schoolEventWritesNotificationToAllParentsAtSchool() {
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.SCHOOL,
                SUNRISE,
                "IT-nd-school-event",
                null,
                null,
                OffsetDateTime.parse("2026-10-01T09:00:00-04:00"),
                OffsetDateTime.parse("2026-10-01T10:00:00-04:00"),
                false,
                null,
                true),
            admin());

    Integer notifs = countNotifications(ev.id());
    assertThat(notifs).as("one notification row").isEqualTo(1);

    // Sunrise has Priya as the only PARENT (Daniel is at Maplewood).
    List<UUID> recipients = recipientsFor(ev.id());
    assertThat(recipients).containsExactly(PRIYA);

    Boolean paused = pausedFor(ev.id());
    assertThat(paused).isFalse();
  }

  @Test
  void classroomEventWritesNotificationToParentsOfStudentsInClassroom() {
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.CLASSROOM,
                SUNRISE,
                "IT-nd-classroom-event",
                null,
                BUTTERFLIES,
                OffsetDateTime.parse("2026-10-02T09:00:00-04:00"),
                OffsetDateTime.parse("2026-10-02T10:00:00-04:00"),
                false,
                null,
                true),
            admin());
    // Butterflies has Aanya (Priya's daughter); Caterpillars has Jordan (no parent linked).
    List<UUID> recipients = recipientsFor(ev.id());
    assertThat(recipients).containsExactly(PRIYA);
  }

  @Test
  void inviteParentsFalseWritesNoNotification() {
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.SCHOOL,
                SUNRISE,
                "IT-nd-no-invite",
                null,
                null,
                OffsetDateTime.parse("2026-10-03T09:00:00-04:00"),
                OffsetDateTime.parse("2026-10-03T10:00:00-04:00"),
                false,
                null,
                false),
            admin());
    assertThat(countNotifications(ev.id())).isZero();
  }

  @Test
  void excludedUserIdSubtractsFromRecipients() {
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.SCHOOL,
                SUNRISE,
                "IT-nd-exclude-user",
                null,
                null,
                OffsetDateTime.parse("2026-10-04T09:00:00-04:00"),
                OffsetDateTime.parse("2026-10-04T10:00:00-04:00"),
                false,
                null,
                true,
                null,
                null,
                List.of(PRIYA)),
            admin());
    // Priya excluded by user_id → recipient list empty → no notification row written.
    assertThat(countNotifications(ev.id())).isZero();
  }

  @Test
  void excludedStudentIdSubtractsParents() {
    // SCHOOL event at Maplewood; Daniel is the only parent at Maplewood (parent of Lila).
    // Exclude Lila as a STUDENT — Daniel must drop out.
    EventView ev =
        eventService.create(
            new CreateEventRequest(
                EventType.SCHOOL,
                MAPLEWOOD,
                "IT-nd-exclude-student",
                null,
                null,
                OffsetDateTime.parse("2026-10-05T09:00:00-04:00"),
                OffsetDateTime.parse("2026-10-05T10:00:00-04:00"),
                false,
                null,
                true,
                null,
                null,
                List.of(LILA)),
            admin());
    assertThat(countNotifications(ev.id())).isZero();
  }

  @Test
  void inviteParentsOffToOnWritesEventInvite() {
    UUID id =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-nd-flip-on",
                    null,
                    null,
                    OffsetDateTime.parse("2026-10-06T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-10-06T10:00:00-04:00"),
                    false,
                    null,
                    false), // off
                admin())
            .id();
    assertThat(countNotifications(id)).isZero();

    eventService.update(
        id,
        new CreateEventRequest(
            EventType.SCHOOL,
            SUNRISE,
            "IT-nd-flip-on",
            null,
            null,
            OffsetDateTime.parse("2026-10-06T09:00:00-04:00"),
            OffsetDateTime.parse("2026-10-06T10:00:00-04:00"),
            false,
            null,
            true), // on
        admin());

    // Now one EVENT_INVITE row should exist.
    assertThat(countNotificationsOfKind(id, "EVENT_INVITE")).isEqualTo(1);
  }

  @Test
  void inviteParentsOnToOffWritesEventCancelled() {
    UUID id =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-nd-flip-off",
                    null,
                    null,
                    OffsetDateTime.parse("2026-10-07T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-10-07T10:00:00-04:00"),
                    false,
                    null,
                    true), // on (writes EVENT_INVITE)
                admin())
            .id();

    eventService.update(
        id,
        new CreateEventRequest(
            EventType.SCHOOL,
            SUNRISE,
            "IT-nd-flip-off",
            null,
            null,
            OffsetDateTime.parse("2026-10-07T09:00:00-04:00"),
            OffsetDateTime.parse("2026-10-07T10:00:00-04:00"),
            false,
            null,
            false), // off
        admin());

    assertThat(countNotificationsOfKind(id, "EVENT_INVITE")).isEqualTo(1);
    assertThat(countNotificationsOfKind(id, "EVENT_CANCELLED")).isEqualTo(1);
  }

  @Test
  void inviteParentsOnToOnEditWritesEventUpdated() {
    UUID id =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-nd-stay-on",
                    null,
                    null,
                    OffsetDateTime.parse("2026-10-08T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-10-08T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();

    eventService.update(
        id,
        new CreateEventRequest(
            EventType.SCHOOL,
            SUNRISE,
            "IT-nd-stay-on",
            "after edit",
            null,
            OffsetDateTime.parse("2026-10-08T09:00:00-04:00"),
            OffsetDateTime.parse("2026-10-08T10:00:00-04:00"),
            false,
            null,
            true),
        admin());

    assertThat(countNotificationsOfKind(id, "EVENT_INVITE")).isEqualTo(1);
    assertThat(countNotificationsOfKind(id, "EVENT_UPDATED")).isEqualTo(1);
  }

  @Test
  void deleteWritesEventCancelledWhenInvitedParents() {
    UUID id =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.SCHOOL,
                    SUNRISE,
                    "IT-nd-delete-cancel",
                    null,
                    null,
                    OffsetDateTime.parse("2026-10-09T09:00:00-04:00"),
                    OffsetDateTime.parse("2026-10-09T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();
    eventService.delete(id);
    assertThat(countNotificationsOfKind(id, "EVENT_CANCELLED")).isEqualTo(1);
  }

  // -- helpers ---------------------------------------------------------------

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE, MAPLEWOOD),
        Set.of(BUTTERFLIES, CATERPILLARS),
        Set.of(),
        "Owner");
  }

  private Integer countNotifications(UUID eventId) {
    return calendarJdbc.queryForObject(
        "SELECT COUNT(*) FROM notifications WHERE related_entity_id = ?", Integer.class, eventId);
  }

  private Integer countNotificationsOfKind(UUID eventId, String kind) {
    return calendarJdbc.queryForObject(
        "SELECT COUNT(*) FROM notifications WHERE related_entity_id = ? AND kind = ?",
        Integer.class,
        eventId,
        kind);
  }

  private List<UUID> recipientsFor(UUID eventId) {
    return calendarJdbc.queryForList(
        "SELECT user_id FROM notification_recipients nr "
            + "JOIN notifications n ON n.id = nr.notification_id "
            + "WHERE n.related_entity_id = ? ORDER BY user_id",
        UUID.class,
        eventId);
  }

  private Boolean pausedFor(UUID eventId) {
    return calendarJdbc.queryForObject(
        "SELECT paused FROM notifications WHERE related_entity_id = ? LIMIT 1",
        Boolean.class,
        eventId);
  }
}
