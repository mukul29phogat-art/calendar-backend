package com.childcarewow.calendar.importantdate;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.platform.PlatformEntityValidator;
import java.time.OffsetDateTime;
import java.util.UUID;
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

  /** Loads a row for the controller's pre-update / pre-delete policy gate. 404 otherwise. */
  @Transactional(readOnly = true)
  public ImportantDate loadForPolicyCheck(UUID id) {
    return repo.findById(id)
        .filter(x -> x.getDeletedAt() == null)
        .orElseThrow(() -> new NotFoundException("ImportantDate", id));
  }

  /**
   * Updates a row in place. {@code schoolId} is immutable on this path (matches the event/task
   * convention from Parts 5.5 + 8.4 — changing schools is delete-and-recreate). Per-kind required
   * fields still apply: switching to BIRTHDAY without a {@code studentId} → 400.
   */
  @Transactional
  public ImportantDateView update(UUID id, CreateImportantDateRequest req, UserPrincipal actor) {
    ImportantDate existing =
        repo.findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new NotFoundException("ImportantDate", id));

    if (!existing.getSchoolId().equals(req.schoolId())) {
      throw new ValidationException("schoolId", "schoolId is immutable");
    }
    if (req.kind() == ImportantKind.BIRTHDAY && req.studentId() == null) {
      throw new ValidationException("studentId", "studentId is required when kind=BIRTHDAY");
    }
    if (req.studentId() != null && !req.studentId().equals(existing.getStudentId())) {
      platformValidator.assertStudentExists(req.studentId());
    }

    existing.setDate(req.date());
    existing.setLabel(req.label());
    existing.setKind(req.kind());
    existing.setStudentId(req.studentId());
    existing.setVisibleToParents(req.visibleToParentsOrDefault());

    ImportantDate saved = repo.saveAndFlush(existing);
    return ImportantDateView.fromEntity(saved);
  }

  /**
   * Soft-deletes the row. Idempotent on second delete — already-deleted rows surface as 404 on the
   * next call (matches the soft-delete-as-404 read convention from Parts 5.6 + 8.6).
   */
  @Transactional
  public void delete(UUID id, UserPrincipal actor) {
    ImportantDate existing =
        repo.findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new NotFoundException("ImportantDate", id));
    existing.setDeletedAt(OffsetDateTime.now());
    repo.saveAndFlush(existing);
  }
}
