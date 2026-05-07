package com.childcarewow.calendar.auth;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable per-request actor record. Sets are defensively copied so callers can't mutate. Fields
 * sourced from JWT claims when present, otherwise from the platform DB (via {@link
 * PlatformUserDirectory}).
 */
public record UserPrincipal(
    UUID id,
    String email,
    Role role,
    UUID orgId,
    Set<UUID> schoolIds,
    Set<UUID> classroomIds,
    Set<UUID> childStudentIds,
    String designation) {

  public UserPrincipal {
    schoolIds = schoolIds == null ? Set.of() : Set.copyOf(schoolIds);
    classroomIds = classroomIds == null ? Set.of() : Set.copyOf(classroomIds);
    childStudentIds = childStudentIds == null ? Set.of() : Set.copyOf(childStudentIds);
  }
}
