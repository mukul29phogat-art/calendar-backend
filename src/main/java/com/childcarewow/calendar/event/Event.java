package com.childcarewow.calendar.event;

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

/**
 * The {@code events} table. See V2__events.sql for column-level constraints and the rationale for
 * representing {@code type} as TEXT + CHECK rather than a Postgres ENUM.
 *
 * <p>References to platform-owned entities (organizations, schools, classrooms, students, users)
 * are bare {@link UUID} fields without {@code @ManyToOne} mappings — those tables live in a
 * separate platform database and FK constraints can't span databases (locked decision D2/Q1/D11).
 */
@Entity
@Table(name = "events")
public class Event {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Column(name = "school_id", nullable = false)
  private UUID schoolId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EventType type;

  @Column(nullable = false, length = 120)
  private String title;

  @Column private String description;

  @Column(name = "classroom_id")
  private UUID classroomId;

  @Column(name = "start_dt", nullable = false)
  private OffsetDateTime startDt;

  @Column(name = "end_dt", nullable = false)
  private OffsetDateTime endDt;

  @Column(name = "all_day", nullable = false)
  private boolean allDay = false;

  @Column(name = "organizer_user_id")
  private UUID organizerUserId;

  @Column(name = "invite_parents", nullable = false)
  private boolean inviteParents = false;

  @Column(name = "attachment_name")
  private String attachmentName;

  @Column(name = "attachment_url")
  private String attachmentUrl;

  @Column(name = "created_by_user_id", nullable = false)
  private UUID createdByUserId;

  @Column(name = "updated_by_user_id")
  private UUID updatedByUserId;

  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;

  // created_at / updated_at: managed by the database (DEFAULT now() on INSERT). updated_at on
  // UPDATE is the responsibility of a future trigger or application-side @PreUpdate; out of scope
  // for Part 1.1.
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

  public EventType getType() {
    return type;
  }

  public void setType(EventType type) {
    this.type = type;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public UUID getClassroomId() {
    return classroomId;
  }

  public void setClassroomId(UUID classroomId) {
    this.classroomId = classroomId;
  }

  public OffsetDateTime getStartDt() {
    return startDt;
  }

  public void setStartDt(OffsetDateTime startDt) {
    this.startDt = startDt;
  }

  public OffsetDateTime getEndDt() {
    return endDt;
  }

  public void setEndDt(OffsetDateTime endDt) {
    this.endDt = endDt;
  }

  public boolean isAllDay() {
    return allDay;
  }

  public void setAllDay(boolean allDay) {
    this.allDay = allDay;
  }

  public UUID getOrganizerUserId() {
    return organizerUserId;
  }

  public void setOrganizerUserId(UUID organizerUserId) {
    this.organizerUserId = organizerUserId;
  }

  public boolean isInviteParents() {
    return inviteParents;
  }

  public void setInviteParents(boolean inviteParents) {
    this.inviteParents = inviteParents;
  }

  public String getAttachmentName() {
    return attachmentName;
  }

  public void setAttachmentName(String attachmentName) {
    this.attachmentName = attachmentName;
  }

  public String getAttachmentUrl() {
    return attachmentUrl;
  }

  public void setAttachmentUrl(String attachmentUrl) {
    this.attachmentUrl = attachmentUrl;
  }

  public UUID getCreatedByUserId() {
    return createdByUserId;
  }

  public void setCreatedByUserId(UUID createdByUserId) {
    this.createdByUserId = createdByUserId;
  }

  public UUID getUpdatedByUserId() {
    return updatedByUserId;
  }

  public void setUpdatedByUserId(UUID updatedByUserId) {
    this.updatedByUserId = updatedByUserId;
  }

  public OffsetDateTime getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(OffsetDateTime deletedAt) {
    this.deletedAt = deletedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
