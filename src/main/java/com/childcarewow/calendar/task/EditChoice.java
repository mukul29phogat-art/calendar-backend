package com.childcarewow.calendar.task;

/**
 * The user's pick from the recurring-task edit dialog. Matches the FE prototype's {@code
 * EditChoice} type in {@code lib/services/tasksService.ts}.
 *
 * <ul>
 *   <li>{@link #JUST_THIS} (Part 9.3) — upsert a per-occurrence override on this date only.
 *   <li>{@link #THIS_AND_FOLLOWING} (Part 9.4) — shorten the existing rule and create a new task
 *       starting at the split.
 *   <li>{@link #ENTIRE_SERIES} (Part 9.5) — update master + rule in place.
 * </ul>
 */
public enum EditChoice {
  JUST_THIS,
  THIS_AND_FOLLOWING,
  ENTIRE_SERIES
}
