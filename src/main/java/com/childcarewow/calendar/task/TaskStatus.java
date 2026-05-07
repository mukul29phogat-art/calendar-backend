package com.childcarewow.calendar.task;

/**
 * Task lifecycle status. Matches the prototype's three values exactly: TODO, IN_PROGRESS, DONE. NOT
 * "COMPLETED" — a Series 8 API serialization test will fail if this drifts.
 */
public enum TaskStatus {
  TODO,
  IN_PROGRESS,
  DONE
}
