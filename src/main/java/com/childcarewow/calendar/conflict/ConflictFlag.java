package com.childcarewow.calendar.conflict;

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

@Entity
@Table(name = "conflict_flags")
public class ConflictFlag {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Column(name = "school_id", nullable = false)
  private UUID schoolId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false)
  private FlaggedEntity entityType;

  @Column(name = "entity_id", nullable = false)
  private UUID entityId;

  @Enumerated(EnumType.STRING)
  @Column(name = "conflict_type", nullable = false)
  private SoftFlagType conflictType;

  @Column(name = "conflicting_entity_id")
  private UUID conflictingEntityId;

  @Column(nullable = false)
  private String message;

  @Column(nullable = false)
  private boolean dismissed = false;

  @Column(name = "dismissed_by_user_id")
  private UUID dismissedByUserId;

  @Column(name = "dismissed_at")
  private OffsetDateTime dismissedAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private OffsetDateTime updatedAt;

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

  public FlaggedEntity getEntityType() {
    return entityType;
  }

  public void setEntityType(FlaggedEntity entityType) {
    this.entityType = entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public void setEntityId(UUID entityId) {
    this.entityId = entityId;
  }

  public SoftFlagType getConflictType() {
    return conflictType;
  }

  public void setConflictType(SoftFlagType conflictType) {
    this.conflictType = conflictType;
  }

  public UUID getConflictingEntityId() {
    return conflictingEntityId;
  }

  public void setConflictingEntityId(UUID conflictingEntityId) {
    this.conflictingEntityId = conflictingEntityId;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public boolean isDismissed() {
    return dismissed;
  }

  public void setDismissed(boolean dismissed) {
    this.dismissed = dismissed;
  }

  public UUID getDismissedByUserId() {
    return dismissedByUserId;
  }

  public void setDismissedByUserId(UUID dismissedByUserId) {
    this.dismissedByUserId = dismissedByUserId;
  }

  public OffsetDateTime getDismissedAt() {
    return dismissedAt;
  }

  public void setDismissedAt(OffsetDateTime dismissedAt) {
    this.dismissedAt = dismissedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
