package com.childcarewow.calendar.event;

/**
 * Event type discriminator. Stored as TEXT with a CHECK constraint in the {@code events} table (not
 * a Postgres ENUM type) — see V2__events.sql for rationale.
 */
public enum EventType {
  CLASSROOM,
  CUSTOM,
  SCHOOL
}
