package com.childcarewow.calendar.task;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.recurrence.OccurrenceSnapshot;
import com.childcarewow.calendar.recurrence.RecurrenceService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service for tasks. Backs:
 *
 * <ul>
 *   <li>{@code /api/v1/calendar} (Part 7.2) — wraps each {@link TaskView} in a {@code
 *       TaskCalendarItem} at the calendar layer.
 *   <li>{@code /api/v1/tasks?…} window list + {@code /api/v1/tasks/{id}} detail (Part 8.3) —
 *       returns {@code TaskView} directly.
 * </ul>
 *
 * <p><b>Visibility (architecture spec §6.2 + D10).</b>
 *
 * <ul>
 *   <li>PARENT: tasks are never visible — short-circuit to empty / 404 before any DB hit.
 *   <li>STAFF: only tasks where {@code assigneeUserId = actor.id()}. Other staff's tasks at the
 *       same school don't show on a staff member's list.
 *   <li>SCHOOL_ADMIN / ORG_ADMIN: no narrowing; all tasks at the requested school.
 * </ul>
 *
 * <p>STAFF narrowing is applied post-query rather than as a SQL filter. The result sets here are
 * typically small (a few dozen tasks per school per month); pushing the assignee filter into the
 * repository is a Series-11 perf concern if profiling shows it matters.
 *
 * <p><b>Recurring expansion.</b> The window read expands recurring rules into per-occurrence {@link
 * TaskView}s via {@link RecurrenceService#expand} + {@link RecurrenceService#projectFor}. The
 * {@code findById} path returns the parent task as-is (no expansion), since the detail view's
 * identity is the task row's id, not a per-occurrence projection.
 */
@Service
public class TaskReadService {

  private final TaskRepository taskRepo;
  private final RecurrenceService recurrenceService;

  public TaskReadService(TaskRepository taskRepo, RecurrenceService recurrenceService) {
    this.taskRepo = taskRepo;
    this.recurrenceService = recurrenceService;
  }

  @Transactional(readOnly = true)
  public List<TaskView> findInWindow(
      UUID schoolId, LocalDate from, LocalDate to, UserPrincipal actor) {
    if (actor == null || actor.role() == Role.PARENT) {
      return List.of();
    }

    boolean staffNarrowing = actor.role() == Role.STAFF;
    UUID staffId = staffNarrowing ? actor.id() : null;
    List<TaskView> views = new ArrayList<>();

    for (Task t : taskRepo.findNonRecurringInWindow(schoolId, from, to)) {
      if (staffNarrowing && !t.getAssigneeUserId().equals(staffId)) {
        continue;
      }
      views.add(TaskView.fromEntity(t));
    }

    for (Task t : taskRepo.findRecurringForSchool(schoolId)) {
      if (staffNarrowing && !t.getAssigneeUserId().equals(staffId)) {
        continue;
      }
      var expansion = recurrenceService.expand(t, from, to);
      for (LocalDate occDate : expansion.occurrences()) {
        OccurrenceSnapshot snap = recurrenceService.projectFor(t, occDate);
        if (snap == null) {
          // Skipped occurrence — projectFor returns null for these. expand() filters them too;
          // the double-check is cheap insurance against a race between expand and projectFor.
          continue;
        }
        views.add(TaskView.fromOccurrence(t, snap));
      }
    }

    return views;
  }

  /**
   * Detail-view lookup for {@code GET /api/v1/tasks/{id}}. Returns 404 (not 403) when the actor
   * can't see the task — never leak existence outside visibility scope. Soft-deleted tasks are also
   * 404.
   *
   * <p>Unlike {@link #findInWindow}, this path does NOT expand recurring tasks into occurrences.
   * The detail view's identity is the task row's id, and overrides (status, dueTime, title) for a
   * specific occurrence date are handled by a separate {@code GET
   * /api/v1/tasks/{id}/occurrence/{date}} surface that lands in a later part.
   */
  @Transactional(readOnly = true)
  public TaskView findById(UUID id, UserPrincipal actor) {
    Task t =
        taskRepo
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(
                () -> new com.childcarewow.calendar.exception.NotFoundException("Task", id));
    if (!isVisibleTo(t, actor)) {
      throw new com.childcarewow.calendar.exception.NotFoundException("Task", id);
    }
    return TaskView.fromEntity(t);
  }

  private static boolean isVisibleTo(Task t, UserPrincipal actor) {
    if (actor == null) {
      return false;
    }
    return switch (actor.role()) {
      case ORG_ADMIN -> actor.orgId().equals(t.getOrgId());
      case SCHOOL_ADMIN -> actor.schoolIds().contains(t.getSchoolId());
      case STAFF -> actor.id().equals(t.getAssigneeUserId());
      case PARENT -> false; // D10: parents never see tasks.
    };
  }
}
