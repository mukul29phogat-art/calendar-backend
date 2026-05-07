package com.childcarewow.calendar.importantdate;

/**
 * What an important_dates row represents. Orthogonal to the {@code visible_to_parents} gate — see
 * V5__important_dates.sql.
 */
public enum ImportantKind {
  BIRTHDAY,
  IMPORTANT
}
