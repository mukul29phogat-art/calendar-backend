package com.childcarewow.calendar.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response shape for {@code GET /api/v1/notifications/me} (Part 11.2). The FE prototype's {@code
 * Notification} type at {@code src/types/index.ts:180} uses {@code relatedEventId} / {@code
 * relatedEventTitle} — those names predate the schema's generic {@code related_entity_*} columns
 * (which now cover events, tasks, and important-dates). Per architecture spec §6.7 + this Part's
 * playbook note, the wire shape emits BOTH the legacy "event"-named keys AND the generic
 * "entity"-named keys with the same value, so the FE's type stays untouched while the column shape
 * stays generic.
 *
 * <p><b>Why a record with duplicated fields, not Jackson aliasing?</b> {@code @JsonAlias} works on
 * deserialization only — it tells Jackson "accept this name as input." It does NOT emit both keys
 * on output. The clean way to emit two keys with the same value is to model both fields in the
 * record and populate them from the same source in {@link #fromEntity}.
 *
 * <p>{@code @JsonInclude(NON_NULL)} elides absent fields (e.g. notifications with no related
 * entity, or no paused reason).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationView(
    UUID id,
    UUID schoolId,
    NotificationKind kind,
    String message,
    UUID relatedEventId,
    UUID relatedEntityId,
    String relatedEventTitle,
    String relatedEntityTitle,
    boolean paused,
    String pausedReason,
    OffsetDateTime createdAt,
    List<UUID> recipientUserIds,
    List<UUID> readBy) {

  /**
   * Builds the view from a {@link Notification} plus the externally-loaded recipient + read-by id
   * lists. Caller (the read service) is responsible for batching those lookups across multiple
   * notifications — this DTO is the projection only.
   */
  public static NotificationView fromEntity(
      Notification n, List<UUID> recipientUserIds, List<UUID> readBy) {
    return new NotificationView(
        n.getId(),
        n.getSchoolId(),
        n.getKind(),
        n.getMessage(),
        n.getRelatedEntityId(),
        n.getRelatedEntityId(),
        n.getRelatedEntityTitle(),
        n.getRelatedEntityTitle(),
        n.isPaused(),
        n.getPausedReason(),
        n.getCreatedAt(),
        recipientUserIds,
        readBy);
  }
}
