package com.childcarewow.calendar.policy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import org.springframework.stereotype.Service;

/**
 * Implements the action catalog from {@code src/lib/services/policyService.ts}. CLAUDE.md § 14
 * mandates 100% line coverage on this class — every action branch must have at least one passing
 * positive test and one denying negative test.
 */
@Service
public class PolicyServiceImpl implements PolicyService {

  @Override
  public boolean can(UserPrincipal actor, String action) {
    if (actor == null) {
      return false;
    }
    return switch (action) {
        // Events
      case "event.create" -> actor.role() != Role.PARENT;

        // Tasks (parents never see tasks — locked decision D10)
      case "task.view" -> actor.role() != Role.PARENT;

        // Holidays
      case "holiday.manage" -> actor.role() == Role.ORG_ADMIN || actor.role() == Role.SCHOOL_ADMIN;

        // Unknown actions deny by default. Throwing here would be tempting but the
        // call sites haven't been written yet — defaulting to false keeps tests
        // green while Part 3.2 fills in the rest of the catalog.
      default -> false;
    };
  }
}
