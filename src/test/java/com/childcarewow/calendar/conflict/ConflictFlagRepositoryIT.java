package com.childcarewow.calendar.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ConflictFlagRepositoryIT {

  @Autowired ConflictFlagRepository flags;
  @PersistenceContext EntityManager em;

  @Test
  void roundTripsDoubleBookingPair() {
    UUID orgId = UUID.randomUUID();
    UUID schoolId = UUID.randomUUID();
    UUID eventA = UUID.randomUUID();
    UUID eventB = UUID.randomUUID();

    ConflictFlag aToB = baseFlag(orgId, schoolId);
    aToB.setEntityType(FlaggedEntity.EVENT);
    aToB.setEntityId(eventA);
    aToB.setConflictType(SoftFlagType.DOUBLE_BOOKING);
    aToB.setConflictingEntityId(eventB);
    aToB.setMessage("Overlaps with event " + eventB);

    ConflictFlag bToA = baseFlag(orgId, schoolId);
    bToA.setEntityType(FlaggedEntity.EVENT);
    bToA.setEntityId(eventB);
    bToA.setConflictType(SoftFlagType.DOUBLE_BOOKING);
    bToA.setConflictingEntityId(eventA);
    bToA.setMessage("Overlaps with event " + eventA);

    UUID aToBId = flags.saveAndFlush(aToB).getId();
    UUID bToAId = flags.saveAndFlush(bToA).getId();
    em.clear();

    ConflictFlag readA = flags.findById(aToBId).orElseThrow();
    ConflictFlag readB = flags.findById(bToAId).orElseThrow();
    assertThat(readA.getEntityId()).isEqualTo(eventA);
    assertThat(readA.getConflictingEntityId()).isEqualTo(eventB);
    assertThat(readA.getConflictType()).isEqualTo(SoftFlagType.DOUBLE_BOOKING);
    assertThat(readA.isDismissed()).isFalse();
    assertThat(readB.getEntityId()).isEqualTo(eventB);
    assertThat(readB.getConflictingEntityId()).isEqualTo(eventA);
    // Both rows are findable independently — bidirectionality is service-layer-enforced (§7.3),
    // not a DB constraint, but the round-trip confirms both rows can coexist.
  }

  @Test
  void roundTripsHolidayFlag() {
    UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID schoolId = UUID.fromString("22222222-2222-2222-2222-222222222221");
    UUID flaggedEvent = UUID.randomUUID();
    UUID holidayId = UUID.randomUUID();
    UUID dismisserId = UUID.fromString("33333333-0000-0000-0000-000000000002");
    OffsetDateTime dismissedAt = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    ConflictFlag f = new ConflictFlag();
    f.setOrgId(orgId);
    f.setSchoolId(schoolId);
    f.setEntityType(FlaggedEntity.TASK);
    f.setEntityId(flaggedEvent);
    f.setConflictType(SoftFlagType.HOLIDAY);
    f.setConflictingEntityId(holidayId);
    f.setMessage("Falls on Independence Day (school holiday)");
    f.setDismissed(true);
    f.setDismissedByUserId(dismisserId);
    f.setDismissedAt(dismissedAt);

    UUID id = flags.saveAndFlush(f).getId();
    em.clear();
    ConflictFlag read = flags.findById(id).orElseThrow();

    assertThat(read.getId()).isEqualTo(id);
    assertThat(read.getOrgId()).isEqualTo(orgId);
    assertThat(read.getSchoolId()).isEqualTo(schoolId);
    assertThat(read.getEntityType()).isEqualTo(FlaggedEntity.TASK);
    assertThat(read.getEntityId()).isEqualTo(flaggedEvent);
    assertThat(read.getConflictType()).isEqualTo(SoftFlagType.HOLIDAY);
    assertThat(read.getConflictingEntityId()).isEqualTo(holidayId);
    assertThat(read.getMessage()).isEqualTo("Falls on Independence Day (school holiday)");
    assertThat(read.isDismissed()).isTrue();
    assertThat(read.getDismissedByUserId()).isEqualTo(dismisserId);
    assertThat(read.getDismissedAt()).isEqualTo(dismissedAt);
    assertThat(read.getCreatedAt()).isNotNull();
    assertThat(read.getUpdatedAt()).isNotNull();
  }

  @Test
  void dismissedFlagsExcludedFromActiveFinder() {
    UUID orgId = UUID.randomUUID();
    UUID schoolId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();

    ConflictFlag active = baseFlag(orgId, schoolId);
    active.setEntityType(FlaggedEntity.EVENT);
    active.setEntityId(entityId);
    active.setConflictType(SoftFlagType.HOLIDAY);
    active.setConflictingEntityId(UUID.randomUUID());
    active.setMessage("active flag");
    active.setDismissed(false);
    UUID activeId = flags.saveAndFlush(active).getId();

    ConflictFlag dismissed = baseFlag(orgId, schoolId);
    dismissed.setEntityType(FlaggedEntity.EVENT);
    dismissed.setEntityId(entityId);
    dismissed.setConflictType(SoftFlagType.DOUBLE_BOOKING);
    dismissed.setConflictingEntityId(UUID.randomUUID());
    dismissed.setMessage("already dismissed");
    dismissed.setDismissed(true);
    flags.saveAndFlush(dismissed);

    em.clear();
    List<ConflictFlag> activeFlags =
        flags.findByEntityTypeAndEntityIdAndDismissedFalse(FlaggedEntity.EVENT, entityId);

    assertThat(activeFlags).hasSize(1);
    assertThat(activeFlags.get(0).getId()).isEqualTo(activeId);
    assertThat(activeFlags.get(0).isDismissed()).isFalse();
  }

  private static ConflictFlag baseFlag(UUID orgId, UUID schoolId) {
    ConflictFlag f = new ConflictFlag();
    f.setOrgId(orgId);
    f.setSchoolId(schoolId);
    f.setMessage("placeholder");
    return f;
  }
}
