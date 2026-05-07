package com.childcarewow.calendar.notification;

/**
 * Eight values matching the prototype's src/types/index.ts:170-178 exactly. Future additions land
 * in a new migration; do not edit existing values.
 */
public enum NotificationKind {
  EVENT_INVITE,
  EVENT_UPDATED,
  EVENT_CANCELLED,
  TASK_ASSIGNED,
  TASK_UPDATED,
  TASK_DELETED,
  TASK_STATUS_CHANGED,
  TASK_OVERDUE
}
