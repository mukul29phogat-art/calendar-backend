package com.childcarewow.calendar.task;

import com.childcarewow.calendar.audit.Audited;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.policy.PolicyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task write endpoints. {@code POST /api/v1/tasks} (Parts 8.1 + 8.2: single + multi-assignee
 * fan-out). Future parts add GET/list (8.3), PUT (8.4), PATCH status (8.5), DELETE (8.6).
 *
 * <p><b>Response shape.</b> {@code POST} always returns a {@code List<TaskView>} — exactly one
 * element for single-assignee, N elements for fan-out. Keeping the response uniform across the two
 * cases avoids shape-shifting on the wire.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

  private final TaskService service;
  private final PolicyService policy;

  public TaskController(TaskService service, PolicyService policy) {
    this.service = service;
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
}
