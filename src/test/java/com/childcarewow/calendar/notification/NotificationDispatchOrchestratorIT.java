package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.notification.NotificationDispatchOrchestrator.DispatchSummary;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
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
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

/**
 * Real-DB IT for the dispatch pipeline composition. Stitches {@link
 * NotificationDispatchOrchestrator} against the live calendar + platform DBs; mocks {@link
 * JavaMailSender} so the test never hits a real SMTP relay.
 *
 * <p>Per-test fixtures seed exactly the notification + recipient set the assertion needs — there's
 * no shared {@code @BeforeEach} that creates a notification, because each test exercises a
 * different kind and recipient mix.
 */
@SpringBootTest(
    properties = {
      "management.health.mail.enabled=false",
      // Platform seed has @ccw-demo.test addresses for staff/admin users — extend the dev
      // allowlist locally so allowlisted-recipient tests actually reach the SMTP send path.
      // priya@parent.test stays outside the allowlist on purpose (the blocked-recipient test).
      "notifications.email.dev-allowlist=@ccw-demo.test,@ccw.test,@childcarewow.com"
    })
// The @MockBean below busts Spring's context cache (a JavaMailSender swap forces a fresh
// ApplicationContext). Without DirtiesContext, the new context's 20-slot calendar Hikari pool
// + the cached default context's 20-slot pool + OpenApiSnapshotIT's separate property-busted
// context overlap at run-time and exhaust Postgres's default 100 max_connections. AFTER_CLASS
// releases this IT's pool the moment the class finishes.
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NotificationDispatchOrchestratorIT {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  // Seed users + their actual emails per docker/platform-seed.sql.
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID TOM = UUID.fromString("33333333-0000-0000-0000-000000000005");
  // Priya has email priya@parent.test — NOT in the dev allowlist (@childcarewow.com, @ccw.test).
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  // A user id that doesn't exist in platform.users — the resolver returns empty → FAILED.
  private static final UUID GHOST = UUID.fromString("33333333-0000-0000-0000-000000000099");

  @Autowired NotificationDispatchOrchestrator orchestrator;
  @Autowired NotificationRepository notificationRepo;

  @Autowired
  @Qualifier("calendarJdbcTemplate")
  JdbcTemplate calendarJdbc;

  @MockBean JavaMailSender mailSender;

  @BeforeEach
  void cleanup() {
    // Belt-and-braces: any leftover IT rows from a prior class run.
    cleanupRows();
    // Default mock behavior: createMimeMessage returns a real-but-empty MimeMessage and send is a
    // no-op. Tests that need a failure override with doThrow.
    Mockito.when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
  }

  @AfterEach
  void cleanupAfter() {
    cleanupRows();
  }

  private void cleanupRows() {
    calendarJdbc.update(
        "DELETE FROM notification_deliveries WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-ndo-%')");
    calendarJdbc.update(
        "DELETE FROM notification_recipients WHERE notification_id IN "
            + "(SELECT id FROM notifications WHERE related_entity_title LIKE 'IT-ndo-%')");
    calendarJdbc.update("DELETE FROM notifications WHERE related_entity_title LIKE 'IT-ndo-%'");
  }

  @Test
  void eventInviteDispatchesBothAppAndEmailForAllowlistedRecipient() {
    UUID notifId =
        seedNotification("IT-ndo-event-invite", NotificationKind.EVENT_INVITE, false, null, MAYA);

    DispatchSummary summary = orchestrator.dispatch(notifId);

    assertThat(summary.appSent()).isEqualTo(1);
    assertThat(summary.emailSent()).isEqualTo(1);
    assertThat(deliveryStatuses(notifId, DeliveryChannel.APP)).containsExactly("SENT");
    assertThat(deliveryStatuses(notifId, DeliveryChannel.EMAIL)).containsExactly("SENT");
    Mockito.verify(mailSender, Mockito.times(1)).send(any(MimeMessage.class));
  }

  @Test
  void taskAssignedDispatchesOnlyAppChannel() {
    // TASK_ASSIGNED is NOT in EMAIL_KINDS — only APP gets a row.
    UUID notifId =
        seedNotification("IT-ndo-task-assigned", NotificationKind.TASK_ASSIGNED, false, null, MAYA);

    DispatchSummary summary = orchestrator.dispatch(notifId);

    assertThat(summary.appSent()).isEqualTo(1);
    assertThat(summary.emailSent()).isZero();
    assertThat(deliveryStatuses(notifId, DeliveryChannel.APP)).containsExactly("SENT");
    assertThat(deliveryStatuses(notifId, DeliveryChannel.EMAIL)).isEmpty();
    // No SMTP attempt at all.
    Mockito.verify(mailSender, Mockito.never()).send(any(MimeMessage.class));
  }

  @Test
  void taskOverdueDispatchesBothAppAndEmail() {
    // TASK_OVERDUE IS in EMAIL_KINDS (worth alerting the assignee out-of-band).
    UUID notifId =
        seedNotification("IT-ndo-task-overdue", NotificationKind.TASK_OVERDUE, false, null, MAYA);

    DispatchSummary summary = orchestrator.dispatch(notifId);

    assertThat(summary.appSent()).isEqualTo(1);
    assertThat(summary.emailSent()).isEqualTo(1);
  }

  @Test
  void pausedNotificationRecordsPausedDeliveriesAcrossChannelsAndSkipsSmtp() {
    UUID notifId =
        seedNotification(
            "IT-ndo-paused",
            NotificationKind.EVENT_UPDATED,
            true,
            "Holiday: Independence Day",
            MAYA);

    DispatchSummary summary = orchestrator.dispatch(notifId);

    assertThat(summary.appPaused()).isEqualTo(1);
    assertThat(summary.emailPaused()).isEqualTo(1);
    assertThat(summary.appSent()).isZero();
    assertThat(summary.emailSent()).isZero();
    assertThat(deliveryStatuses(notifId, DeliveryChannel.APP)).containsExactly("PAUSED");
    assertThat(deliveryStatuses(notifId, DeliveryChannel.EMAIL)).containsExactly("PAUSED");
    // Reason carries through both channel's audit rows.
    assertThat(deliveryReasons(notifId, DeliveryChannel.APP))
        .containsExactly("Holiday: Independence Day");
    assertThat(deliveryReasons(notifId, DeliveryChannel.EMAIL))
        .containsExactly("Holiday: Independence Day");
    Mockito.verify(mailSender, Mockito.never()).send(any(MimeMessage.class));
  }

  @Test
  void nonAllowlistedRecipientRecordsEmailAsBlocked() {
    // Priya's email is @parent.test — outside the dev allowlist.
    UUID notifId =
        seedNotification(
            "IT-ndo-blocked-parent", NotificationKind.EVENT_INVITE, false, null, PRIYA);

    DispatchSummary summary = orchestrator.dispatch(notifId);

    assertThat(summary.appSent()).isEqualTo(1);
    assertThat(summary.emailBlocked()).isEqualTo(1);
    assertThat(deliveryStatuses(notifId, DeliveryChannel.EMAIL)).containsExactly("PAUSED");
    assertThat(deliveryReasons(notifId, DeliveryChannel.EMAIL))
        .containsExactly("BLOCKED_BY_ALLOWLIST");
    Mockito.verify(mailSender, Mockito.never()).send(any(MimeMessage.class));
  }

  @Test
  void unknownRecipientUserIdRecordsEmailAsFailed() {
    // Ghost user_id doesn't exist in platform.users → resolver returns empty.
    UUID notifId =
        seedNotification("IT-ndo-ghost-user", NotificationKind.EVENT_INVITE, false, null, GHOST);

    DispatchSummary summary = orchestrator.dispatch(notifId);

    assertThat(summary.appSent()).isEqualTo(1); // APP doesn't need email lookup
    assertThat(summary.emailFailed()).isEqualTo(1);
    assertThat(deliveryStatuses(notifId, DeliveryChannel.EMAIL)).containsExactly("FAILED");
    assertThat(deliveryReasons(notifId, DeliveryChannel.EMAIL))
        .containsExactly("Recipient email not found in platform.users");
    Mockito.verify(mailSender, Mockito.never()).send(any(MimeMessage.class));
  }

  @Test
  void smtpFailureRecordsEmailAsFailedNotThrown() {
    Mockito.doThrow(new MailSendException("SMTP server down"))
        .when(mailSender)
        .send(any(MimeMessage.class));

    UUID notifId =
        seedNotification("IT-ndo-smtp-down", NotificationKind.EVENT_INVITE, false, null, MAYA);

    DispatchSummary summary = orchestrator.dispatch(notifId);

    // Orchestrator never propagates the exception — the audit row gets FAILED, caller carries on.
    assertThat(summary.appSent()).isEqualTo(1);
    assertThat(summary.emailFailed()).isEqualTo(1);
    assertThat(deliveryStatuses(notifId, DeliveryChannel.EMAIL)).containsExactly("FAILED");
  }

  @Test
  void multiRecipientNotificationDispatchesPerRecipient() {
    UUID notifId =
        seedNotification(
            "IT-ndo-multi-recipient", NotificationKind.EVENT_INVITE, false, null, MAYA, TOM);

    DispatchSummary summary = orchestrator.dispatch(notifId);

    assertThat(summary.appSent()).isEqualTo(2);
    assertThat(summary.emailSent()).isEqualTo(2);
    // Two APP audit rows + two EMAIL audit rows.
    Integer total =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_deliveries WHERE notification_id = ?",
            Integer.class,
            notifId);
    assertThat(total).isEqualTo(4);
    Mockito.verify(mailSender, Mockito.times(2)).send(any(MimeMessage.class));
  }

  @Test
  void noRecipientsIsNoOpReturnsZeroes() {
    UUID notifId =
        seedNotification("IT-ndo-no-recipients", NotificationKind.EVENT_INVITE, false, null);
    // No recipient rows on purpose.

    DispatchSummary summary = orchestrator.dispatch(notifId);

    assertThat(summary.total()).isZero();
    Integer audit =
        calendarJdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_deliveries WHERE notification_id = ?",
            Integer.class,
            notifId);
    assertThat(audit).isZero();
  }

  @Test
  void unknownNotificationIdReturns404() {
    assertThatThrownBy(
            () -> orchestrator.dispatch(UUID.fromString("99999999-0000-0000-0000-000000000099")))
        .isInstanceOf(NotFoundException.class);
  }

  // -- helpers ---------------------------------------------------------------

  /** Seeds one notification row + per-recipient rows. Returns the notification id. */
  private UUID seedNotification(
      String relatedTitle,
      NotificationKind kind,
      boolean paused,
      String pausedReason,
      UUID... recipients) {
    UUID id = UUID.randomUUID();
    calendarJdbc.update(
        "INSERT INTO notifications (id, org_id, school_id, kind, message, related_entity_title, "
            + "paused, paused_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        ORG,
        SUNRISE,
        kind.name(),
        relatedTitle + " msg",
        relatedTitle,
        paused,
        pausedReason);
    for (UUID recipient : recipients) {
      calendarJdbc.update(
          "INSERT INTO notification_recipients (notification_id, user_id) VALUES (?, ?)",
          id,
          recipient);
    }
    return id;
  }

  private List<String> deliveryStatuses(UUID notifId, DeliveryChannel channel) {
    return calendarJdbc.queryForList(
        "SELECT status FROM notification_deliveries WHERE notification_id = ? AND channel = ? "
            + "ORDER BY created_at ASC",
        String.class,
        notifId,
        channel.name());
  }

  private List<String> deliveryReasons(UUID notifId, DeliveryChannel channel) {
    return calendarJdbc.queryForList(
        "SELECT last_error FROM notification_deliveries WHERE notification_id = ? AND channel = ? "
            + "ORDER BY created_at ASC",
        String.class,
        notifId,
        channel.name());
  }
}
