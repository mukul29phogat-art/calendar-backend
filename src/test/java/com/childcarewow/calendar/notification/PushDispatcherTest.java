package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.childcarewow.calendar.notification.PushDispatcher.DispatchResult;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for {@link PushDispatcher}'s allowlist gate + disabled / no-firebase-bean
 * paths. Uses a Mockito-mocked {@link FirebaseMessaging} so the test runs offline — no real FCM
 * call is ever made. Mirrors {@link EmailDispatcherTest} from Part 11.4.
 *
 * <p><b>Why mock-only.</b> Per the v1 scope, ChildcareWow is web-only — no iOS/Android client
 * exists. Live-fire push has nowhere to send to. The dispatcher class is in place + tested for the
 * day a mobile client ships; the dev allowlist gate proves the architecture-spec-§7.4 mandate is
 * enforced in code.
 */
class PushDispatcherTest {

  private static final String TEST_TOKEN_A =
      "test-token-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-A";
  private static final String TEST_TOKEN_B =
      "test-token-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-B";

  @Test
  void allowlistedTokenGetsSent() throws FirebaseMessagingException {
    FirebaseMessaging fcm = mock(FirebaseMessaging.class);
    when(fcm.send(any(Message.class))).thenReturn("projects/proj/messages/123");

    PushDispatcher dispatcher =
        new PushDispatcher(fcm, new PushDispatcherProperties(true, List.of(TEST_TOKEN_A)));

    DispatchResult result = dispatcher.send(TEST_TOKEN_A, "Welcome", "Hello!");

    assertThat(result).isEqualTo(DispatchResult.SENT);
    verify(fcm, times(1)).send(any(Message.class));
  }

  @Test
  void emptyAllowlistPermitsAllTokens() throws FirebaseMessagingException {
    // Prod default — empty list = no dev gate, every token OK.
    FirebaseMessaging fcm = mock(FirebaseMessaging.class);
    when(fcm.send(any(Message.class))).thenReturn("projects/proj/messages/123");

    PushDispatcher dispatcher =
        new PushDispatcher(fcm, new PushDispatcherProperties(true, List.of()));

    DispatchResult result = dispatcher.send("any-random-token", "Subject", "Body");

    assertThat(result).isEqualTo(DispatchResult.SENT);
    verify(fcm, times(1)).send(any(Message.class));
  }

  @Test
  void nonAllowlistedTokenIsBlockedAndFcmUntouched() throws FirebaseMessagingException {
    FirebaseMessaging fcm = mock(FirebaseMessaging.class);
    PushDispatcher dispatcher =
        new PushDispatcher(fcm, new PushDispatcherProperties(true, List.of(TEST_TOKEN_A)));

    DispatchResult result = dispatcher.send(TEST_TOKEN_B, "Hi", "nope");

    assertThat(result).isEqualTo(DispatchResult.BLOCKED_BY_ALLOWLIST);
    verify(fcm, never()).send(any(Message.class));
  }

  @Test
  void disabledFlagShortCircuitsBeforeFcm() throws FirebaseMessagingException {
    FirebaseMessaging fcm = mock(FirebaseMessaging.class);
    PushDispatcher dispatcher =
        new PushDispatcher(fcm, new PushDispatcherProperties(false, List.of(TEST_TOKEN_A)));

    DispatchResult result = dispatcher.send(TEST_TOKEN_A, "Welcome", "Hello!");

    assertThat(result).isEqualTo(DispatchResult.DISABLED);
    verify(fcm, never()).send(any(Message.class));
  }

  @Test
  void noFirebaseBeanReturnsDisabledEvenWhenPropertyEnabled() throws FirebaseMessagingException {
    // The most important test for the dev/test runtime: enabled=true in config but no
    // FirebaseMessaging bean → dispatcher gracefully returns DISABLED (no NPE, no exception).
    PushDispatcher dispatcher =
        new PushDispatcher(null, new PushDispatcherProperties(true, List.of()));

    DispatchResult result = dispatcher.send(TEST_TOKEN_A, "Welcome", "Hello!");

    assertThat(result).isEqualTo(DispatchResult.DISABLED);
  }

  @Test
  void fcmFailureReturnsFailedNotThrown() throws FirebaseMessagingException {
    // FCM raised a checked exception → dispatcher catches + returns FAILED. No propagation.
    FirebaseMessaging fcm = mock(FirebaseMessaging.class);
    FirebaseMessagingException stub = mock(FirebaseMessagingException.class);
    when(stub.getMessage()).thenReturn("UNREGISTERED");
    doThrow(stub).when(fcm).send(any(Message.class));

    PushDispatcher dispatcher =
        new PushDispatcher(fcm, new PushDispatcherProperties(true, List.of()));

    DispatchResult result = dispatcher.send(TEST_TOKEN_A, "Welcome", "Hello!");

    assertThat(result).isEqualTo(DispatchResult.FAILED);
  }

  @Test
  void runtimeExceptionAlsoReturnsFailedNotThrown() throws FirebaseMessagingException {
    // Defense: a non-FirebaseMessagingException runtime error (e.g. a malformed Message builder)
    // must not propagate past the dispatcher.
    FirebaseMessaging fcm = mock(FirebaseMessaging.class);
    doThrow(new RuntimeException("unexpected")).when(fcm).send(any(Message.class));

    PushDispatcher dispatcher =
        new PushDispatcher(fcm, new PushDispatcherProperties(true, List.of()));

    DispatchResult result = dispatcher.send(TEST_TOKEN_A, "Welcome", "Hello!");

    assertThat(result).isEqualTo(DispatchResult.FAILED);
  }

  @Test
  void nullTokenIsBlocked() throws FirebaseMessagingException {
    FirebaseMessaging fcm = mock(FirebaseMessaging.class);
    PushDispatcher dispatcher =
        new PushDispatcher(fcm, new PushDispatcherProperties(true, List.of(TEST_TOKEN_A)));

    DispatchResult result = dispatcher.send(null, "Welcome", "Hello!");

    assertThat(result).isEqualTo(DispatchResult.BLOCKED_BY_ALLOWLIST);
    verify(fcm, never()).send(any(Message.class));
  }

  @Test
  void isEnabledReflectsBothPropertyAndBeanPresence() {
    FirebaseMessaging fcm = mock(FirebaseMessaging.class);

    assertThat(new PushDispatcher(fcm, new PushDispatcherProperties(true, List.of())).isEnabled())
        .isTrue();
    assertThat(new PushDispatcher(fcm, new PushDispatcherProperties(false, List.of())).isEnabled())
        .isFalse();
    assertThat(new PushDispatcher(null, new PushDispatcherProperties(true, List.of())).isEnabled())
        .isFalse();
    assertThat(new PushDispatcher(null, new PushDispatcherProperties(false, List.of())).isEnabled())
        .isFalse();
  }
}
