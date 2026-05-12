package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.childcarewow.calendar.notification.EmailDispatcher.DispatchResult;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Unit-level coverage for {@link EmailDispatcher}'s allowlist gate + send / failure / disabled
 * paths. Uses a Mockito-mocked {@link JavaMailSender} so the test runs offline — real SMTP arrives
 * via the Series 11 deploy with AWS Secrets Manager creds.
 */
class EmailDispatcherTest {

  private static final List<String> DEV_ALLOWLIST = List.of("@childcarewow.com", "@ccw.test");

  @Test
  void allowlistedRecipientGetsSent() {
    JavaMailSender mail = mock(JavaMailSender.class);
    MimeMessage msg = new MimeMessage((Session) null);
    org.mockito.Mockito.when(mail.createMimeMessage()).thenReturn(msg);

    EmailDispatcher dispatcher =
        new EmailDispatcher(mail, new EmailDispatcherProperties(true, DEV_ALLOWLIST));

    DispatchResult result = dispatcher.send("alice@ccw.test", "Welcome", "<p>Hello!</p>");

    assertThat(result).isEqualTo(DispatchResult.SENT);
    verify(mail, times(1)).send(any(MimeMessage.class));
  }

  @Test
  void nonAllowlistedRecipientIsBlockedAndSmtpUntouched() {
    JavaMailSender mail = mock(JavaMailSender.class);
    EmailDispatcher dispatcher =
        new EmailDispatcher(mail, new EmailDispatcherProperties(true, DEV_ALLOWLIST));

    DispatchResult result = dispatcher.send("stranger@external.example", "Hi", "<p>nope</p>");

    assertThat(result).isEqualTo(DispatchResult.BLOCKED_BY_ALLOWLIST);
    verify(mail, never()).createMimeMessage();
    verify(mail, never()).send(any(MimeMessage.class));
  }

  @Test
  void emptyAllowlistPermitsAllRecipients() {
    // Prod default — empty list means "no dev gate, every recipient OK."
    JavaMailSender mail = mock(JavaMailSender.class);
    MimeMessage msg = new MimeMessage((Session) null);
    org.mockito.Mockito.when(mail.createMimeMessage()).thenReturn(msg);

    EmailDispatcher dispatcher =
        new EmailDispatcher(mail, new EmailDispatcherProperties(true, List.of()));

    DispatchResult result = dispatcher.send("anyone@anywhere.example", "Subject", "<p>Body</p>");

    assertThat(result).isEqualTo(DispatchResult.SENT);
    verify(mail, times(1)).send(any(MimeMessage.class));
  }

  @Test
  void disabledFlagShortCircuitsBeforeSmtp() {
    JavaMailSender mail = mock(JavaMailSender.class);
    EmailDispatcher dispatcher =
        new EmailDispatcher(mail, new EmailDispatcherProperties(false, DEV_ALLOWLIST));

    DispatchResult result = dispatcher.send("alice@ccw.test", "Welcome", "<p>Hello!</p>");

    assertThat(result).isEqualTo(DispatchResult.DISABLED);
    verify(mail, never()).createMimeMessage();
  }

  @Test
  void smtpFailureReturnsFailedNotThrown() {
    // The dispatcher must not propagate SMTP exceptions — the upstream caller (Part 11.7's
    // delivery row updater) handles retry / failure semantics. The dispatcher returns FAILED so
    // the caller can stamp the delivery row's status without a try/catch.
    JavaMailSender mail = mock(JavaMailSender.class);
    MimeMessage msg = new MimeMessage((Session) null);
    org.mockito.Mockito.when(mail.createMimeMessage()).thenReturn(msg);
    doThrow(new MailSendException("SMTP server down")).when(mail).send(any(MimeMessage.class));

    EmailDispatcher dispatcher =
        new EmailDispatcher(mail, new EmailDispatcherProperties(true, DEV_ALLOWLIST));

    DispatchResult result = dispatcher.send("alice@ccw.test", "Welcome", "<p>Hello!</p>");

    assertThat(result).isEqualTo(DispatchResult.FAILED);
  }

  @Test
  void caseInsensitiveAllowlistMatch() {
    // Operator may type @CCW.TEST in config; recipient may have @ccw.test in their profile.
    // Match case-insensitively so they line up.
    JavaMailSender mail = mock(JavaMailSender.class);
    MimeMessage msg = new MimeMessage((Session) null);
    org.mockito.Mockito.when(mail.createMimeMessage()).thenReturn(msg);

    EmailDispatcher dispatcher =
        new EmailDispatcher(mail, new EmailDispatcherProperties(true, List.of("@CCW.TEST")));

    DispatchResult result = dispatcher.send("Alice@ccw.test", "Hi", "<p>x</p>");

    assertThat(result).isEqualTo(DispatchResult.SENT);
  }

  @Test
  void nullRecipientBlocked() {
    JavaMailSender mail = mock(JavaMailSender.class);
    EmailDispatcher dispatcher =
        new EmailDispatcher(mail, new EmailDispatcherProperties(true, DEV_ALLOWLIST));

    DispatchResult result = dispatcher.send(null, "x", "x");

    assertThat(result).isEqualTo(DispatchResult.BLOCKED_BY_ALLOWLIST);
    verify(mail, never()).createMimeMessage();
  }
}
