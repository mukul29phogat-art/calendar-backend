package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the dual-key wire shape from Part 11.1: {@link NotificationView} must emit BOTH the legacy
 * {@code relatedEventId} / {@code relatedEventTitle} keys AND the generic {@code relatedEntityId} /
 * {@code relatedEntityTitle} keys, both populated from the same column. The FE prototype's {@code
 * Notification} type (predates the generic-entity schema) consumes the legacy keys; future FE work
 * will read the generic keys; both must coexist on the wire.
 */
class NotificationViewSerializationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void emitsBothEventAndEntityKeysWithSameValue() throws Exception {
    UUID notifId = UUID.randomUUID();
    UUID schoolId = UUID.randomUUID();
    UUID relatedId = UUID.randomUUID();

    Notification n = new Notification();
    n.setId(notifId);
    n.setSchoolId(schoolId);
    n.setKind(NotificationKind.EVENT_UPDATED);
    n.setMessage("Title changed: Spring Concert");
    n.setRelatedEntityId(relatedId);
    n.setRelatedEntityTitle("Spring Concert");

    NotificationView view = NotificationView.fromEntity(n, List.of(), List.of());
    String json = MAPPER.writeValueAsString(view);

    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = MAPPER.readValue(json, Map.class);

    // Both legacy ("event") and generic ("entity") keys present.
    assertThat(parsed).containsKey("relatedEventId").containsKey("relatedEntityId");
    assertThat(parsed).containsKey("relatedEventTitle").containsKey("relatedEntityTitle");
    // Same value on both sides.
    assertThat(parsed.get("relatedEventId")).isEqualTo(relatedId.toString());
    assertThat(parsed.get("relatedEntityId")).isEqualTo(relatedId.toString());
    assertThat(parsed.get("relatedEventTitle")).isEqualTo("Spring Concert");
    assertThat(parsed.get("relatedEntityTitle")).isEqualTo("Spring Concert");
  }

  @Test
  void nullRelatedEntityElidesBothKeyVariants() throws Exception {
    Notification n = new Notification();
    n.setId(UUID.randomUUID());
    n.setSchoolId(UUID.randomUUID());
    n.setKind(NotificationKind.EVENT_CANCELLED);
    n.setMessage("Event cancelled (no related entity row in this fixture)");
    n.setRelatedEntityId(null);
    n.setRelatedEntityTitle(null);

    String json = MAPPER.writeValueAsString(NotificationView.fromEntity(n, List.of(), List.of()));
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = MAPPER.readValue(json, Map.class);

    // NON_NULL elision: both id-keys and both title-keys omitted.
    assertThat(parsed)
        .doesNotContainKey("relatedEventId")
        .doesNotContainKey("relatedEntityId")
        .doesNotContainKey("relatedEventTitle")
        .doesNotContainKey("relatedEntityTitle");
  }

  @Test
  void pausedReasonElidedWhenNull() throws Exception {
    Notification n = new Notification();
    n.setId(UUID.randomUUID());
    n.setSchoolId(UUID.randomUUID());
    n.setKind(NotificationKind.EVENT_INVITE);
    n.setMessage("ok");
    n.setPaused(false);
    n.setPausedReason(null);

    String json = MAPPER.writeValueAsString(NotificationView.fromEntity(n, List.of(), List.of()));
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = MAPPER.readValue(json, Map.class);

    assertThat(parsed).containsEntry("paused", Boolean.FALSE);
    assertThat(parsed).doesNotContainKey("pausedReason");
  }

  @Test
  void recipientAndReadByListsRoundTrip() throws Exception {
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    UUID reader = UUID.randomUUID();

    Notification n = new Notification();
    n.setId(UUID.randomUUID());
    n.setSchoolId(UUID.randomUUID());
    n.setKind(NotificationKind.EVENT_INVITE);
    n.setMessage("ok");
    // createdAt is DB-populated (insertable=false); leaving null here is fine — we're checking
    // the recipient/readBy serialization only.

    NotificationView view = NotificationView.fromEntity(n, List.of(r1, r2), List.of(reader));
    String json = MAPPER.writeValueAsString(view);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = MAPPER.readValue(json, Map.class);

    @SuppressWarnings("unchecked")
    List<String> recipients = (List<String>) parsed.get("recipientUserIds");
    @SuppressWarnings("unchecked")
    List<String> readers = (List<String>) parsed.get("readBy");
    assertThat(recipients).containsExactly(r1.toString(), r2.toString());
    assertThat(readers).containsExactly(reader.toString());
  }
}
