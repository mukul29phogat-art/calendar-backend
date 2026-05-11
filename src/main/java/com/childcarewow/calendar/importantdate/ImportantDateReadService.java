package com.childcarewow.calendar.importantdate;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.calendar.BirthdayCalendarItem;
import com.childcarewow.calendar.calendar.CalendarItem;
import com.childcarewow.calendar.calendar.ImportantCalendarItem;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service used by the calendar feed (Part 7.3) to materialize {@link
 * BirthdayCalendarItem}s and {@link ImportantCalendarItem}s for a window. The full write surface
 * (POST / PUT / DELETE for important_dates) is owned by a later series; this is the slim read path
 * the calendar feed needs.
 *
 * <p><b>Visibility (architecture spec §6.2 + §7.x).</b>
 *
 * <ul>
 *   <li>ORG_ADMIN / SCHOOL_ADMIN / STAFF: everything in window.
 *   <li>PARENT: {@code visible_to_parents=true} is the gate; additionally for BIRTHDAY rows, the
 *       {@code student_id} must be one of the parent's own children. An IMPORTANT row that's marked
 *       {@code visible_to_parents=true} but is not student-scoped is visible to every parent at the
 *       school.
 * </ul>
 *
 * <p>Both kinds come from one DB query; the kind discriminator splits them into {@link
 * BirthdayCalendarItem} (kind="birthday") vs {@link ImportantCalendarItem} (kind="important") on
 * the wire.
 */
@Service
public class ImportantDateReadService {

  private final ImportantDateRepository repo;

  public ImportantDateReadService(ImportantDateRepository repo) {
    this.repo = repo;
  }

  @Transactional(readOnly = true)
  public List<CalendarItem> findInWindow(
      UUID schoolId, LocalDate from, LocalDate to, UserPrincipal actor) {
    List<ImportantDate> raw = repo.findInWindow(schoolId, from, to);
    List<CalendarItem> items = new ArrayList<>(raw.size());
    for (ImportantDate row : raw) {
      if (!isVisibleTo(row, actor)) {
        continue;
      }
      ImportantDateView view = ImportantDateView.fromEntity(row);
      CalendarItem item =
          row.getKind() == ImportantKind.BIRTHDAY
              ? new BirthdayCalendarItem(row.getDate(), view)
              : new ImportantCalendarItem(row.getDate(), view);
      items.add(item);
    }
    return items;
  }

  private boolean isVisibleTo(ImportantDate row, UserPrincipal actor) {
    if (actor == null) {
      return false;
    }
    if (actor.role() != Role.PARENT) {
      return true;
    }
    // PARENT clamp: visible_to_parents must be true; for birthdays, only own child.
    if (!row.isVisibleToParents()) {
      return false;
    }
    if (row.getKind() == ImportantKind.BIRTHDAY) {
      UUID studentId = row.getStudentId();
      return studentId != null && actor.childStudentIds().contains(studentId);
    }
    // IMPORTANT row marked visible_to_parents: every parent at the school sees it.
    return true;
  }
}
