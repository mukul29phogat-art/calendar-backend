package com.childcarewow.calendar.policy;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.Event;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.task.Task;
import org.springframework.stereotype.Service;

/**
 * Implements the full action catalog from {@code src/lib/services/policyService.ts:53-121}.
 * CLAUDE.md § 14 mandates 100% coverage on this class — every action branch has positive AND
 * negative tests.
 */
@Service
public class PolicyServiceImpl implements PolicyService {

  // --- resource-less ---------------------------------------------------------

  @Override
  public boolean can(UserPrincipal actor, String action) {
    if (actor == null) {
      return false;
    }
    return switch (action) {
        // Events (resource-less)
      case "event.create" -> nonParent(actor);
      case "event.create.schoolType" -> isAdmin(actor);

        // Resource-bearing actions called without a resource → false (defensive)
      case "event.edit", "event.delete", "task.edit", "task.delete" -> false;

        // Tasks (D10: parents never see tasks)
      case "task.view" -> nonParent(actor);
      case "task.create" -> nonParent(actor);
      case "task.viewAllScope" -> isAdmin(actor);

        // Holidays + important dates
      case "holiday.manage", "holiday.approve", "importantDate.manage" -> isAdmin(actor);

        // Calendar UI
      case "calendar.softFlag.see" -> nonParent(actor);
      case "addMenu.show", "addMenu.event", "addMenu.task" -> nonParent(actor);
      case "addMenu.holiday", "addMenu.importantDate" -> isAdmin(actor);

        // Notifications visible to everyone (parents see event-only notifications;
        // staff/admins see everything they're entitled to per their other actions)
      case "notifications.see" -> true;

      default -> false;
    };
  }

  // --- resource-bearing: Event ----------------------------------------------

  @Override
  public boolean can(UserPrincipal actor, String action, Event event) {
    if (actor == null || event == null) {
      return false;
    }
    return switch (action) {
      case "event.edit", "event.delete" -> canModifyEvent(actor, event);
      default -> can(actor, action); // delegate non-event-specific actions
    };
  }

  private boolean canModifyEvent(UserPrincipal actor, Event event) {
    return switch (actor.role()) {
      case ORG_ADMIN -> true;
      case SCHOOL_ADMIN -> actor.schoolIds().contains(event.getSchoolId());
      case STAFF ->
          switch (event.getType()) {
            case CLASSROOM ->
                event.getClassroomId() != null
                    && actor.classroomIds().contains(event.getClassroomId());
            case CUSTOM -> actor.schoolIds().contains(event.getSchoolId());
            case SCHOOL -> false;
          };
      case PARENT -> false;
    };
  }

  // --- resource-bearing: Task -----------------------------------------------

  @Override
  public boolean can(UserPrincipal actor, String action, Task task) {
    if (actor == null || task == null) {
      return false;
    }
    return switch (action) {
      case "task.edit", "task.delete" -> canModifyTask(actor, task);
      default -> can(actor, action);
    };
  }

  private boolean canModifyTask(UserPrincipal actor, Task task) {
    return switch (actor.role()) {
      case ORG_ADMIN -> true;
      case SCHOOL_ADMIN -> actor.schoolIds().contains(task.getSchoolId());
      case STAFF -> task.getAssigneeUserId() != null && task.getAssigneeUserId().equals(actor.id());
      case PARENT -> false;
    };
  }

  // --- helpers ---------------------------------------------------------------

  private static boolean nonParent(UserPrincipal actor) {
    return actor.role() != Role.PARENT;
  }

  private static boolean isAdmin(UserPrincipal actor) {
    return actor.role() == Role.ORG_ADMIN || actor.role() == Role.SCHOOL_ADMIN;
  }

  // Suppresses an exhaustiveness warning the compiler raises on switch-over-enum
  // even when all branches return — keeps -Werror happy.
  @SuppressWarnings("unused")
  private static EventType ensureEventTypeReferenced() {
    return EventType.CLASSROOM;
  }
}
