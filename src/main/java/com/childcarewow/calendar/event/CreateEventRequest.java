package com.childcarewow.calendar.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/events}. Mirrors the prototype's {@code
 * eventsService.ts:78-139} validation rules. Type-specific shape (CUSTOM attendee/student arrays,
 * SCHOOL excludedParticipantIds) is enforced in {@link EventService}, not by bean-validation —
 * cross-field rules are awkward to express as annotations.
 *
 * <p>{@code attendeeUserIds} and {@code studentIds} apply to CUSTOM events (Part 5.2). For
 * CLASSROOM events, the classroom roster is implicit — explicit attendee lists make no sense and
 * are silently ignored.
 */
public record CreateEventRequest(
    @NotNull EventType type,
    @NotNull UUID schoolId,
    @NotBlank @Size(max = 120) String title,
    String description,
    UUID classroomId,
    @NotNull OffsetDateTime startDt,
    @NotNull OffsetDateTime endDt,
    Boolean allDay,
    UUID organizerUserId,
    Boolean inviteParents,
    List<UUID> attendeeUserIds,
    List<UUID> studentIds,
    List<UUID> excludedParticipantIds) {

  /** Defensive: callers may omit the arrays — treat absent as empty. */
  public List<UUID> attendeeUserIds() {
    return attendeeUserIds == null ? List.of() : attendeeUserIds;
  }

  public List<UUID> studentIds() {
    return studentIds == null ? List.of() : studentIds;
  }

  public List<UUID> excludedParticipantIds() {
    return excludedParticipantIds == null ? List.of() : excludedParticipantIds;
  }

  /**
   * Convenience constructor for callers that pre-date the join-table arrays (Part 5.1 tests).
   * Defaults all three to empty.
   */
  public CreateEventRequest(
      EventType type,
      UUID schoolId,
      String title,
      String description,
      UUID classroomId,
      OffsetDateTime startDt,
      OffsetDateTime endDt,
      Boolean allDay,
      UUID organizerUserId,
      Boolean inviteParents) {
    this(
        type,
        schoolId,
        title,
        description,
        classroomId,
        startDt,
        endDt,
        allDay,
        organizerUserId,
        inviteParents,
        null,
        null,
        null);
  }

  /** Convenience constructor for Part 5.2 callers; defaults excludedParticipantIds to empty. */
  public CreateEventRequest(
      EventType type,
      UUID schoolId,
      String title,
      String description,
      UUID classroomId,
      OffsetDateTime startDt,
      OffsetDateTime endDt,
      Boolean allDay,
      UUID organizerUserId,
      Boolean inviteParents,
      List<UUID> attendeeUserIds,
      List<UUID> studentIds) {
    this(
        type,
        schoolId,
        title,
        description,
        classroomId,
        startDt,
        endDt,
        allDay,
        organizerUserId,
        inviteParents,
        attendeeUserIds,
        studentIds,
        null);
  }
}
