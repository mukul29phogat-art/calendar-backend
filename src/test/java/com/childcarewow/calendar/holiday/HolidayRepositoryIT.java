package com.childcarewow.calendar.holiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
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
class HolidayRepositoryIT {

  @Autowired HolidayRepository holidays;
  @PersistenceContext EntityManager em;

  @Test
  void roundTripsCustomHoliday() {
    UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID schoolId = UUID.fromString("22222222-2222-2222-2222-222222222221");
    UUID approverId = UUID.fromString("33333333-0000-0000-0000-000000000002");
    UUID createdById = UUID.fromString("33333333-0000-0000-0000-000000000004");
    OffsetDateTime approvedAt = OffsetDateTime.of(2026, 5, 15, 14, 30, 0, 0, ZoneOffset.UTC);

    Holiday h = new Holiday();
    h.setOrgId(orgId);
    h.setSchoolId(schoolId);
    h.setDate(LocalDate.of(2026, 7, 4));
    h.setName("Independence Day (school closed)");
    h.setNotes("Closure observed; office staff on call only");
    h.setSource(HolidaySource.CUSTOM);
    h.setApproved(true);
    h.setApprovedByUserId(approverId);
    h.setApprovedAt(approvedAt);
    h.setCreatedByUserId(createdById);

    UUID id = holidays.saveAndFlush(h).getId();
    em.clear();
    Holiday read = holidays.findById(id).orElseThrow();

    assertThat(read.getId()).isEqualTo(id);
    assertThat(read.getOrgId()).isEqualTo(orgId);
    assertThat(read.getSchoolId()).isEqualTo(schoolId);
    assertThat(read.getDate()).isEqualTo(LocalDate.of(2026, 7, 4));
    assertThat(read.getName()).isEqualTo("Independence Day (school closed)");
    assertThat(read.getNotes()).isEqualTo("Closure observed; office staff on call only");
    assertThat(read.getSource()).isEqualTo(HolidaySource.CUSTOM);
    assertThat(read.isApproved()).isTrue();
    assertThat(read.getApprovedByUserId()).isEqualTo(approverId);
    assertThat(read.getApprovedAt()).isEqualTo(approvedAt);
    assertThat(read.getCreatedByUserId()).isEqualTo(createdById);
    assertThat(read.getDeletedAt()).isNull();
    assertThat(read.getCreatedAt()).isNotNull();
    assertThat(read.getUpdatedAt()).isNotNull();
  }

  @Test
  void allowsApprovedCustomAlongsidePendingFederalOnSameDate() {
    UUID schoolId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 11, 26);

    Holiday approvedCustom = baseHoliday(schoolId, date, HolidaySource.CUSTOM);
    approvedCustom.setApproved(true);
    approvedCustom.setName("Thanksgiving (custom)");

    Holiday pendingFederal = baseHoliday(schoolId, date, HolidaySource.FEDERAL);
    pendingFederal.setApproved(false);
    pendingFederal.setName("Thanksgiving (Nager federal sync)");

    holidays.saveAndFlush(approvedCustom);
    holidays.saveAndFlush(pendingFederal); // both partial unique indexes accept this combination

    em.clear();
    long count = holidays.count();
    assertThat(count).isGreaterThanOrEqualTo(2);
  }

  @Test
  void forbidsTwoApprovedHolidaysOnSameDate() {
    UUID schoolId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 12, 25);

    Holiday first = baseHoliday(schoolId, date, HolidaySource.CUSTOM);
    first.setApproved(true);
    first.setName("Christmas Day (custom)");
    holidays.saveAndFlush(first);

    Holiday second = baseHoliday(schoolId, date, HolidaySource.FEDERAL);
    second.setApproved(true);
    second.setName("Christmas Day (federal, also approved)");

    assertThatThrownBy(() -> holidays.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class); // uq_holiday_school_date_approved
  }

  @Test
  void forbidsTwoPendingFederalsOnSameDate() {
    UUID schoolId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 1, 1);

    Holiday first = baseHoliday(schoolId, date, HolidaySource.FEDERAL);
    first.setApproved(false);
    first.setName("New Year's Day (Nager v1)");
    holidays.saveAndFlush(first);

    Holiday second = baseHoliday(schoolId, date, HolidaySource.FEDERAL);
    second.setApproved(false);
    second.setName("New Year's Day (Nager v2)");

    assertThatThrownBy(() -> holidays.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class); // uq_holidays_federal_pending
  }

  private static Holiday baseHoliday(UUID schoolId, LocalDate date, HolidaySource source) {
    Holiday h = new Holiday();
    h.setOrgId(UUID.randomUUID());
    h.setSchoolId(schoolId);
    h.setDate(date);
    h.setName("placeholder");
    h.setSource(source);
    return h;
  }
}
