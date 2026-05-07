package com.childcarewow.calendar.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional // each test rolls back; calendar-db doesn't accumulate test rows across runs
class EventRepositoryIT {

  @Autowired EventRepository events;
  @PersistenceContext EntityManager em;

  @Test
  void roundTripsClassroomEvent() {
    UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID schoolId = UUID.fromString("22222222-2222-2222-2222-222222222221");
    UUID classroomId = UUID.fromString("44444444-0000-0000-0000-000000000001");
    UUID organizerId = UUID.fromString("33333333-0000-0000-0000-000000000002");
    UUID createdById = UUID.fromString("33333333-0000-0000-0000-000000000004");
    UUID updatedById = UUID.fromString("33333333-0000-0000-0000-000000000005");
    OffsetDateTime start = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    OffsetDateTime end = OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC);
    OffsetDateTime deleted = OffsetDateTime.of(2026, 6, 2, 9, 0, 0, 0, ZoneOffset.UTC);

    Event e = new Event();
    e.setOrgId(orgId);
    e.setSchoolId(schoolId);
    e.setType(EventType.CLASSROOM);
    e.setTitle("Story time");
    e.setDescription("Picture book reading");
    e.setClassroomId(classroomId);
    e.setStartDt(start);
    e.setEndDt(end);
    e.setAllDay(true);
    e.setOrganizerUserId(organizerId);
    e.setInviteParents(true);
    e.setAttachmentName("storybook-cover.jpg");
    e.setAttachmentUrl("https://storage.example.com/attachments/abc.jpg");
    e.setCreatedByUserId(createdById);
    e.setUpdatedByUserId(updatedById);
    e.setDeletedAt(deleted);

    UUID id = events.saveAndFlush(e).getId();
    em.clear(); // evict the L1 cache so findById issues a real SELECT
    Event read = events.findById(id).orElseThrow();

    assertThat(read.getId()).isEqualTo(id);
    assertThat(read.getOrgId()).isEqualTo(orgId);
    assertThat(read.getSchoolId()).isEqualTo(schoolId);
    assertThat(read.getType()).isEqualTo(EventType.CLASSROOM);
    assertThat(read.getTitle()).isEqualTo("Story time");
    assertThat(read.getDescription()).isEqualTo("Picture book reading");
    assertThat(read.getClassroomId()).isEqualTo(classroomId);
    assertThat(read.getStartDt()).isEqualTo(start);
    assertThat(read.getEndDt()).isEqualTo(end);
    assertThat(read.isAllDay()).isTrue();
    assertThat(read.getOrganizerUserId()).isEqualTo(organizerId);
    assertThat(read.isInviteParents()).isTrue();
    assertThat(read.getAttachmentName()).isEqualTo("storybook-cover.jpg");
    assertThat(read.getAttachmentUrl())
        .isEqualTo("https://storage.example.com/attachments/abc.jpg");
    assertThat(read.getCreatedByUserId()).isEqualTo(createdById);
    assertThat(read.getUpdatedByUserId()).isEqualTo(updatedById);
    assertThat(read.getDeletedAt()).isEqualTo(deleted);
    assertThat(read.getCreatedAt()).isNotNull();
    assertThat(read.getUpdatedAt()).isNotNull();
  }

  @Test
  void enforcesEndAfterStart() {
    Event e = new Event();
    e.setOrgId(UUID.randomUUID());
    e.setSchoolId(UUID.randomUUID());
    e.setType(EventType.CUSTOM);
    e.setTitle("Backwards in time");
    OffsetDateTime when = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    e.setStartDt(when);
    e.setEndDt(when.minusMinutes(30)); // end before start violates chk_event_time_range
    e.setCreatedByUserId(UUID.randomUUID());

    assertThatThrownBy(() -> events.saveAndFlush(e))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void requiresClassroomForClassroomType() {
    Event e = new Event();
    e.setOrgId(UUID.randomUUID());
    e.setSchoolId(UUID.randomUUID());
    e.setType(EventType.CLASSROOM);
    e.setTitle("Classroom event with no classroom");
    e.setClassroomId(null); // violates chk_event_classroom_required
    OffsetDateTime when = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    e.setStartDt(when);
    e.setEndDt(when.plusMinutes(30));
    e.setCreatedByUserId(UUID.randomUUID());

    assertThatThrownBy(() -> events.saveAndFlush(e))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
