package com.childcarewow.calendar.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The {@code tasks} table. Refs to platform-owned entities (orgs, schools, classrooms, users) are
 * bare {@link UUID} fields per D11. {@code recurrenceId} is calendar-owned (real FK in DB to {@code
 * recurrence_rules(id)}) but kept as a bare UUID here for consistency with the rest of the entity.
 */
@Entity
@Table(name = "tasks")
public class Task {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Column(name = "school_id", nullable = false)
  private UUID schoolId;

  @Column(name = "parent_task_group_id")
  private UUID parentTaskGroupId;

  @Column(nullable = false, length = 120)
  private String title;

  @Column private String description;

  @Column(name = "classroom_id")
  private UUID classroomId;

  @Column(name = "assignee_user_id", nullable = false)
  private UUID assigneeUserId;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Column(name = "due_time")
  private LocalTime dueTime;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskStatus status = TaskStatus.TODO;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskPriority priority = TaskPriority.MEDIUM;

  @Column(name = "recurrence_id")
  private UUID recurrenceId;

  @Column(name = "created_by_user_id", nullable = false)
  private UUID createdByUserId;

  @Column(name = "updated_by_user_id")
  private UUID updatedByUserId;

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

  public UUID getParentTaskGroupId() {
    return parentTaskGroupId;
  }

  public void setParentTaskGroupId(UUID parentTaskGroupId) {
    this.parentTaskGroupId = parentTaskGroupId;
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

  public UUID getAssigneeUserId() {
    return assigneeUserId;
  }

  public void setAssigneeUserId(UUID assigneeUserId) {
    this.assigneeUserId = assigneeUserId;
  }

  public LocalDate getDueDate() {
    return dueDate;
  }

  public void setDueDate(LocalDate dueDate) {
    this.dueDate = dueDate;
  }

  public LocalTime getDueTime() {
    return dueTime;
  }

  public void setDueTime(LocalTime dueTime) {
    this.dueTime = dueTime;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public void setStatus(TaskStatus status) {
    this.status = status;
  }

  public TaskPriority getPriority() {
    return priority;
  }

  public void setPriority(TaskPriority priority) {
    this.priority = priority;
  }

  public UUID getRecurrenceId() {
    return recurrenceId;
  }

  public void setRecurrenceId(UUID recurrenceId) {
    this.recurrenceId = recurrenceId;
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
