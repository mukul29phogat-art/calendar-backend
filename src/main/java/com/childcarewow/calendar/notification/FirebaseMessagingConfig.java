package com.childcarewow.calendar.notification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.FileInputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prod-profile wiring for the Firebase Admin SDK. Creates {@link FirebaseApp} + {@link
 * FirebaseMessaging} beans from a service-account JSON file path provided via {@code
 * notifications.push.firebase.service-account-path}.
 *
 * <p><b>Conditional creation.</b> Both beans are gated on {@code
 * notifications.push.firebase.enabled=true}. Default {@code false} in {@code application.yml} →
 * neither bean exists in dev/test → {@link PushDispatcher}'s {@code @Autowired(required=false)}
 * reference resolves to {@code null} → every {@code send()} call returns {@code DISABLED}.
 *
 * <p><b>Operator wire-up (Series-11 deploy).</b> The service-account JSON is stored as a LocalStack
 * / AWS Secrets Manager secret ({@code childcarewow-calendar/dev/firebase-service-account} per
 * P0.3). At deploy time, Spring Cloud AWS Secrets Manager (Part 0.8) materializes the secret to a
 * file path and sets {@code notifications.push.firebase.service-account-path} + {@code
 * notifications.push.firebase.enabled=true} via env vars or a per-environment YAML.
 *
 * <p><b>Tests don't exercise this config.</b> {@link PushDispatcherTest} uses a Mockito-mocked
 * {@link FirebaseMessaging} directly — no real Firebase SDK initialization. Integration tests that
 * need a {@code FirebaseMessaging} bean use {@code @MockBean} the same way 11.4's IT mocked {@code
 * JavaMailSender}.
 */
@Configuration
@ConditionalOnProperty(name = "notifications.push.firebase.enabled", havingValue = "true")
class FirebaseMessagingConfig {

  private static final Logger log = LoggerFactory.getLogger(FirebaseMessagingConfig.class);

  @Bean
  FirebaseApp firebaseApp(
      @org.springframework.beans.factory.annotation.Value(
              "${notifications.push.firebase.service-account-path}")
          String serviceAccountPath)
      throws IOException {
    try (FileInputStream serviceAccountStream = new FileInputStream(serviceAccountPath)) {
      FirebaseOptions options =
          FirebaseOptions.builder()
              .setCredentials(GoogleCredentials.fromStream(serviceAccountStream))
              .build();
      log.info("Initializing FirebaseApp from service-account-path={}", serviceAccountPath);
      return FirebaseApp.initializeApp(options);
    }
  }

  @Bean
  FirebaseMessaging firebaseMessaging(FirebaseApp app) {
    return FirebaseMessaging.getInstance(app);
  }
}
