package com.childcarewow.calendar.importantdate;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.platform.PlatformEntityValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns important-date writes (Part 10.1+). Same cross-cutting layering as the other create flows —
 * {@code PolicyService} (asserted at the controller), {@link PlatformEntityValidator} (school +
 * student existence), {@code AuditService} (via {@code @Audited} on the controller). No soft-flag
 * or notification side-effects — important dates don't participate in overlap rules and don't
 * notify anyone on create.
 *
 * <p><b>Per-kind required fields.</b> {@code kind=BIRTHDAY} requires {@code studentId} (the row
 * means "this student's birthday is on this date"). {@code kind=IMPORTANT} permits {@code
 * studentId} to be null (label-only date — "Picture Day" etc.).
 */
@Service
public class ImportantDateService {

  private final ImportantDateRepository repo;
  private final PlatformEntityValidator platformValidator;

  public ImportantDateService(
      ImportantDateRepository repo, PlatformEntityValidator platformValidator) {
    this.repo = repo;
    this.platformValidator = platformValidator;
  }

  @Transactional
  public ImportantDateView create(CreateImportantDateRequest req, UserPrincipal actor) {
    if (req.kind() == ImportantKind.BIRTHDAY && req.studentId() == null) {
      throw new ValidationException("studentId", "studentId is required when kind=BIRTHDAY");
    }

    platformValidator.assertSchoolExists(req.schoolId());
    if (req.studentId() != null) {
      platformValidator.assertStudentExists(req.studentId());
    }

    ImportantDate row = new ImportantDate();
    row.setOrgId(actor.orgId());
    row.setSchoolId(req.schoolId());
    row.setDate(req.date());
    row.setLabel(req.label());
    row.setKind(req.kind());
    row.setStudentId(req.studentId());
    row.setVisibleToParents(req.visibleToParentsOrDefault());
    row.setCreatedByUserId(actor.id());

    ImportantDate saved = repo.saveAndFlush(row);
    return ImportantDateView.fromEntity(saved);
  }
}
