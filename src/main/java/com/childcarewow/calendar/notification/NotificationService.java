package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.event.Event;
import com.childcarewow.calendar.task.Task;
import com.childcarewow.calendar.timezone.TimezoneService;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes notification rows on event create/update/delete (architecture spec § 7.4). The actual
 * email/push delivery is Series 11 — this service stops at the {@code notifications} + {@code
 * notification_recipients} write.
 *
 * <p><b>Recipient resolution:</b>
 *
 * <ul>
 *   <li>{@code SCHOOL} → all {@code PARENT}-role users at the school.
 *   <li>{@code CLASSROOM} → parents of non-deleted students in that classroom.
 *   <li>{@code CUSTOM} → coarse rule: all parents at the school. Flagged COPPA-blocking — Part 12.4
 *       narrows to "parents of students in {@code event_students}". Documented in the relevant
 *       resolver method.
 * </ul>
 *
 * <p>Then subtract: any {@code USER} ids in {@code excludedParticipantIds} directly; any {@code
 * STUDENT} ids resolved to their parent user_ids and subtracted too.
 *
 * <p><b>Holiday pause:</b> if an approved holiday at the school covers the event's school-local
 * date, the notification row is written with {@code paused=true} and a {@code pausedReason}.
 * Recipients are still inserted so a future "unpause" job can flip the flag without rebuilding the
 * recipient set.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final NotificationRepository notificationRepo;
  private final NotificationRecipientRepository recipientRepo;
  private final NamedParameterJdbcTemplate platformNamedJdbc;
  private final JdbcTemplate calendarJdbc;
  private final TimezoneService timezoneService;

  public NotificationService(
      NotificationRepository notificationRepo,
      NotificationRecipientRepository recipientRepo,
      @Qualifier("platformNamedJdbcTemplate") NamedParameterJdbcTemplate platformNamedJdbc,
      @Qualifier("calendarJdbcTemplate") JdbcTemplate calendarJdbc,
      TimezoneService timezoneService) {
    this.notificationRepo = notificationRepo;
    this.recipientRepo = recipientRepo;
    this.platformNamedJdbc = platformNamedJdbc;
    this.calendarJdbc = calendarJdbc;
    this.timezoneService = timezoneService;
  }

  // -- public dispatchers ----------------------------------------------------

  /** Writes EVENT_INVITE when {@code inviteParents=true}; otherwise no-op. */
  @Transactional
  public void dispatchEventCreated(Event event) {
    if (event == null || !event.isInviteParents()) {
      return;
    }
    writeWithRecipients(
        event, NotificationKind.EVENT_INVITE, "You're invited to: " + event.getTitle());
  }

  /**
   * Diff prev → next:
   *
   * <ul>
   *   <li>off → on: EVENT_INVITE.
   *   <li>on → off: EVENT_CANCELLED.
   *   <li>on → on: EVENT_UPDATED.
   *   <li>off → off: nothing.
   * </ul>
   */
  @Transactional
  public void dispatchEventUpdated(Event prev, Event next) {
    if (next == null) {
      return;
    }
    boolean prevInvite = prev != null && prev.isInviteParents();
    boolean nextInvite = next.isInviteParents();
    if (!prevInvite && !nextInvite) {
      return;
    }
    NotificationKind kind;
    String msg;
    if (!prevInvite) {
      kind = NotificationKind.EVENT_INVITE;
      msg = "You're invited to: " + next.getTitle();
    } else if (!nextInvite) {
      kind = NotificationKind.EVENT_CANCELLED;
      msg = "Cancelled: " + next.getTitle();
    } else {
      kind = NotificationKind.EVENT_UPDATED;
      msg = "Updated: " + next.getTitle();
    }
    writeWithRecipients(next, kind, msg);
  }

  /** Writes EVENT_CANCELLED when the deleted event had invitees; otherwise no-op. */
  @Transactional
  public void dispatchEventDeleted(Event event) {
    if (event == null || !event.isInviteParents()) {
      return;
    }
    writeWithRecipients(event, NotificationKind.EVENT_CANCELLED, "Cancelled: " + event.getTitle());
  }

  // -- task dispatchers (Part 8.1+) -----------------------------------------

  /**
   * Writes a TASK_ASSIGNED row addressed to the task's assignee. Tasks have a single recipient (the
   * assignee) — unlike events, no fan-out to parents/students. Holiday-pause check still applies
   * via {@link #writeTaskNotification}; in practice a task's due_date is hard-blocked from falling
   * on a holiday (Part 8.1), so the pause path is only reachable when a holiday is created
   * retroactively for the task's date AND a TASK_UPDATED / TASK_STATUS_CHANGED fires after.
   */
  @Transactional
  public void dispatchTaskCreated(Task task) {
    if (task == null) {
      return;
    }
    writeTaskNotification(task, NotificationKind.TASK_ASSIGNED, "Assigned: " + task.getTitle());
  }

  /**
   * Writes a TASK_DELETED row addressed to the (about-to-be-deleted) task's assignee. Mirrors
   * {@code dispatchEventDeleted} — the row is written AFTER the soft-delete commit so the recipient
   * resolution sees the canonical task state. No-op if the task has no assignee.
   */
  @Transactional
  public void dispatchTaskDeleted(Task task) {
    if (task == null) {
      return;
    }
    writeTaskNotification(task, NotificationKind.TASK_DELETED, "Deleted: " + task.getTitle());
  }

  /**
   * Diff-driven dispatcher for task updates.
   *
   * <ul>
   *   <li><b>Assignee changed</b> ({@code prev.assignee != next.assignee}): writes {@code
   *       TASK_ASSIGNED} to the NEW assignee + {@code TASK_UPDATED} to the OLD one (the old
   *       assignee gets a heads-up that the task was reassigned away). When both happen, the
   *       status-change branch is skipped — the assignment notification carries enough context for
   *       the new owner.
   *   <li><b>Status changed to DONE</b> ({@code prev.status != DONE && next.status == DONE}, same
   *       assignee): writes {@code TASK_STATUS_CHANGED} to the assignee. **Only the
   *       transition-to-DONE produces this kind**, matching the FE prototype's {@code
   *       dispatchTaskStatusChanged} in {@code notificationService.ts:251-265} — other status moves
   *       (TODO ↔ IN_PROGRESS, DONE → TODO) fall through to the next branch and surface as {@code
   *       TASK_UPDATED}.
   *   <li><b>Any other meaningful change</b> (title, description, dueDate, dueTime, priority,
   *       classroomId, OR non-DONE status transition): writes {@code TASK_UPDATED} to the assignee.
   *   <li><b>No-op</b> if the prev and next are field-equal on the diff dimensions above. An audit
   *       row still lands for the PUT (via {@code @Audited}), but no notification.
   * </ul>
   */
  @Transactional
  public void dispatchTaskUpdated(Task prev, Task next) {
    if (prev == null || next == null) {
      return;
    }
    UUID prevAssignee = prev.getAssigneeUserId();
    UUID nextAssignee = next.getAssigneeUserId();

    if (!java.util.Objects.equals(prevAssignee, nextAssignee)) {
      // Re-assignment. New assignee gets TASK_ASSIGNED; old one gets a heads-up TASK_UPDATED.
      writeTaskNotification(next, NotificationKind.TASK_ASSIGNED, "Assigned: " + next.getTitle());
      if (prevAssignee != null) {
        // Build a synthetic Task pointing at the OLD assignee for the heads-up. We don't mutate
        // `prev` itself (it's a detached snapshot the caller still holds); construct a fresh row.
        Task heads = synthHandoffNotice(prev, next, prevAssignee);
        writeTaskNotification(
            heads, NotificationKind.TASK_UPDATED, "Reassigned away: " + next.getTitle());
      }
      return;
    }

    // FE prototype contract (notificationService.ts:251-265): TASK_STATUS_CHANGED is reserved for
    // the "marked done" transition. Other status moves are surfaced as TASK_UPDATED.
    boolean transitionedToDone =
        next.getStatus() == com.childcarewow.calendar.task.TaskStatus.DONE
            && prev.getStatus() != com.childcarewow.calendar.task.TaskStatus.DONE;
    if (transitionedToDone) {
      writeTaskNotification(
          next, NotificationKind.TASK_STATUS_CHANGED, "Marked done: " + next.getTitle());
      return;
    }

    if (taskMeaningfullyChanged(prev, next)
        || !java.util.Objects.equals(prev.getStatus(), next.getStatus())) {
      writeTaskNotification(next, NotificationKind.TASK_UPDATED, "Updated: " + next.getTitle());
    }
  }

  /**
   * Builds a non-persisted Task carrying the OLD assignee but the NEW row's identity (id, org,
   * school, dueDate, title). Used by {@link #dispatchTaskUpdated} so the "reassigned away"
   * notification renders correctly to the prior assignee without re-querying the DB.
   */
  private static Task synthHandoffNotice(Task prev, Task next, UUID oldAssignee) {
    Task t = new Task();
    t.setId(next.getId());
    t.setOrgId(next.getOrgId());
    t.setSchoolId(next.getSchoolId());
    t.setTitle(prev.getTitle()); // the title the prior assignee remembers
    t.setAssigneeUserId(oldAssignee);
    t.setDueDate(next.getDueDate());
    return t;
  }

  private static boolean taskMeaningfullyChanged(Task prev, Task next) {
    return !java.util.Objects.equals(prev.getTitle(), next.getTitle())
        || !java.util.Objects.equals(prev.getDescription(), next.getDescription())
        || !java.util.Objects.equals(prev.getDueDate(), next.getDueDate())
        || !java.util.Objects.equals(prev.getDueTime(), next.getDueTime())
        || !java.util.Objects.equals(prev.getPriority(), next.getPriority())
        || !java.util.Objects.equals(prev.getClassroomId(), next.getClassroomId());
  }

  /**
   * Single-recipient writer for task notifications. Skips entirely if assignee is null (defensive).
   * Holiday-pause check fires against the task's school + due_date.
   */
  private void writeTaskNotification(Task task, NotificationKind kind, String baseMessage) {
    UUID assignee = task.getAssigneeUserId();
    if (assignee == null) {
      log.debug("Task {} has no assignee; skipping {} notification", task.getId(), kind);
      return;
    }

    String pausedReason = checkTaskPauseReason(task);
    String message =
        pausedReason == null ? baseMessage : "[paused: " + pausedReason + "] " + baseMessage;

    Notification n = new Notification();
    n.setOrgId(task.getOrgId());
    n.setSchoolId(task.getSchoolId());
    n.setKind(kind);
    n.setMessage(message);
    n.setRelatedEntityId(task.getId());
    n.setRelatedEntityTitle(task.getTitle());
    n.setPaused(pausedReason != null);
    n.setPausedReason(pausedReason);
    n.setPayload("{}");
    Notification saved = notificationRepo.save(n);

    NotificationRecipient r = new NotificationRecipient();
    r.setNotificationId(saved.getId());
    r.setUserId(assignee);
    recipientRepo.save(r);
  }

  /**
   * Approved-holiday lookup keyed by the task's school-local {@code due_date} (already a {@link
   * LocalDate}, no instant conversion needed). Returns the formatted pause reason or {@code null}
   * when no holiday blocks.
   */
  private String checkTaskPauseReason(Task task) {
    String name =
        calendarJdbc.query(
            "SELECT name FROM holidays "
                + "WHERE school_id = ? AND date = ? AND approved = true AND deleted_at IS NULL "
                + "LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null,
            task.getSchoolId(),
            task.getDueDate());
    return name == null ? null : "Holiday: " + name;
  }

  // -- core write path -------------------------------------------------------

  private void writeWithRecipients(Event event, NotificationKind kind, String baseMessage) {
    Set<UUID> recipientIds = resolveRecipients(event);
    if (recipientIds.isEmpty()) {
      log.debug(
          "No recipients for event={} kind={}; skipping notification write", event.getId(), kind);
      return;
    }

    String pausedReason = checkPauseReason(event);
    String message =
        pausedReason == null ? baseMessage : "[paused: " + pausedReason + "] " + baseMessage;

    Notification n = new Notification();
    n.setOrgId(event.getOrgId());
    n.setSchoolId(event.getSchoolId());
    n.setKind(kind);
    n.setMessage(message);
    n.setRelatedEntityId(event.getId());
    n.setRelatedEntityTitle(event.getTitle());
    n.setPaused(pausedReason != null);
    n.setPausedReason(pausedReason);
    n.setPayload("{}");
    Notification saved = notificationRepo.save(n);

    for (UUID userId : recipientIds) {
      NotificationRecipient r = new NotificationRecipient();
      r.setNotificationId(saved.getId());
      r.setUserId(userId);
      recipientRepo.save(r);
    }
  }

  /** Looks up an approved, non-deleted holiday on the event's school-local date. */
  private String checkPauseReason(Event event) {
    LocalDate localDate =
        timezoneService.toSchoolLocalDate(event.getStartDt().toInstant(), event.getSchoolId());
    String name =
        calendarJdbc.query(
            "SELECT name FROM holidays "
                + "WHERE school_id = ? AND date = ? AND approved = true AND deleted_at IS NULL "
                + "LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null,
            event.getSchoolId(),
            localDate);
    return name == null ? null : "Holiday: " + name;
  }

  // -- recipient resolution --------------------------------------------------

  Set<UUID> resolveRecipients(Event event) {
    Set<UUID> base = new HashSet<>(baseRecipients(event));
    base.removeAll(excludedRecipients(event.getId()));
    return base;
  }

  /** Type-specific recipient set BEFORE excludedParticipantIds subtraction. */
  private List<UUID> baseRecipients(Event event) {
    return switch (event.getType()) {
      case SCHOOL -> parentsAtSchool(event.getSchoolId());
      case CLASSROOM -> parentsOfStudentsInClassroom(event.getClassroomId());
        // CUSTOM uses the coarse "parents at school" rule. COPPA-blocking — Part 12.4 narrows to
        // the actual event_students roster. Until then, we keep this open and rely on
        // excludedParticipantIds to restrict.
      case CUSTOM -> parentsAtSchool(event.getSchoolId());
    };
  }

  private List<UUID> parentsAtSchool(UUID schoolId) {
    return platformNamedJdbc.queryForList(
        "SELECT u.id FROM users u "
            + "JOIN user_schools us ON us.user_id = u.id "
            + "WHERE u.role = 'PARENT' AND us.school_id = :schoolId",
        new MapSqlParameterSource("schoolId", schoolId),
        UUID.class);
  }

  private List<UUID> parentsOfStudentsInClassroom(UUID classroomId) {
    if (classroomId == null) {
      return List.of();
    }
    return platformNamedJdbc.queryForList(
        "SELECT DISTINCT sp.user_id FROM student_parents sp "
            + "JOIN students s ON s.id = sp.student_id "
            + "WHERE s.classroom_id = :classroomId AND s.deleted_at IS NULL",
        new MapSqlParameterSource("classroomId", classroomId),
        UUID.class);
  }

  /**
   * Resolves the event's excludedParticipantIds (user_ids + student_ids) into a single user_id set
   * to subtract. Student exclusions map to all of that student's parent user_ids.
   */
  private Set<UUID> excludedRecipients(UUID eventId) {
    record Row(UUID id, String type) {}
    List<Row> rows =
        calendarJdbc.query(
            "SELECT participant_id, participant_type FROM event_excluded_participants "
                + "WHERE event_id = ?",
            (rs, n) ->
                new Row(
                    UUID.fromString(rs.getString("participant_id")),
                    rs.getString("participant_type")),
            eventId);
    Set<UUID> userExclusions = new HashSet<>();
    Set<UUID> studentExclusions = new HashSet<>();
    for (Row r : rows) {
      if ("USER".equals(r.type())) {
        userExclusions.add(r.id());
      } else if ("STUDENT".equals(r.type())) {
        studentExclusions.add(r.id());
      }
    }
    Set<UUID> excluded = new HashSet<>(userExclusions);
    if (!studentExclusions.isEmpty()) {
      excluded.addAll(
          platformNamedJdbc.queryForList(
              "SELECT user_id FROM student_parents WHERE student_id IN (:ids)",
              new MapSqlParameterSource("ids", studentExclusions),
              UUID.class));
    }
    return excluded;
  }
}
