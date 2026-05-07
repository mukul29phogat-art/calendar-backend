package com.childcarewow.calendar.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/events}. Mirrors the prototype's {@code
 * eventsService.ts:78-139} validation rules. Type-specific shape (CUSTOM attendee/student arrays,
 * SCHOOL excludedParticipantIds) is enforced in {@link EventService}, not by bean-validation —
 * cross-field rules are awkward to express as annotations.
 *
 * <p>Part 5.1 covers {@link EventType#CLASSROOM} only; CUSTOM and SCHOOL land in 5.2 and 5.3.
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
    Boolean inviteParents) {}
