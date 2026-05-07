package com.childcarewow.calendar.policy;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.ForbiddenException;

/**
 * Single security gate for the calendar service. Every controller method calls {@link
 * #assertCan(UserPrincipal, String)} as its first line; per locked decision D8, RLS is NOT
 * implemented and PolicyService is the sole enforcement layer.
 *
 * <p>Action strings mirror the prototype's {@code src/lib/services/policyService.ts} catalog. Part
 * 3.1 implements the first three actions: {@code event.create}, {@code task.view}, {@code
 * holiday.manage}. Part 3.2 adds the remaining 16 actions including resource-bearing variants
 * (where the {@code Event} / {@code Task} resource matters for the decision).
 */
public interface PolicyService {

  boolean can(UserPrincipal actor, String action);

  default void assertCan(UserPrincipal actor, String action) {
    if (!can(actor, action)) {
      throw new ForbiddenException(action);
    }
  }
}
