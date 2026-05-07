package com.childcarewow.calendar.event;

import com.childcarewow.calendar.conflict.ConflictFlag;
import java.util.List;
import java.util.UUID;

/**
 * Manual entity ↔ view mapper. We deliberately don't use MapStruct here despite the playbook's
 * preference — adding MapStruct for a single mapping requires the annotation-processor wiring in
 * pom.xml + maven-compiler-plugin overrides, and the upside is small at one mapping. Switch to
 * MapStruct when Series 6+ adds three or more mappers.
 */
final class EventMapper {

  private EventMapper() {}

  static EventView toView(
      Event entity,
      List<UUID> attendeeUserIds,
      List<UUID> studentIds,
      List<ConflictFlag> activeFlags) {
    List<EventView.SoftFlagView> flagViews =
        activeFlags.stream()
            .map(
                f ->
                    new EventView.SoftFlagView(
                        f.getId(),
                        f.getConflictType().name(),
                        f.getConflictingEntityId(),
                        f.getMessage()))
            .toList();
    return new EventView(
        entity.getId(),
        entity.getType(),
        entity.getSchoolId(),
        entity.getOrgId(),
        entity.getClassroomId(),
        entity.getTitle(),
        entity.getDescription(),
        entity.getStartDt(),
        entity.getEndDt(),
        entity.isAllDay(),
        entity.getOrganizerUserId(),
        entity.isInviteParents(),
        entity.getCreatedByUserId(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        attendeeUserIds == null ? List.of() : attendeeUserIds,
        studentIds == null ? List.of() : studentIds,
        flagViews);
  }
}
