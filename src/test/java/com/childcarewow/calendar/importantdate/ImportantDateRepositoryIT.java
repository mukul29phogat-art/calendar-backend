package com.childcarewow.calendar.importantdate;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ImportantDateRepositoryIT {

  @Autowired ImportantDateRepository dates;
  @PersistenceContext EntityManager em;

  @Test
  void roundTripsBirthday() {
    UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID schoolId = UUID.fromString("22222222-2222-2222-2222-222222222221");
    UUID studentId = UUID.fromString("55555555-0000-0000-0000-000000000001");
    UUID createdById = UUID.fromString("33333333-0000-0000-0000-000000000004");

    ImportantDate b = new ImportantDate();
    b.setOrgId(orgId);
    b.setSchoolId(schoolId);
    b.setDate(LocalDate.of(2026, 4, 12));
    b.setLabel("Aanya's birthday");
    b.setKind(ImportantKind.BIRTHDAY);
    b.setStudentId(studentId);
    b.setVisibleToParents(true);
    b.setCreatedByUserId(createdById);

    UUID id = dates.saveAndFlush(b).getId();
    em.clear();
    ImportantDate read = dates.findById(id).orElseThrow();

    assertThat(read.getId()).isEqualTo(id);
    assertThat(read.getOrgId()).isEqualTo(orgId);
    assertThat(read.getSchoolId()).isEqualTo(schoolId);
    assertThat(read.getDate()).isEqualTo(LocalDate.of(2026, 4, 12));
    assertThat(read.getLabel()).isEqualTo("Aanya's birthday");
    assertThat(read.getKind()).isEqualTo(ImportantKind.BIRTHDAY);
    assertThat(read.getStudentId()).isEqualTo(studentId);
    assertThat(read.isVisibleToParents()).isTrue();
    assertThat(read.getCreatedByUserId()).isEqualTo(createdById);
    assertThat(read.getDeletedAt()).isNull();
    assertThat(read.getCreatedAt()).isNotNull();
    assertThat(read.getUpdatedAt()).isNotNull();
  }

  @Test
  void roundTripsImportantWithDefaultParentVisibilityFalse() {
    ImportantDate i = new ImportantDate();
    i.setOrgId(UUID.randomUUID());
    i.setSchoolId(UUID.randomUUID());
    i.setDate(LocalDate.of(2026, 9, 1));
    i.setLabel("First day of school");
    i.setKind(ImportantKind.IMPORTANT);
    // studentId left null
    // visibleToParents intentionally not set — default is false (matches DB DEFAULT false)

    UUID id = dates.saveAndFlush(i).getId();
    em.clear();
    ImportantDate read = dates.findById(id).orElseThrow();

    assertThat(read.getKind()).isEqualTo(ImportantKind.IMPORTANT);
    assertThat(read.getStudentId()).isNull();
    assertThat(read.isVisibleToParents()).isFalse();
  }

  @Test
  void birthdayWithoutStudentIdPermitted() {
    // The architecture spec deliberately does not enforce student_id-required-for-BIRTHDAY at the
    // DB level — service layer is responsible. This test pins that contract: the row inserts
    // cleanly even without a student_id, and we expect any future attempt to add such a CHECK
    // constraint at the DB level to break this test (forcing a deliberate spec re-review).
    ImportantDate orphanBirthday = new ImportantDate();
    orphanBirthday.setOrgId(UUID.randomUUID());
    orphanBirthday.setSchoolId(UUID.randomUUID());
    orphanBirthday.setDate(LocalDate.of(2026, 5, 20));
    orphanBirthday.setLabel("Birthday with no student linked");
    orphanBirthday.setKind(ImportantKind.BIRTHDAY);
    // studentId intentionally null
    orphanBirthday.setVisibleToParents(false);

    UUID id = dates.saveAndFlush(orphanBirthday).getId();
    assertThat(id).isNotNull();

    em.clear();
    ImportantDate read = dates.findById(id).orElseThrow();
    assertThat(read.getKind()).isEqualTo(ImportantKind.BIRTHDAY);
    assertThat(read.getStudentId()).isNull();
  }
}
