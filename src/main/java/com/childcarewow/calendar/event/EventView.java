package com.childcarewow.calendar.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response shape for create / read operations on the events table. Matches the prototype's {@code
 * CalendarEvent} type plus {@code softFlags[]} so the FE can render warnings without a second
 * roundtrip.
 *
 * <p>{@code softFlags} is the active (non-dismissed) flag set produced by {@code
 * SoftFlagService.findActiveByEntity}; empty list when the event has none. Optional fields are
 * dropped from the JSON via {@code @JsonInclude(NON_EMPTY)}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record EventView(
    UUID id,
    EventType type,
    UUID schoolId,
    UUID orgId,
    UUID classroomId,
    String title,
    String description,
    OffsetDateTime startDt,
    OffsetDateTime endDt,
    boolean allDay,
    UUID organizerUserId,
    boolean inviteParents,
    UUID createdByUserId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<UUID> attendeeUserIds,
    List<UUID> studentIds,
    List<ExcludedParticipantView> excludedParticipants,
    List<SoftFlagView> softFlags) {

  /** Trimmed flag shape for inline rendering on the event card. */
  public record SoftFlagView(
      UUID id, String conflictType, UUID conflictingEntityId, String message) {}

  /** Tagged exclusion: a single participant_id with its inferred USER/STUDENT type. */
  public record ExcludedParticipantView(UUID participantId, String participantType) {}
}
