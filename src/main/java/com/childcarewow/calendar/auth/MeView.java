package com.childcarewow.calendar.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Set;
import java.util.UUID;

/**
 * JSON response shape for {@code GET /api/v1/auth/me}. Field names match the prototype's {@code
 * User} type in {@code src/types/index.ts:5-14} exactly. Optional fields ({@code designation},
 * {@code classroomIds}, {@code childStudentIds}) are omitted from JSON when null/empty per the
 * frontend's {@code User?} shape — keeps the wire compact for org-admin / school-admin / staff
 * roles that don't have child-student links.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MeView(
    UUID id,
    String name,
    String email,
    Role role,
    String designation,
    Set<UUID> schoolIds,
    Set<UUID> classroomIds,
    Set<UUID> childStudentIds) {

  public static MeView from(UserPrincipal p) {
    return new MeView(
        p.id(),
        p.name(),
        p.email(),
        p.role(),
        p.designation(),
        p.schoolIds(),
        p.classroomIds(),
        p.childStudentIds());
  }
}
