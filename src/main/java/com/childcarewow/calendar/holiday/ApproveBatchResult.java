package com.childcarewow.calendar.holiday;

import java.util.List;
import java.util.UUID;

/**
 * Response shape for {@code POST /api/v1/holidays/approve-batch}. {@code approved} is the count of
 * holidays moved to {@code approved=true} (excludes already-approved rows from the count). {@code
 * skipped} carries one entry per id that didn't approve, with the reason code matching the {@link
 * com.childcarewow.calendar.exception.ServiceError} envelope codes.
 */
public record ApproveBatchResult(int approved, List<Skip> skipped) {
  public record Skip(UUID id, String reason) {}
}
