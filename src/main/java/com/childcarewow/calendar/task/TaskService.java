package com.childcarewow.calendar.task;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.TaskOnHolidayException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.notification.NotificationService;
import com.childcarewow.calendar.platform.PlatformEntityValidator;
import com.childcarewow.calendar.recurrence.RecurrenceService;
import com.childcarewow.calendar.softflag.SoftFlagService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns task writes. Parts 8.1 + 8.2: {@link #create} handles both single-assignee and
 * multi-assignee fan-out — one task row per assignee in the request list.
 *
 * <p>The create flow stitches the same cross-cutting services as event creation: {@link
 * PolicyService} (asserted at the controller), {@link PlatformEntityValidator} (school + classroom
 * + user existence), {@link SoftFlagService} (recompute after save), {@link NotificationService}
 * (TASK_ASSIGNED row addressed to each assignee), and {@link
 * com.childcarewow.calendar.audit.AuditService} (via {@code @Audited} on the controller).
 *
 * <p><b>Holiday block.</b> A task's {@code dueDate} cannot fall on an approved holiday at the
 * task's school. Same hard-block rule as events (architecture spec § 9.4 + locked decision § 5.5).
 * The block runs once per request — rejecting the whole batch atomically.
 *
 * <p><b>Multi-assignee fan-out.</b> When {@code assigneeUserIds.size() > 1}, every row gets the
 * same {@code parent_task_group_id} (a UUID generated at request time), letting later features
 * operate on the group atomically. Single-assignee requests leave the field null per the production
 * schema convention (group_id is only meaningful when there's a group to identify).
 */
@Service
public class TaskService {

  private final TaskRepository taskRepo;
  private final PlatformEntityValidator platformValidator;
  private final SoftFlagService softFlagService;
  private final NotificationService notificationService;
  private final RecurrenceService recurrenceService;
  private final JdbcTemplate calendarJdbc;

  @PersistenceContext private EntityManager em;

  public TaskService(
      TaskRepository taskRepo,
      PlatformEntityValidator platformValidator,
      SoftFlagService softFlagService,
      NotificationService notificationService,
      RecurrenceService recurrenceService,
      @Qualifier("calendarJdbcTemplate") JdbcTemplate calendarJdbc) {
    this.taskRepo = taskRepo;
    this.platformValidator = platformValidator;
    this.softFlagService = softFlagService;
    this.notificationService = notificationService;
    this.recurrenceService = recurrenceService;
    this.calendarJdbc = calendarJdbc;
  }

  @Transactional
  public List<TaskView> create(CreateTaskRequest req, UserPrincipal actor) {
    validateRequest(req);

    List<UUID> assignees = req.assigneeUserIds();

    // Hard-block: due_date cannot fall on an approved holiday. Runs once for the whole batch — a
    // blocked date rejects every row, never partially.
    String holidayName = findApprovedHolidayName(req.schoolId(), req.dueDate());
    if (holidayName != null) {
      throw new TaskOnHolidayException(holidayName);
    }

    // Pre-validate ALL assignees + school/classroom in one shot before any DB writes. The whole
    // method is @Transactional so a mid-loop failure would roll back anyway, but pre-validating
    // means we never half-write rows on a fixable input error.
    platformValidator.assertSchoolExists(req.schoolId());
    if (req.classroomId() != null) {
      platformValidator.assertClassroomExists(req.classroomId());
      platformValidator.assertClassroomBelongsToSchool(req.classroomId(), req.schoolId());
    }
    for (UUID assignee : assignees) {
      platformValidator.assertUserExists(assignee);
    }

    // group_id is only set when there's an actual group (size > 1). Single-assignee tasks leave
    // the field null per the production-schema convention.
    UUID groupId = assignees.size() > 1 ? UUID.randomUUID() : null;

    List<Task> saved = new ArrayList<>(assignees.size());
    for (UUID assignee : assignees) {
      // Per-row recurrence rule creation. Each row gets an independent rule per D9 — N assignees
      // → N rules → N tasks, every (task, rule) pair editable in isolation (Part 9.3+ overrides).
      UUID recurrenceId = null;
      if (req.recurrence() != null) {
        com.childcarewow.calendar.task.RecurrenceRule rule = buildRule(req.recurrence());
        com.childcarewow.calendar.task.RecurrenceRule persistedRule =
            recurrenceService.create(rule, req.dueDate());
        recurrenceId = persistedRule.getId();
      }

      Task t = new Task();
      t.setOrgId(actor.orgId());
      t.setSchoolId(req.schoolId());
      t.setClassroomId(req.classroomId());
      t.setTitle(req.title());
      t.setDescription(req.description());
      t.setAssigneeUserId(assignee);
      t.setDueDate(req.dueDate());
      t.setDueTime(req.dueTime());
      t.setStatus(req.statusOrDefault());
      t.setPriority(req.priorityOrDefault());
      t.setParentTaskGroupId(groupId);
      t.setRecurrenceId(recurrenceId);
      t.setCreatedByUserId(actor.id());

      Task persisted = taskRepo.saveAndFlush(t);
      em.refresh(persisted);
      saved.add(persisted);
    }

    // Per-row side effects after all rows are persisted, so the recompute sees the full set.
    for (Task t : saved) {
      softFlagService.recomputeForTask(t.getId());
      notificationService.dispatchTaskCreated(t);
    }

    return saved.stream().map(TaskView::fromEntity).toList();
  }

  /** Loads a task for the controller's pre-update / pre-delete policy gate. 404 otherwise. */
  @Transactional(readOnly = true)
  public Task loadForPolicyCheck(UUID id) {
    return taskRepo
        .findById(id)
        .filter(x -> x.getDeletedAt() == null)
        .orElseThrow(() -> new com.childcarewow.calendar.exception.NotFoundException("Task", id));
  }

  /**
   * Updates a task in place. Same input shape as create ({@link CreateTaskRequest}); the {@code
   * schoolId} field is immutable on this path (changing schools is a delete-and-recreate, matching
   * the event-update convention from Part 5.5). Multi-assignee group editing is not supported on
   * this path — for 8.4, only single-assignee tasks can be edited; {@code assigneeUserIds.size()}
   * must be 1.
   *
   * <p><b>Holiday block on date-moves only.</b> The holiday SELECT fires only when {@code
   * req.dueDate()} differs from {@code existing.dueDate()}. Same pattern as {@code
   * EventService.update}'s {@code startMoved} gate — same-date title / status / priority edits
   * never re-check the holiday table.
   *
   * <p><b>Soft-flag recompute always runs.</b> Any change to date / assignee / dueTime can
   * introduce or remove same-assignee same-day overlap pairs (Part 3.12). The recompute clears the
   * existing DOUBLE_BOOKING flags involving the task and re-derives them from the post-save state.
   *
   * <p><b>Notification dispatch</b> via {@link NotificationService#dispatchTaskUpdated} captures
   * the diff: status change → TASK_STATUS_CHANGED to the assignee; assignee change → TASK_ASSIGNED
   * to the new assignee + TASK_UPDATED to the old one; any other meaningful change → TASK_UPDATED
   * to the assignee.
   */
  @Transactional
  public TaskView update(UUID id, CreateTaskRequest req, UserPrincipal actor) {
    Task existing =
        taskRepo
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(
                () -> new com.childcarewow.calendar.exception.NotFoundException("Task", id));

    validateUpdateRequest(req);

    if (!existing.getSchoolId().equals(req.schoolId())) {
      throw new ValidationException("schoolId", "schoolId is immutable");
    }

    UUID newAssignee = req.assigneeUserIds().get(0);

    // Date-move gate: only re-check holiday + recompute-trigger when the dueDate actually changes.
    boolean dateMoved = !existing.getDueDate().equals(req.dueDate());
    if (dateMoved) {
      String holidayName = findApprovedHolidayName(req.schoolId(), req.dueDate());
      if (holidayName != null) {
        throw new TaskOnHolidayException(holidayName);
      }
    }

    // Platform validator gates re-run on update (classroom, new assignee). We intentionally do NOT
    // re-validate schoolId since it's immutable above.
    if (req.classroomId() != null) {
      platformValidator.assertClassroomExists(req.classroomId());
      platformValidator.assertClassroomBelongsToSchool(req.classroomId(), req.schoolId());
    }
    platformValidator.assertUserExists(newAssignee);

    // Snapshot via a detached copy BEFORE mutating the managed entity. The dispatcher inspects
    // prev.status / prev.assigneeUserId / prev.title etc. to decide which notification kind to
    // write, so the snapshot must reflect pre-mutation state.
    Task prev = snapshotForDiff(existing);

    existing.setTitle(req.title());
    existing.setDescription(req.description());
    existing.setClassroomId(req.classroomId());
    existing.setAssigneeUserId(newAssignee);
    existing.setDueDate(req.dueDate());
    existing.setDueTime(req.dueTime());
    existing.setStatus(req.statusOrDefault());
    existing.setPriority(req.priorityOrDefault());
    existing.setUpdatedByUserId(actor.id());

    Task saved = taskRepo.saveAndFlush(existing);
    em.refresh(saved);

    softFlagService.recomputeForTask(saved.getId());
    notificationService.dispatchTaskUpdated(prev, saved);

    return TaskView.fromEntity(saved);
  }

  /**
   * Focused status-only mutation for the Kanban drag-and-drop. Smaller surface than {@link
   * #update}: no holiday check (dueDate unchanged), no platform-validator gates (assignee /
   * classroom unchanged), no schoolId guard. Soft-flag recompute STILL runs because the
   * DOUBLE_BOOKING rule (Part 3.12 / {@code findOverlapCandidates}) excludes {@code DONE} tasks — a
   * TODO → DONE transition clears the overlap pair the task was previously contributing to, and
   * DONE → TODO can introduce one.
   *
   * <p>No-op (same status as existing) returns the current view without writing a notification. The
   * audit row still lands via the controller's {@code @Audited}.
   */
  @Transactional
  public TaskView updateStatus(UUID id, TaskStatus newStatus, UserPrincipal actor) {
    Task existing =
        taskRepo
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(
                () -> new com.childcarewow.calendar.exception.NotFoundException("Task", id));
    if (newStatus == null) {
      throw new ValidationException("status", "status is required");
    }
    if (newStatus == existing.getStatus()) {
      // Idempotent no-op — return current view, skip the notification and the recompute.
      return TaskView.fromEntity(existing);
    }

    Task prev = snapshotForDiff(existing);
    existing.setStatus(newStatus);
    existing.setUpdatedByUserId(actor.id());

    Task saved = taskRepo.saveAndFlush(existing);
    em.refresh(saved);

    softFlagService.recomputeForTask(saved.getId());
    notificationService.dispatchTaskUpdated(prev, saved);

    return TaskView.fromEntity(saved);
  }

  /**
   * Apply a recurring-task series edit (Part 9.3+). Dispatches on {@code req.choice()}:
   *
   * <ul>
   *   <li>{@link EditChoice#JUST_THIS} (9.3) — validate the occurrence is in the rule's expansion
   *       window, then upsert into {@code task_instance_overrides}. Master task unchanged.
   *   <li>{@link EditChoice#THIS_AND_FOLLOWING} (9.4) — rejected for now.
   *   <li>{@link EditChoice#ENTIRE_SERIES} (9.5) — rejected for now.
   * </ul>
   *
   * <p>Per the FE prototype's {@code tasksService.ts:328-348}, JUST_THIS does NOT dispatch a
   * notification and does NOT recompute soft flags — overrides are scoped to a single occurrence
   * and don't change the master row's overlap relations. The audit row still lands via the
   * controller's {@code @Audited(TASK_SERIES_EDIT)}.
   */
  @Transactional
  public TaskView applySeriesEdit(UUID id, TaskSeriesEditRequest req, UserPrincipal actor) {
    Task task =
        taskRepo
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(
                () -> new com.childcarewow.calendar.exception.NotFoundException("Task", id));

    if (task.getRecurrenceId() == null) {
      throw new ValidationException("recurrence", "task is not recurring");
    }
    if (req.choice() == EditChoice.THIS_AND_FOLLOWING) {
      throw new ValidationException("choice", "THIS_AND_FOLLOWING lands in Part 9.4");
    }
    if (req.choice() == EditChoice.ENTIRE_SERIES) {
      throw new ValidationException("choice", "ENTIRE_SERIES lands in Part 9.5");
    }

    // JUST_THIS: occurrenceDate must be a date the rule actually produces. Use a single-day
    // expansion window — if the rule emits it, the override is valid.
    com.childcarewow.calendar.recurrence.ExpansionResult exp =
        recurrenceService.expand(task, req.occurrenceDate(), req.occurrenceDate());
    if (!exp.occurrences().contains(req.occurrenceDate())) {
      throw new com.childcarewow.calendar.exception.InvalidRecurrenceException(
          "occurrenceDate is not produced by the task's recurrence rule");
    }

    TaskInstanceOverride incoming = new TaskInstanceOverride();
    incoming.setTaskId(id);
    incoming.setOccurrenceDate(req.occurrenceDate());
    incoming.setTitle(req.title());
    incoming.setDueTime(req.dueTime());
    incoming.setStatus(req.status());
    incoming.setSkipped(req.skipped() != null && req.skipped());
    recurrenceService.upsertOverride(incoming);

    task.setUpdatedByUserId(actor.id());
    taskRepo.saveAndFlush(task);

    return TaskView.fromEntity(task);
  }

  /**
   * Soft-deletes the task. Idempotent on second delete — a row that's already been soft-deleted
   * surfaces as 404 on the next call (matches the read-path behavior from Part 8.3's {@code
   * findById}).
   *
   * <p><b>Soft-flag cleanup is bidirectional.</b> {@link SoftFlagService#removeFlagsForTask} drops
   * DOUBLE_BOOKING flags pointing at this task AND any flags this task was conflicting with.
   * Without that, the surviving task in an overlap pair would keep a stale flag pointing at the
   * deleted task — same posture as event delete from Part 5.6.
   *
   * <p><b>Notification dispatch.</b> {@code dispatchTaskDeleted} writes a TASK_DELETED row
   * addressed to the assignee (a single-recipient kind — tasks are internal). The pause check still
   * applies via the shared {@code writeTaskNotification} helper, even though a hard-blocked dueDate
   * on a holiday shouldn't make it to a delete in practice.
   */
  @Transactional
  public void delete(UUID id, UserPrincipal actor) {
    Task existing =
        taskRepo
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(
                () -> new com.childcarewow.calendar.exception.NotFoundException("Task", id));

    existing.setDeletedAt(java.time.OffsetDateTime.now());
    existing.setUpdatedByUserId(actor.id());
    Task saved = taskRepo.saveAndFlush(existing);

    softFlagService.removeFlagsForTask(saved.getId());
    notificationService.dispatchTaskDeleted(saved);
  }

  /** Detached snapshot of fields the post-update notification diff inspects. */
  private static Task snapshotForDiff(Task t) {
    Task s = new Task();
    s.setId(t.getId());
    s.setOrgId(t.getOrgId());
    s.setSchoolId(t.getSchoolId());
    s.setClassroomId(t.getClassroomId());
    s.setTitle(t.getTitle());
    s.setDescription(t.getDescription());
    s.setAssigneeUserId(t.getAssigneeUserId());
    s.setDueDate(t.getDueDate());
    s.setDueTime(t.getDueTime());
    s.setStatus(t.getStatus());
    s.setPriority(t.getPriority());
    return s;
  }

  private static void validateUpdateRequest(CreateTaskRequest req) {
    if (req.assigneeUserIds() == null || req.assigneeUserIds().isEmpty()) {
      throw new ValidationException("assigneeUserIds", "at least one assignee is required");
    }
    if (req.assigneeUserIds().size() != 1) {
      throw new ValidationException(
          "assigneeUserIds", "PUT supports single-assignee tasks only; group-edit is a later part");
    }
  }

  // -- validation ------------------------------------------------------------

  private static void validateRequest(CreateTaskRequest req) {
    // assigneeUserIds is already @NotEmpty via bean-validation, but defensively check again so
    // direct (non-controller) callers can't slip past.
    if (req.assigneeUserIds() == null || req.assigneeUserIds().isEmpty()) {
      throw new ValidationException("assigneeUserIds", "at least one assignee is required");
    }
    // Dedupe check: the FE prototype's tasksService rejects duplicate assignees in one request to
    // avoid creating two identical tasks for the same user. Match that behavior.
    var distinct = new java.util.HashSet<>(req.assigneeUserIds());
    if (distinct.size() != req.assigneeUserIds().size()) {
      throw new ValidationException(
          "assigneeUserIds", "duplicate assignee ids are not allowed in one request");
    }
  }

  /**
   * Builds a transient {@link RecurrenceRule} from a request spec. Validation lives in {@link
   * RecurrenceService#create} — cycle-shape gates (WEEKLY needs {@code dueDayOfWeek}, MONTHLY needs
   * {@code dueDayOfMonth}, {@code untilDate} ≤ +5 years) all fire there.
   */
  private static RecurrenceRule buildRule(CreateTaskRequest.RecurrenceSpec spec) {
    RecurrenceRule r = new RecurrenceRule();
    r.setCycle(spec.cycle());
    r.setDueDayOfWeek(spec.dueDayOfWeek());
    r.setDueDayOfMonth(spec.dueDayOfMonth());
    r.setDueTime(spec.dueTime());
    r.setUntilDate(spec.untilDate());
    return r;
  }

  /** Approved-holiday lookup for the dueDate block. Mirrors {@code EventService}'s helper. */
  private String findApprovedHolidayName(UUID schoolId, LocalDate dueDate) {
    return calendarJdbc.query(
        "SELECT name FROM holidays "
            + "WHERE school_id = ? AND date = ? AND approved = true AND deleted_at IS NULL "
            + "LIMIT 1",
        rs -> rs.next() ? rs.getString(1) : null,
        schoolId,
        dueDate);
  }
}
