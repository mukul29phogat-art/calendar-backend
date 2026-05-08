package com.childcarewow.calendar.holiday;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.DuplicateHolidayException;
import com.childcarewow.calendar.exception.ForbiddenException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.exception.ValidationException;
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

  /**
   * Updates name/notes/date on an existing holiday. Per playbook common-failure-points line 2820,
   * {@code source} and {@code approved} are NOT mutable through this path — those go through the
   * approve endpoint (Part 6.4) and the federal-sync job. If the date moved, {@code
   * recomputeForHoliday} clears + re-inserts flags so events on the OLD date drop their flag and
   * events on the NEW date pick one up.
   *
   * <p>Date-change duplicate check: if moving the holiday to a date that already has another
   * approved holiday for the same school, throws {@link DuplicateHolidayException}.
   */
  @Transactional
  public HolidayView update(UUID id, CreateHolidayRequest req, UserPrincipal actor) {
    Holiday existing =
        holidayRepo
            .findById(id)
            .filter(h -> h.getDeletedAt() == null)
            .orElseThrow(() -> new NotFoundException("Holiday", id));

    if (!existing.getSchoolId().equals(req.schoolId())) {
      // Don't allow re-targeting a holiday to a different school via PUT — that's effectively a
      // delete-and-recreate.
      throw new ValidationException("schoolId", "schoolId is immutable");
    }

    if (!existing.getDate().equals(req.date())
        && holidayRepo
            .findApprovedAt(req.schoolId(), req.date())
            .filter(h -> !h.getId().equals(id))
            .isPresent()) {
      throw new DuplicateHolidayException(req.date());
    }

    existing.setName(req.name());
    existing.setNotes(req.notes());
    existing.setDate(req.date());

    Holiday saved = holidayRepo.saveAndFlush(existing);
    em.refresh(saved);

    softFlagService.recomputeForHoliday(saved);

    return HolidayView.fromEntity(saved);
  }

  /**
   * Soft-deletes the holiday and clears its HOLIDAY flags. Affected events/tasks no longer render
   * the "falls on holiday" banner.
   */
  @Transactional
  public void delete(UUID id) {
    Holiday existing =
        holidayRepo
            .findById(id)
            .filter(h -> h.getDeletedAt() == null)
            .orElseThrow(() -> new NotFoundException("Holiday", id));
    existing.setDeletedAt(OffsetDateTime.now());
    holidayRepo.saveAndFlush(existing);
    softFlagService.removeFlagsForHoliday(id);
  }
}
