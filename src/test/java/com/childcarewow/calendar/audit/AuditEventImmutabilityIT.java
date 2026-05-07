package com.childcarewow.calendar.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.crosscut.AuditEvent;
import com.childcarewow.calendar.crosscut.AuditEventRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the {@code @org.hibernate.annotations.Immutable} guarantee on {@link AuditEvent}:
 * modifications to a previously-persisted row are silently dropped by Hibernate at flush time — no
 * {@code UPDATE} statement is emitted, and the database row remains unchanged.
 *
 * <p>Each step (insert, mutate, reload) runs in its own committed transaction via {@link
 * TransactionTemplate} so the assertions read true DB state, not L1-cache state.
 */
@SpringBootTest
class AuditEventImmutabilityIT {

  @Autowired AuditService service;
  @Autowired AuditEventRepository repo;
  @Autowired TransactionTemplate tx;

  @Test
  void modifyingPersistedRowDoesNotEmitUpdate() {
    UUID actor = UUID.fromString("33333333-0000-0000-0000-000000000001");
    String marker = "IMMUTABLE_TEST_" + UUID.randomUUID();

    // 1) Insert (AuditService already wraps log() in REQUIRES_NEW).
    service.log(actor, marker, "TEST", null, "203.0.113.1", "ua-original", Map.of("v", 1));

    // 2) Locate, mutate, save+flush in its own committed transaction. Hibernate should silently
    // drop the UPDATE because of @Immutable.
    UUID id =
        tx.execute(
            status -> {
              AuditEvent loaded =
                  repo.findAll().stream()
                      .filter(r -> marker.equals(r.getAction()))
                      .findFirst()
                      .orElseThrow();
              loaded.setUserAgent("ua-MUTATED");
              loaded.setIpAddress("198.51.100.99");
              loaded.setMetadata(Map.of("v", 999));
              repo.save(loaded);
              return loaded.getId();
            });

    // 3) Reload from a fresh transaction; the original values must survive.
    AuditEvent reloaded = repo.findById(id).orElseThrow();
    assertThat(reloaded.getUserAgent())
        .as("@Immutable must drop the UPDATE — original user_agent survives")
        .isEqualTo("ua-original");
    assertThat(reloaded.getIpAddress()).isEqualTo("203.0.113.1");
    assertThat(reloaded.getMetadata()).containsEntry("v", 1);

    // Cleanup so the row doesn't pollute later test runs (this DELETE is from a test, not main).
    repo.deleteById(id);
  }
}
