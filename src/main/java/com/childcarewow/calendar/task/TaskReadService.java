package com.childcarewow.calendar.task;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.calendar.TaskCalendarItem;
import com.childcarewow.calendar.recurrence.OccurrenceSnapshot;
import com.childcarewow.calendar.recurrence.RecurrenceService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service used by the calendar feed to materialize {@link TaskCalendarItem}s for a
 * window. Series 8 will introduce the full {@code TaskService} (CRUD); this class is the slim read
 * path that 7.2 needs without pulling in the write surface ahead of schedule.
 *
 * <p><b>D10: parents never see tasks.</b> A PARENT actor short-circuits to an empty list before any
 * DB hit. STAFF visibility is still broad in 7.2 — the per-role narrowing ("STAFF sees only tasks
 * assigned to them") lands in Part 7.4 as part of the unified role-based visibility pass.
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
  public List<TaskCalendarItem> findInWindow(
      UUID schoolId, LocalDate from, LocalDate to, UserPrincipal actor) {
    if (actor != null && actor.role() == Role.PARENT) {
      return List.of();
    }

    List<TaskCalendarItem> items = new ArrayList<>();

    for (Task t : taskRepo.findNonRecurringInWindow(schoolId, from, to)) {
      items.add(new TaskCalendarItem(t.getDueDate(), TaskView.fromEntity(t)));
    }

    for (Task t : taskRepo.findRecurringForSchool(schoolId)) {
      var expansion = recurrenceService.expand(t, from, to);
      for (LocalDate occDate : expansion.occurrences()) {
        OccurrenceSnapshot snap = recurrenceService.projectFor(t, occDate);
        if (snap == null) {
          // Skipped occurrence — projectFor returns null for these so the calendar doesn't render
          // the date. expand() also filters these, but we double-check here in case of a race.
          continue;
        }
        items.add(new TaskCalendarItem(snap.date(), TaskView.fromOccurrence(t, snap)));
      }
    }

    return items;
  }
}
