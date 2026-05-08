package com.childcarewow.calendar.holiday;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.DuplicateHolidayException;
import com.childcarewow.calendar.exception.ForbiddenException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.platform.PlatformEntityValidator;
import com.childcarewow.calendar.softflag.SoftFlagService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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

  /**
   * Lists holidays at one school, applying role visibility:
   *
   * <ul>
   *   <li>PARENT: only {@code approved=true} (the {@code approved} query param is ignored — clamped
   *       to true unconditionally per playbook line 2754).
   *   <li>STAFF/ADMIN: respects the {@code approved}/{@code source} query params as-is.
   * </ul>
   *
   * <p>The school-membership check (actor must have access to the requested school) is enforced
   * here too: org-admins see any school in their org; school-scoped roles only see their own.
   */
  @Transactional(readOnly = true)
  public List<HolidayView> findInSchool(
      UUID schoolId, Boolean approved, HolidaySource source, UserPrincipal actor) {
    assertSchoolVisible(actor, schoolId);
    Boolean approvedFilter = (actor.role() == Role.PARENT) ? Boolean.TRUE : approved;
    return holidayRepo.findFiltered(schoolId, approvedFilter, source).stream()
        .map(HolidayView::fromEntity)
        .toList();
  }

  @Transactional(readOnly = true)
  public HolidayView findById(UUID id, UserPrincipal actor) {
    Holiday h =
        holidayRepo
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new NotFoundException("Holiday", id));
    assertSchoolVisible(actor, h.getSchoolId());
    if (actor.role() == Role.PARENT && !h.isApproved()) {
      // Hide unapproved (federal-pending) rows from parents — render as 404 to avoid leakage.
      throw new NotFoundException("Holiday", id);
    }
    return HolidayView.fromEntity(h);
  }

  private void assertSchoolVisible(UserPrincipal actor, UUID schoolId) {
    if (actor.role() == Role.ORG_ADMIN) {
      return;
    }
    if (!actor.schoolIds().contains(schoolId)) {
      throw new ForbiddenException("holiday.read");
    }
  }
}
