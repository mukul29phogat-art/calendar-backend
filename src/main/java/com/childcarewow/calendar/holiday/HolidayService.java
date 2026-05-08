package com.childcarewow.calendar.holiday;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.DuplicateHolidayException;
import com.childcarewow.calendar.platform.PlatformEntityValidator;
import com.childcarewow.calendar.softflag.SoftFlagService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holiday writes. Part 6.1 covers CUSTOM creation only — FEDERAL inserts come from the Nager.Date
 * sync job (Part 6.4) and the approval endpoint (Part 6.5). Edit/delete land in 6.3.
 *
 * <p>The duplicate-rule per playbook line 2719: {@code (school_id, date) WHERE approved=true} —
 * unapproved-federal rows can sit alongside a CUSTOM insert; only an already-approved row blocks.
 */
@Service
public class HolidayService {

  private final HolidayRepository holidayRepo;
  private final PlatformEntityValidator platformValidator;
  private final SoftFlagService softFlagService;
  private final EntityManager em;

  public HolidayService(
      HolidayRepository holidayRepo,
      PlatformEntityValidator platformValidator,
      SoftFlagService softFlagService,
      EntityManager em) {
    this.holidayRepo = holidayRepo;
    this.platformValidator = platformValidator;
    this.softFlagService = softFlagService;
    this.em = em;
  }

  @Transactional
  public HolidayView create(CreateHolidayRequest req, UserPrincipal actor) {
    platformValidator.assertSchoolExists(req.schoolId());

    if (holidayRepo.findApprovedAt(req.schoolId(), req.date()).isPresent()) {
      throw new DuplicateHolidayException(req.date());
    }

    Holiday h = new Holiday();
    h.setOrgId(actor.orgId());
    h.setSchoolId(req.schoolId());
    h.setDate(req.date());
    h.setName(req.name());
    h.setNotes(req.notes());
    h.setSource(HolidaySource.CUSTOM);
    h.setApproved(true);
    h.setApprovedAt(OffsetDateTime.now());
    h.setApprovedByUserId(actor.id());
    h.setCreatedByUserId(actor.id());

    Holiday saved = holidayRepo.saveAndFlush(h);
    em.refresh(saved);

    softFlagService.recomputeForHoliday(saved);

    return HolidayView.fromEntity(saved);
  }
}
