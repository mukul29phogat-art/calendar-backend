package com.childcarewow.calendar.notification;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends real email via {@link JavaMailSender}, gated by the dev allowlist (architecture spec §7.4).
 *
 * <p>The dispatcher is the lowest-level send primitive — it does NOT decide which notifications
 * deserve email, NOR does it pull from {@code notification_deliveries} (that's Part 11.7 + the
 * scheduler in Part 11.4/11.8 follow-ups). It just takes a recipient + subject + body and either
 * sends them or refuses to. The wire-up is done by upstream code that knows about the
 * notification's kind, channel, and recipient resolution.
 *
 * <p><b>Allowlist enforcement is in code, not at the SMTP / firewall level.</b> The playbook
 * common-failure-point: an ops-team firewall as the only line of defense is fragile (an
 * accidentally promoted dev config that points at real SMTP could blast users). The dev-allowlist
 * gate runs in this class first; the firewall is belt-and-braces.
 */
@Service
public class EmailDispatcher {

  private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

  private final JavaMailSender mailSender;
  private final EmailDispatcherProperties props;

  public EmailDispatcher(JavaMailSender mailSender, EmailDispatcherProperties props) {
    this.mailSender = mailSender;
    this.props = props;
  }

  /**
   * Send {@code body} to {@code toAddress}. Returns one of:
   *
   * <ul>
   *   <li>{@link DispatchResult#SENT} — successful SMTP send.
   *   <li>{@link DispatchResult#DISABLED} — global kill-switch ({@code notifications.email.enabled=
   *       false}); SMTP not touched.
   *   <li>{@link DispatchResult#BLOCKED_BY_ALLOWLIST} — recipient doesn't match any allowlist
   *       suffix; SMTP not touched.
   *   <li>{@link DispatchResult#FAILED} — SMTP threw. The exception is logged with the {@code
   *       toAddress} so an operator can troubleshoot.
   * </ul>
   *
   * <p>Callers are responsible for stamping the result onto the {@code notification_deliveries} row
   * (Part 11.7). This method's contract is: enum out, no side effects on the calendar database.
   */
  public DispatchResult send(String toAddress, String subject, String body) {
    if (!props.enabled()) {
      log.debug("Email dispatch disabled; would have sent to {}", toAddress);
      return DispatchResult.DISABLED;
    }
    if (!allowed(toAddress)) {
      log.info(
          "Email dispatch BLOCKED_BY_ALLOWLIST: recipient={} not in allowlist={}",
          toAddress,
          props.devAllowlist());
      return DispatchResult.BLOCKED_BY_ALLOWLIST;
    }
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper =
          new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
      helper.setTo(toAddress);
      helper.setSubject(subject);
      helper.setText(body, true); // HTML body
      mailSender.send(message);
      return DispatchResult.SENT;
    } catch (MailException | jakarta.mail.MessagingException e) {
      log.warn("Email dispatch FAILED for recipient={}: {}", toAddress, e.getMessage());
      return DispatchResult.FAILED;
    }
  }

  /**
   * Empty allowlist → permit-all (prod default). Non-empty allowlist → at least one suffix must
   * match the recipient address. Comparison is case-insensitive so that {@code @CCW.TEST} matches
   * the configured {@code @ccw.test}.
   */
  private boolean allowed(String toAddress) {
    if (toAddress == null) {
      return false;
    }
    if (props.devAllowlist().isEmpty()) {
      return true;
    }
    String lower = toAddress.toLowerCase();
    return props.devAllowlist().stream().map(String::toLowerCase).anyMatch(lower::endsWith);
  }

  /** Outcome of a single {@link #send} call. */
  public enum DispatchResult {
    SENT,
    DISABLED,
    BLOCKED_BY_ALLOWLIST,
    FAILED
  }

  /** Wires the {@link EmailDispatcherProperties @ConfigurationProperties} binding. */
  @Configuration
  @EnableConfigurationProperties(EmailDispatcherProperties.class)
  static class Config {}
}
