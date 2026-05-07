package com.childcarewow.calendar.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notifications")
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Column(name = "school_id", nullable = false)
  private UUID schoolId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private NotificationKind kind;

  @Column(nullable = false)
  private String message;

  @Column(name = "related_entity_id")
  private UUID relatedEntityId;

  @Column(name = "related_entity_title")
  private String relatedEntityTitle;

  @Column(nullable = false)
  private boolean paused = false;

  @Column(name = "paused_reason")
  private String pausedReason;

  // JSONB column. Stored/read as a JSON string; service code handles serialization.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String payload;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public UUID getSchoolId() {
    return schoolId;
  }

  public void setSchoolId(UUID schoolId) {
    this.schoolId = schoolId;
  }

  public NotificationKind getKind() {
    return kind;
  }

  public void setKind(NotificationKind kind) {
    this.kind = kind;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public UUID getRelatedEntityId() {
    return relatedEntityId;
  }

  public void setRelatedEntityId(UUID relatedEntityId) {
    this.relatedEntityId = relatedEntityId;
  }

  public String getRelatedEntityTitle() {
    return relatedEntityTitle;
  }

  public void setRelatedEntityTitle(String relatedEntityTitle) {
    this.relatedEntityTitle = relatedEntityTitle;
  }

  public boolean isPaused() {
    return paused;
  }

  public void setPaused(boolean paused) {
    this.paused = paused;
  }

  public String getPausedReason() {
    return pausedReason;
  }

  public void setPausedReason(String pausedReason) {
    this.pausedReason = pausedReason;
  }

  public String getPayload() {
    return payload;
  }

  public void setPayload(String payload) {
    this.payload = payload;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
