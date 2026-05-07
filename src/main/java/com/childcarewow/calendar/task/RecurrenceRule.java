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
@Table(name = "recurrence_rules")
public class RecurrenceRule {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RecurCycle cycle;

  @Column(name = "due_day_of_week")
  private Short dueDayOfWeek;

  @Column(name = "due_day_of_month")
  private Short dueDayOfMonth;

  @Column(name = "due_time")
  private LocalTime dueTime;

  @Column(name = "until_date", nullable = false)
  private LocalDate untilDate;

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

  public RecurCycle getCycle() {
    return cycle;
  }

  public void setCycle(RecurCycle cycle) {
    this.cycle = cycle;
  }

  public Short getDueDayOfWeek() {
    return dueDayOfWeek;
  }

  public void setDueDayOfWeek(Short dueDayOfWeek) {
    this.dueDayOfWeek = dueDayOfWeek;
  }

  public Short getDueDayOfMonth() {
    return dueDayOfMonth;
  }

  public void setDueDayOfMonth(Short dueDayOfMonth) {
    this.dueDayOfMonth = dueDayOfMonth;
  }

  public LocalTime getDueTime() {
    return dueTime;
  }

  public void setDueTime(LocalTime dueTime) {
    this.dueTime = dueTime;
  }

  public LocalDate getUntilDate() {
    return untilDate;
  }

  public void setUntilDate(LocalDate untilDate) {
    this.untilDate = untilDate;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
