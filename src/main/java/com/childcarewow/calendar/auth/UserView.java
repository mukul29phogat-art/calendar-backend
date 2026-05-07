package com.childcarewow.calendar.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * Trimmed user shape for the assignee-selector list endpoint. Distinct from the per-request {@link
 * MeView} (which carries the actor's full schoolIds/classroomIds/childStudentIds set): list reads
 * return many users at once, so the joined arrays are dropped to keep the response small. Callers
 * that need the full shape should hit {@code GET /api/v1/auth/me} (only valid for the current actor
 * anyway).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record UserView(UUID id, String name, String email, Role role, String designation) {}
