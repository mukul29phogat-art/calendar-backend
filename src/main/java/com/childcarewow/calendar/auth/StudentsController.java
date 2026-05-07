package com.childcarewow.calendar.auth;

import com.childcarewow.calendar.audit.AuditRead;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the FE's student selectors (CUSTOM event participants, birthday picker, etc.). Visibility
 * is enforced inside the service: parents only see their own children, regardless of the scope
 * filter passed.
 *
 * <p><b>COPPA audit.</b> Every successful invocation writes one {@code STUDENT_VIEW} row to {@code
 * audit_events} with the full UUID set under {@code metadata.subject_ids} — handled by {@link
 * AuditRead}'s {@code AuditReadAspect}. The aspect fires {@code @AfterReturning} so 403 / 404 /
 * validation failures do NOT generate audit rows (per playbook common-failure-points).
 */
@RestController
@RequestMapping("/api/v1/students")
public class StudentsController {

  private final StudentsReadService service;

  public StudentsController(StudentsReadService service) {
    this.service = service;
  }

  @GetMapping
  @AuditRead(action = "STUDENT_VIEW", subjectsFrom = "![id]")
  public List<StudentView> list(
      @RequestParam(required = false) UUID schoolId,
      @RequestParam(required = false) UUID classroomId,
      @AuthenticationPrincipal UserPrincipal actor) {
    return service.findByScope(schoolId, classroomId, actor);
  }
}
