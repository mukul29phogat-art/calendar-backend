package com.childcarewow.calendar.task;

import com.childcarewow.calendar.audit.Audited;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.policy.PolicyService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task write + read endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/v1/tasks} (Parts 8.1 + 8.2): single + multi-assignee fan-out. Returns
 *       {@code List<TaskView>} uniformly.
 *   <li>{@code GET /api/v1/tasks?schoolId=&from=&to=} (Part 8.3): tasks window list for the
 *       Tasks-page Kanban + List views. Returns {@code List<TaskView>} with recurring tasks
 *       expanded into per-occurrence entries.
 *   <li>{@code GET /api/v1/tasks/{id}} (Part 8.3): single-task detail. Returns the parent row (no
 *       per-occurrence projection); occurrence overrides surface via a separate endpoint in a later
 *       part.
 * </ul>
 *
 * <p>Future parts add PUT (8.4), PATCH status (8.5), DELETE (8.6), recurrence wire-up (8.7).
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

  private final TaskService service;
  private final TaskReadService readService;
  private final PolicyService policy;

  public TaskController(TaskService service, TaskReadService readService, PolicyService policy) {
    this.service = service;
    this.readService = readService;
    this.policy = policy;
  }

  /**
   * {@code idFrom="[0].id"} resolves the first task's id from the returned list via SpEL — the
   * audit row records the "lead" task id. The per-row activity (one INSERT + recompute + notify per
   * assignee) is implicit in the audit's metadata-less shape; Series-12 polish may extend this to
   * record {@code parent_task_group_id} explicitly when the request fans out.
   */
  @PostMapping
  @Audited(action = "TASK_CREATE", targetType = "TASK", idFrom = "[0].id")
  public ResponseEntity<List<TaskView>> create(
      @AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody CreateTaskRequest req) {
    policy.assertCan(actor, "task.create");
    List<TaskView> created = service.create(req, actor);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  /**
   * Calendar-window read for the Tasks page. Auth-only at the controller — visibility (PARENT empty
   * / STAFF own-only / ADMIN all) is applied in the service.
   */
  @GetMapping
  public List<TaskView> list(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam UUID schoolId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return readService.findInWindow(schoolId, from, to, actor);
  }

  /**
   * Detail-view read. 404 (not 403) when the actor can't see the task — never leak existence
   * outside visibility scope.
   */
  @GetMapping("/{id}")
  public TaskView findById(@AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID id) {
    return readService.findById(id, actor);
  }

  /**
   * Updates a task. Resource-bearing policy gate: the actor must be able to {@code task.edit} THIS
   * specific task — ORG_ADMIN always; SCHOOL_ADMIN if task's school is in their scope; STAFF only
   * if they're the assignee; PARENT never. Loads the task first so the policy check has the entity
   * in hand.
   */
  @PutMapping("/{id}")
  @Audited(action = "TASK_UPDATE", targetType = "TASK")
  public TaskView update(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable UUID id,
      @Valid @RequestBody CreateTaskRequest req) {
    Task existing = service.loadForPolicyCheck(id);
    policy.assertCan(actor, "task.edit", existing);
    return service.update(id, req, actor);
  }
}
