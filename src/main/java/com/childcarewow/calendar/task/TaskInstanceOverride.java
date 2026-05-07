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

@Entity
@Table(name = "task_instance_overrides")
public class TaskInstanceOverride {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(name = "occurrence_date", nullable = false)
  private LocalDate occurrenceDate;

  @Enumerated(EnumType.STRING)
  @Column
  private TaskStatus status; // nullable: NULL means "no status override"

  @Column private String title;

  @Column(name = "due_time")
  private LocalTime dueTime;

  @Column(nullable = false)
  private boolean skipped = false;

  @Column private String notes;

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

  public UUID getTaskId() {
    return taskId;
  }

  public void setTaskId(UUID taskId) {
    this.taskId = taskId;
  }

  public LocalDate getOccurrenceDate() {
    return occurrenceDate;
  }

  public void setOccurrenceDate(LocalDate occurrenceDate) {
    this.occurrenceDate = occurrenceDate;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public void setStatus(TaskStatus status) {
    this.status = status;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public LocalTime getDueTime() {
    return dueTime;
  }

  public void setDueTime(LocalTime dueTime) {
    this.dueTime = dueTime;
  }

  public boolean isSkipped() {
    return skipped;
  }

  public void setSkipped(boolean skipped) {
    this.skipped = skipped;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
