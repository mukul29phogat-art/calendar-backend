package com.childcarewow.calendar.task;

import com.childcarewow.calendar.audit.Audited;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.policy.PolicyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task write endpoints. {@code POST /api/v1/tasks} (this part — Part 8.1, single-assignee). Future
 * parts add multi-assignee fan-out (8.2), GET/list (8.3), PUT (8.4), PATCH status (8.5), DELETE
 * (8.6).
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

  @PostMapping
  @Audited(action = "TASK_CREATE", targetType = "TASK")
  public ResponseEntity<TaskView> create(
      @AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody CreateTaskRequest req) {
    policy.assertCan(actor, "task.create");
    TaskView created = service.create(req, actor);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }
}
