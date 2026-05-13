package com.childcarewow.calendar.notification;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Sends push notifications via the Firebase Cloud Messaging admin SDK, gated by the dev allowlist
 * (architecture spec §7.4). Mirrors {@link EmailDispatcher}'s shape — same {@link DispatchResult}
 * enum semantics, same allowlist-in-code mandate, same "lowest-level send primitive" scope.
 *
 * <p><b>Web-only v1 caveat.</b> No iOS/Android client exists for ChildcareWow as of this Part.
 * Live-fire push has nowhere to send to. The dispatcher class lands as a mockable primitive +
 * unit-tested with a Mockito-mocked {@link FirebaseMessaging} so the API is in place for the day we
 * ship a mobile client. Web Push (VAPID + service-worker) is a SEPARATE dispatcher class — the
 * browser delivery path doesn't share FCM's transport.
 *
 * <p><b>No orchestrator wire-in in this Part.</b> {@link NotificationDispatchOrchestrator} still
 * iterates only APP + EMAIL channels. Wiring PUSH in needs an FCM-token-by-user resolver (parallel
 * to {@link com.childcarewow.calendar.platform.PlatformUserEmailResolver}), which depends on a
 * device-registration table that doesn't exist yet. When that lands, the orchestrator gets a {@code
 * dispatchPushChannel} method gated by {@link #isEnabled()}.
 *
 * <p><b>Firebase bean lifecycle.</b> The {@link FirebaseMessaging} bean is created only when the
 * prod profile loads {@code notifications.push.firebase.enabled=true} + the service-account JSON
 * from AWS Secrets Manager. Dev / test runtimes have no real bean. The dispatcher's constructor
 * accepts a nullable {@code @Autowired(required=false)} reference so the absence of the bean
 * doesn't break the Spring context — every {@link #send} call returns {@link
 * DispatchResult#DISABLED} when the bean is missing.
 */
@Service
public class PushDispatcher {

  private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

  private final FirebaseMessaging firebaseMessaging;
  private final PushDispatcherProperties props;

  public PushDispatcher(
      @Autowired(required = false) @Nullable FirebaseMessaging firebaseMessaging,
      PushDispatcherProperties props) {
    this.firebaseMessaging = firebaseMessaging;
    this.props = props;
  }

  /**
   * Send {@code body} as a push notification to the device identified by {@code fcmToken}.
   *
   * <ul>
   *   <li>{@link DispatchResult#SENT} — FCM accepted the message.
   *   <li>{@link DispatchResult#DISABLED} — push kill-switch on, OR no {@link FirebaseMessaging}
   *       bean present (dev/test runtime without firebase configured). No FCM call made.
   *   <li>{@link DispatchResult#BLOCKED_BY_ALLOWLIST} — token doesn't match the dev allowlist. No
   *       FCM call made.
   *   <li>{@link DispatchResult#FAILED} — FCM threw. Token may be expired / revoked / invalid;
   *       caller (Series-12 retry orchestration) decides whether to re-attempt.
   * </ul>
   *
   * <p>Mirrors the no-throw contract of {@link EmailDispatcher#send}: exceptions are caught +
   * logged, never propagated.
   */
  public DispatchResult send(String fcmToken, String title, String body) {
    if (!props.enabled() || firebaseMessaging == null) {
      log.debug("Push dispatch disabled; would have sent to token={}", maskToken(fcmToken));
      return DispatchResult.DISABLED;
    }
    if (!allowed(fcmToken)) {
      log.info(
          "Push dispatch BLOCKED_BY_ALLOWLIST: token={} not in allowlist ({} entries)",
          maskToken(fcmToken),
          props.devAllowlist().size());
      return DispatchResult.BLOCKED_BY_ALLOWLIST;
    }
    try {
      Message message =
          Message.builder()
              .setToken(fcmToken)
              .setNotification(
                  com.google.firebase.messaging.Notification.builder()
                      .setTitle(title)
                      .setBody(body)
                      .build())
              .build();
      firebaseMessaging.send(message);
      return DispatchResult.SENT;
    } catch (FirebaseMessagingException ex) {
      log.warn("Push dispatch FAILED for token={}: {}", maskToken(fcmToken), ex.getMessage());
      return DispatchResult.FAILED;
    } catch (RuntimeException ex) {
      // Defense — Firebase SDK is well-behaved but a runtime issue (e.g. malformed message
      // builder) shouldn't propagate past the dispatcher.
      log.warn(
          "Push dispatch FAILED (unchecked) for token={}: {}",
          maskToken(fcmToken),
          ex.getMessage());
      return DispatchResult.FAILED;
    }
  }

  /**
   * Convenience for the future orchestrator wire-in — when push is wired into the channel routing,
   * the orchestrator can gate on this before iterating recipients (avoiding the audit-log noise
   * that would come from writing FAILED rows for every PUSH attempt when no FCM bean exists).
   */
  public boolean isEnabled() {
    return props.enabled() && firebaseMessaging != null;
  }

  /**
   * Empty allowlist → permit-all (prod default). Non-empty allowlist → token must exactly match one
   * of the listed tokens. FCM tokens are opaque 100+ char strings; exact match (not suffix) is the
   * right shape — there's no "domain" concept to match against.
   */
  private boolean allowed(String fcmToken) {
    if (fcmToken == null) {
      return false;
    }
    if (props.devAllowlist().isEmpty()) {
      return true;
    }
    return props.devAllowlist().contains(fcmToken);
  }

  /**
   * Log-safe token rendering. FCM tokens are 100+ chars and shouldn't end up in plaintext logs
   * (they're per-device credentials). Show the last 8 chars for correlation.
   */
  private static String maskToken(String token) {
    if (token == null || token.length() <= 8) {
      return "[***]";
    }
    return "[***]" + token.substring(token.length() - 8);
  }

  /** Same enum as {@link EmailDispatcher.DispatchResult} — kept separate for type clarity. */
  public enum DispatchResult {
    SENT,
    DISABLED,
    BLOCKED_BY_ALLOWLIST,
    FAILED
  }

  /** Wires the {@link PushDispatcherProperties @ConfigurationProperties} binding. */
  @Configuration
  @EnableConfigurationProperties(PushDispatcherProperties.class)
  static class Config {}
}
