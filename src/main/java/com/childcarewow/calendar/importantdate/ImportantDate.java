package com.childcarewow.calendar.importantdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "important_dates")
public class ImportantDate {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Column(name = "school_id", nullable = false)
  private UUID schoolId;

  @Column(nullable = false)
  private LocalDate date;

  @Column(nullable = false, length = 120)
  private String label;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ImportantKind kind;

  @Column(name = "student_id")
  private UUID studentId;

  @Column(name = "visible_to_parents", nullable = false)
  private boolean visibleToParents = false;

  @Column(name = "created_by_user_id")
  private UUID createdByUserId;

  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;

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

  public LocalDate getDate() {
    return date;
  }

  public void setDate(LocalDate date) {
    this.date = date;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public ImportantKind getKind() {
    return kind;
  }

  public void setKind(ImportantKind kind) {
    this.kind = kind;
  }

  public UUID getStudentId() {
    return studentId;
  }

  public void setStudentId(UUID studentId) {
    this.studentId = studentId;
  }

  public boolean isVisibleToParents() {
    return visibleToParents;
  }

  public void setVisibleToParents(boolean visibleToParents) {
    this.visibleToParents = visibleToParents;
  }

  public UUID getCreatedByUserId() {
    return createdByUserId;
  }

  public void setCreatedByUserId(UUID createdByUserId) {
    this.createdByUserId = createdByUserId;
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
