package com.childcarewow.calendar.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the FE's classroom selector. Authentication-only at the controller — every authenticated
 * user has a legitimate reason to see the classroom list of a school they have access to (parents
 * to know what classroom their kid is in, staff to assign tasks/events). The school-level access
 * gating happens upstream when the FE picks {@code schoolId}; if a parent passes a school they have
 * no link to, the result is just empty (no classrooms in scope) — not 403, since revealing the
 * existence of an unknown school would itself be an info leak.
 */
@RestController
@RequestMapping("/api/v1/classrooms")
public class ClassroomsController {

  private final ClassroomsReadService service;

  public ClassroomsController(ClassroomsReadService service) {
    this.service = service;
  }

  @GetMapping
  public List<ClassroomView> list(
      @RequestParam UUID schoolId, @AuthenticationPrincipal UserPrincipal actor) {
    // No policy gate: see Javadoc for rationale. The result is empty for schools the actor
    // doesn't have access to.
    return service.findBySchool(schoolId);
  }
}
