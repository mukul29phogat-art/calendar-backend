package com.childcarewow.calendar.holiday;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read shape for holidays. Matches the prototype's {@code Holiday} type. Optional fields are elided
 * via {@link JsonInclude#NON_NULL} so unapproved-federal rows don't ship empty {@code approvedAt}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HolidayView(
    UUID id,
    UUID schoolId,
    LocalDate date,
    String name,
    String notes,
    HolidaySource source,
    boolean approved,
    OffsetDateTime approvedAt,
    UUID approvedByUserId,
    OffsetDateTime createdAt) {

  public static HolidayView fromEntity(Holiday h) {
    return new HolidayView(
        h.getId(),
        h.getSchoolId(),
        h.getDate(),
        h.getName(),
        h.getNotes(),
        h.getSource(),
        h.isApproved(),
        h.getApprovedAt(),
        h.getApprovedByUserId(),
        h.getCreatedAt());
  }
}
