package com.childcarewow.calendar.crosscut;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CrosscutRepositoryIT {

  @Autowired IdempotencyKeyRepository idempotencyKeys;
  @Autowired AuditEventRepository auditEvents;
  @PersistenceContext EntityManager em;

  @Test
  void idempotencyKeyDefaultExpiresAt24h() {
    IdempotencyKey k = new IdempotencyKey();
    k.setKey("client-supplied-uuid-" + UUID.randomUUID());
    k.setRequestHash("sha256:abcd1234");
    k.setResponseBody("{\"id\":\"event-123\",\"created\":true}");
    k.setStatusCode(201);
    // expires_at intentionally not set — DB default (now()+24h) should apply

    OffsetDateTime before = OffsetDateTime.now();
    // saveAndFlush() with a pre-set @Id calls merge() and returns a NEW managed instance —
    // the original `k` is detached, so em.refresh(k) would throw "Entity not managed".
    IdempotencyKey saved = idempotencyKeys.saveAndFlush(k);
    em.refresh(saved); // pick up DB-side defaults (created_at, expires_at)
    OffsetDateTime after = OffsetDateTime.now();

    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getExpiresAt()).isNotNull();

    Duration sinceCreate = Duration.between(saved.getCreatedAt(), saved.getExpiresAt());
    assertThat(sinceCreate).isCloseTo(Duration.ofHours(24), Duration.ofSeconds(5));

    OffsetDateTime expectedMin = before.plusHours(23).plusMinutes(59);
    OffsetDateTime expectedMax = after.plusHours(24).plusMinutes(1);
    assertThat(saved.getExpiresAt()).isAfter(expectedMin).isBefore(expectedMax);
  }

  @Test
  void auditEventInsertOnly() {
    AuditEvent e = new AuditEvent();
    e.setActorUserId(UUID.fromString("33333333-0000-0000-0000-000000000004"));
    e.setAction("STUDENT_VIEW");
    e.setTargetType("STUDENT");
    e.setTargetId(UUID.fromString("55555555-0000-0000-0000-000000000001"));
    e.setIpAddress("198.51.100.42");
    e.setUserAgent("Mozilla/5.0 (TestRunner)");
    e.setMetadata(Map.of("source", "calendar-detail-modal", "viewedFields", "name,dob"));

    UUID id = auditEvents.saveAndFlush(e).getId();
    em.clear();
    AuditEvent read = auditEvents.findById(id).orElseThrow();

    assertThat(read.getId()).isEqualTo(id);
    assertThat(read.getActorUserId()).isEqualTo(e.getActorUserId());
    assertThat(read.getAction()).isEqualTo("STUDENT_VIEW");
    assertThat(read.getTargetType()).isEqualTo("STUDENT");
    assertThat(read.getTargetId()).isEqualTo(e.getTargetId());
    assertThat(read.getIpAddress()).isEqualTo("198.51.100.42");
    assertThat(read.getUserAgent()).isEqualTo("Mozilla/5.0 (TestRunner)");
    assertThat(read.getMetadata())
        .containsEntry("source", "calendar-detail-modal")
        .containsEntry("viewedFields", "name,dob");
    assertThat(read.getCreatedAt()).isNotNull();
    // Application-layer immutability (@Immutable) lands in Phase 3.4 — no UPDATE/DELETE in code.
    // The schema permits both; the test only verifies INSERT works correctly.
  }
}
