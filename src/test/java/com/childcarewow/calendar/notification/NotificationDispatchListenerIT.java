package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.CreateEventRequest;
import com.childcarewow.calendar.event.EventService;
import com.childcarewow.calendar.event.EventType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

/**
 * Real-DB end-to-end IT for the {@link NotificationDispatchListener} wire-up. Creates events via
 * {@link EventService} and verifies that — once the create transaction commits — the listener fires
 * the orchestrator, which lands {@code notification_deliveries} rows alongside the {@code
 * notifications} + {@code notification_recipients} rows.
 *
 * <p>Mocks {@link JavaMailSender} so the test doesn't actually hit SMTP; the orchestrator's
 * dispatcher-level paths are pinned by {@link NotificationDispatchOrchestratorIT} — this file pins
 * ONLY the listener wire-up (event publish → AFTER_COMMIT trigger → orchestrator dispatch).
 */
@SpringBootTest(
    properties = {
      "management.health.mail.enabled=false",
      "notifications.email.dev-allowlist=@ccw-demo.test,@ccw.test,@childcarewow.com"
    })
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NotificationDispatchListenerIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");

  @Autowired EventService eventService;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @MockBean JavaMailSender mailSender;

  @BeforeEach
  void setUp() {
    cleanup();
    Mockito.when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
  }

  @AfterEach
  void cleanupAfter() {
    cleanup();
  }

  private void cleanup() {
    calendarJdbc.update(
        "DELETE FROM notification_deliveries WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-ndl-%')");
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-ndl-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-ndl-%'");
    calendarJdbc.update("DELETE FROM events WHERE title LIKE 'IT-ndl-%'");
  }

  @Test
  void inviteParentsEventLandsDeliveriesViaListener() {
    // CLASSROOM at Butterflies with inviteParents=true → all parents-of-Butterflies-students get
    // a notification. The listener fires AFTER_COMMIT and the orchestrator writes
    // notification_deliveries rows. Maya isn't a parent so she won't be a recipient — the seed
    // has Priya (parent of Aanya) as the BUTTERFLIES parent.
    UUID eventId =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-ndl-classroom-invite",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2027-09-01T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-09-01T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();

    // Notification row landed.
    Integer notifCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE related_entity_id = ?",
            Integer.class,
            eventId);
    assertThat(notifCount).isEqualTo(1);

    // Recipients landed.
    Integer recipCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_recipients nr "
                + "JOIN notifications n ON n.id = nr.notification_id "
                + "WHERE n.related_entity_id = ?",
            Integer.class,
            eventId);
    assertThat(recipCount).isGreaterThanOrEqualTo(1);

    // Deliveries also landed — the listener fired and the orchestrator wrote audit rows.
    Integer deliveryCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_deliveries d "
                + "JOIN notifications n ON n.id = d.notification_id "
                + "WHERE n.related_entity_id = ?",
            Integer.class,
            eventId);
    assertThat(deliveryCount)
        .as("listener should have triggered orchestrator dispatch")
        .isPositive();

    // EVENT_INVITE is in EMAIL_KINDS — there should be both APP + EMAIL channel rows per recipient.
    List<String> channels =
        calendarJdbc.queryForList(
            "SELECT DISTINCT channel FROM notification_deliveries d "
                + "JOIN notifications n ON n.id = d.notification_id "
                + "WHERE n.related_entity_id = ?",
            String.class,
            eventId);
    assertThat(channels).containsExactlyInAnyOrder("APP", "EMAIL");
  }

  @Test
  void inviteParentsFalseProducesNoNotificationAndNoDeliveries() {
    // inviteParents=false → NotificationService.dispatchEventCreated early-returns. No
    // notification, no event published, no listener invocation, no deliveries.
    UUID eventId =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-ndl-classroom-no-invite",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2027-09-02T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-09-02T10:00:00-04:00"),
                    false,
                    null,
                    false),
                admin())
            .id();

    Integer notifCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE related_entity_id = ?",
            Integer.class,
            eventId);
    assertThat(notifCount).isZero();

    Integer deliveryCount =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_deliveries d "
                + "JOIN notifications n ON n.id = d.notification_id "
                + "WHERE n.related_entity_id = ?",
            Integer.class,
            eventId);
    assertThat(deliveryCount).isZero();
  }

  @Test
  void deliveriesAreExactlyOnePerChannelPerRecipient() {
    // No duplicate audit rows — exactly one APP + one EMAIL row per recipient. Catches a
    // hypothetical regression where listener double-fires (e.g. an extra event publish from a
    // future re-write of NotificationService that forgot to early-exit).
    UUID eventId =
        eventService
            .create(
                new CreateEventRequest(
                    EventType.CLASSROOM,
                    SUNRISE,
                    "IT-ndl-no-dupes",
                    null,
                    BUTTERFLIES,
                    OffsetDateTime.parse("2027-09-03T09:00:00-04:00"),
                    OffsetDateTime.parse("2027-09-03T10:00:00-04:00"),
                    false,
                    null,
                    true),
                admin())
            .id();

    // For each (notification_id, recipient_user_id, channel) tuple, exactly one delivery row.
    Integer maxRowsPerTuple =
        calendarJdbc.queryForObject(
            "SELECT MAX(c) FROM ("
                + "  SELECT COUNT(*) c FROM notification_deliveries d "
                + "  JOIN notifications n ON n.id = d.notification_id "
                + "  WHERE n.related_entity_id = ? "
                + "  GROUP BY d.notification_id, d.recipient_user_id, d.channel"
                + ") x",
            Integer.class,
            eventId);
    assertThat(maxRowsPerTuple).isEqualTo(1);
  }

  private static UserPrincipal admin() {
    return new UserPrincipal(
        OLIVIA,
        "Olivia",
        "olivia@ccw-demo.test",
        Role.ORG_ADMIN,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES),
        Set.of(),
        "Owner");
  }
}
