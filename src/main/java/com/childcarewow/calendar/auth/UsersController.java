package com.childcarewow.calendar.auth;

import com.childcarewow.calendar.policy.PolicyService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the FE's assignee selectors. {@code GET /api/v1/users?schoolId=&role=} returns the users
 * assigned to a school (optionally filtered by role) with just the fields the picker needs.
 *
 * <p>Gated by {@code policyService.assertCan(actor, "users.read")} — admins (ORG/SCHOOL) and STAFF
 * can list users; PARENT cannot (their UI doesn't surface a user picker, and exposing one would let
 * a parent enumerate other parents at their school).
 *
 * <p>Not annotated with {@code @AuditRead}: the listed entities are users, not children. Auditing
 * user-list reads at COPPA cadence would be noise. If we ever need write-side audits (e.g.
 * who-viewed-whom for security review), reach for {@code @Audited} per controller method.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

  private final UsersReadService service;
  private final PolicyService policy;

  public UsersController(UsersReadService service, PolicyService policy) {
    this.service = service;
    this.policy = policy;
  }

  @GetMapping
  public List<UserView> list(
      @RequestParam UUID schoolId,
      @RequestParam(required = false) Role role,
      @AuthenticationPrincipal UserPrincipal actor) {
    policy.assertCan(actor, "users.read");
    return service.findByScope(schoolId, role);
  }
}
