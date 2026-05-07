package com.childcarewow.calendar.policy;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.Event;
import com.childcarewow.calendar.exception.ForbiddenException;
import com.childcarewow.calendar.task.Task;

/**
 * Single security gate (locked decision D8 — no RLS). Every controller method calls one of the
 * {@code assertCan(...)} overloads as its first line.
 *
 * <p>The 19-action catalog mirrors the prototype's {@code src/lib/services/policyService.ts}:
 *
 * <ul>
 *   <li><b>Resource-less:</b> {@code event.create}, {@code event.create.schoolType}, {@code
 *       task.view}, {@code task.create}, {@code task.viewAllScope}, {@code holiday.manage}, {@code
 *       holiday.approve}, {@code importantDate.manage}, {@code calendar.softFlag.see}, {@code
 *       addMenu.show}, {@code addMenu.event}, {@code addMenu.task}, {@code addMenu.holiday}, {@code
 *       addMenu.importantDate}, {@code notifications.see}, {@code users.read}.
 *   <li><b>Resource-bearing (Event):</b> {@code event.edit}, {@code event.delete}.
 *   <li><b>Resource-bearing (Task):</b> {@code task.edit}, {@code task.delete}.
 * </ul>
 *
 * Resource-bearing actions called via the resource-less {@link #can(UserPrincipal, String)} default
 * to {@code false} (defensive — the caller forgot to pass the resource).
 */
public interface PolicyService {

  boolean can(UserPrincipal actor, String action);

  boolean can(UserPrincipal actor, String action, Event event);

  boolean can(UserPrincipal actor, String action, Task task);

  default void assertCan(UserPrincipal actor, String action) {
    if (!can(actor, action)) {
      throw new ForbiddenException(action);
    }
  }

  default void assertCan(UserPrincipal actor, String action, Event event) {
    if (!can(actor, action, event)) {
      throw new ForbiddenException(action);
    }
  }

  default void assertCan(UserPrincipal actor, String action, Task task) {
    if (!can(actor, action, task)) {
      throw new ForbiddenException(action);
    }
  }
}
