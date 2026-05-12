package com.childcarewow.calendar.importantdate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/important-dates}. Mirrors the frontend's {@code
 * importantDatesService.ts} validation contract.
 *
 * <p><b>Per-kind required fields</b> live in the service ({@code ImportantDateService.create}) —
 * {@code kind=BIRTHDAY} requires {@code studentId}; {@code kind=IMPORTANT} permits it to be null.
 * Bean-validation here covers the unconditionally-required shape only.
 *
 * <p>{@code visibleToParents} defaults to {@code false} per architecture spec §5.5 — admins must
 * explicitly opt-in to surface a row on the parent calendar.
 */
public record CreateImportantDateRequest(
    @NotBlank @Size(max = 120) String label,
    @NotNull LocalDate date,
    @NotNull UUID schoolId,
    @NotNull ImportantKind kind,
    UUID studentId,
    Boolean visibleToParents) {

  /** Defaults visible-to-parents to {@code false}. */
  public boolean visibleToParentsOrDefault() {
    return visibleToParents != null && visibleToParents;
  }
}
