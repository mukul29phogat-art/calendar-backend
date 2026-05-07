package com.childcarewow.calendar.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.childcarewow.calendar.crosscut.AuditEvent;
import com.childcarewow.calendar.crosscut.AuditEventRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Round-trips {@link AuditService#log} against the real calendar-db. Validates that the entity
 * mapping (jsonb metadata, inet ip_address, generated id, db-managed created_at) all work.
 */
@SpringBootTest
@Transactional
class AuditServiceIT {

  @Autowired AuditService service;
  @Autowired AuditEventRepository repo;
  @Autowired EntityManager em;

  @Test
  void writesAndReadsBackARow() {
    UUID actor = UUID.fromString("33333333-0000-0000-0000-000000000001");
    UUID target = UUID.fromString("99999999-0000-0000-0000-000000000001");
    String action = "EVENT_CREATED_TEST_" + UUID.randomUUID();

    service.log(
        actor,
        action,
        "EVENT",
        target,
        "203.0.113.7",
        "junit/round-trip",
        Map.of("note", "round-trip"));
    em.flush();
    em.clear();

    List<AuditEvent> rows = repo.findAll();
    AuditEvent saved =
        rows.stream().filter(r -> action.equals(r.getAction())).findFirst().orElse(null);
    assertThat(saved).as("inserted row").isNotNull();
    assertThat(saved.getActorUserId()).isEqualTo(actor);
    assertThat(saved.getTargetType()).isEqualTo("EVENT");
    assertThat(saved.getTargetId()).isEqualTo(target);
    assertThat(saved.getIpAddress()).isEqualTo("203.0.113.7");
    assertThat(saved.getUserAgent()).isEqualTo("junit/round-trip");
    assertThat(saved.getMetadata()).containsEntry("note", "round-trip");
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getId()).isNotNull();
  }

  @Test
  void nullMetadataDefaultsToEmptyMap() {
    UUID actor = UUID.fromString("33333333-0000-0000-0000-000000000001");
    String action = "NULL_META_TEST_" + UUID.randomUUID();

    service.log(actor, action, "TEST", null, null, null, null);
    em.flush();
    em.clear();

    AuditEvent saved =
        repo.findAll().stream().filter(r -> action.equals(r.getAction())).findFirst().orElseThrow();
    assertThat(saved.getMetadata()).isEmpty();
    assertThat(saved.getTargetId()).isNull();
    assertThat(saved.getIpAddress()).isNull();
    assertThat(saved.getUserAgent()).isNull();
  }
}
