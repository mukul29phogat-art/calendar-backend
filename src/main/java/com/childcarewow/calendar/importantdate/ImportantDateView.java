package com.childcarewow.calendar.importantdate;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response shape for {@code important_dates} rows. Matches the frontend's {@code ImportantDate}
 * type at {@code src/types/index.ts:121}. The full write surface (POST / PUT / DELETE) is owned by
 * a later series; for Part 7.3 only the read shape exists.
 *
 * <p>Both {@code studentId} and {@code visibleToParents=false} are optional on the wire — the FE
 * stores them as {@code studentId?} / {@code visibleToParents?: boolean}.
 * {@code @JsonInclude(NON_DEFAULT)} would drop {@code visibleToParents=false} too aggressively
 * (it's a meaningful negative state for admins), so this record uses {@code NON_NULL} and emits the
 * boolean explicitly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportantDateView(
    UUID id,
    UUID schoolId,
    LocalDate date,
    String label,
    ImportantKind kind,
    UUID studentId,
    boolean visibleToParents) {

  public static ImportantDateView fromEntity(ImportantDate i) {
    return new ImportantDateView(
        i.getId(),
        i.getSchoolId(),
        i.getDate(),
        i.getLabel(),
        i.getKind(),
        i.getStudentId(),
        i.isVisibleToParents());
  }
}
