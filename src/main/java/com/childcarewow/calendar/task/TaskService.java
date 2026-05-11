package com.childcarewow.calendar.task;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.exception.TaskOnHolidayException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.notification.NotificationService;
import com.childcarewow.calendar.platform.PlatformEntityValidator;
import com.childcarewow.calendar.softflag.SoftFlagService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns task writes. Part 8.1 implements {@link #create} for the single-assignee case;
 * multi-assignee fan-out (one row per assignee in the request list) lands in Part 8.2.
 *
 * <p>The create flow stitches the same cross-cutting services as event creation: {@link
 * PolicyService} (asserted at the controller), {@link PlatformEntityValidator} (school + classroom
 * + user existence), {@link SoftFlagService} (recompute after save), {@link NotificationService}
 * (TASK_ASSIGNED row addressed to the assignee), and {@link
 * com.childcarewow.calendar.audit.AuditService} (via {@code @Audited} on the controller).
 *
 * <p><b>Holiday block.</b> A task's {@code dueDate} cannot fall on an approved holiday at the
 * task's school. Same hard-block rule as events (architecture spec § 9.4 + locked decision § 5.5).
 */
@Service
public class TaskService {

  private final TaskRepository taskRepo;
  private final PlatformEntityValidator platformValidator;
  private final SoftFlagService softFlagService;
  private final NotificationService notificationService;
  private final JdbcTemplate calendarJdbc;

  @PersistenceContext private EntityManager em;

  public TaskService(
      TaskRepository taskRepo,
      PlatformEntityValidator platformValidator,
      SoftFlagService softFlagService,
      NotificationService notificationService,
      @Qualifier("calendarJdbcTemplate") JdbcTemplate calendarJdbc) {
    this.taskRepo = taskRepo;
    this.platformValidator = platformValidator;
    this.softFlagService = softFlagService;
    this.notificationService = notificationService;
    this.calendarJdbc = calendarJdbc;
  }

  @Transactional
  public TaskView create(CreateTaskRequest req, UserPrincipal actor) {
    validateRequest(req);

    UUID assigneeUserId = req.assigneeUserIds().get(0);

    // Hard-block: due_date cannot fall on an approved holiday at the school.
    String holidayName = findApprovedHolidayName(req.schoolId(), req.dueDate());
    if (holidayName != null) {
      throw new TaskOnHolidayException(holidayName);
    }

    platformValidator.assertSchoolExists(req.schoolId());
    if (req.classroomId() != null) {
      platformValidator.assertClassroomExists(req.classroomId());
      platformValidator.assertClassroomBelongsToSchool(req.classroomId(), req.schoolId());
    }
    platformValidator.assertUserExists(assigneeUserId);

    Task t = new Task();
    t.setOrgId(actor.orgId());
    t.setSchoolId(req.schoolId());
    t.setClassroomId(req.classroomId());
    t.setTitle(req.title());
    t.setDescription(req.description());
    t.setAssigneeUserId(assigneeUserId);
    t.setDueDate(req.dueDate());
    t.setDueTime(req.dueTime());
    t.setStatus(req.statusOrDefault());
    t.setPriority(req.priorityOrDefault());
    t.setCreatedByUserId(actor.id());

    Task saved = taskRepo.saveAndFlush(t);
    em.refresh(saved);

    softFlagService.recomputeForTask(saved.getId());
    notificationService.dispatchTaskCreated(saved);

    return TaskView.fromEntity(saved);
  }

  /**
   * Loads a task for the controller's pre-update policy gate (lands in Part 8.4). 404 otherwise.
   */
  @Transactional(readOnly = true)
  public Task loadForPolicyCheck(UUID id) {
    return taskRepo
        .findById(id)
        .filter(x -> x.getDeletedAt() == null)
        .orElseThrow(() -> new com.childcarewow.calendar.exception.NotFoundException("Task", id));
  }

  // -- validation ------------------------------------------------------------

  private static void validateRequest(CreateTaskRequest req) {
    // Multi-assignee lands in 8.2. For 8.1 the wire shape already accepts a list (so 8.2 doesn't
    // break the API contract), but only size=1 is supported.
    if (req.assigneeUserIds().size() != 1) {
      throw new ValidationException(
          "assigneeUserIds",
          "multi-assignee fan-out lands in Part 8.2; currently exactly one assignee is required");
    }
    if (req.classroomId() == null && req.schoolId() == null) {
      throw new ValidationException("scope", "schoolId is required");
    }
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
