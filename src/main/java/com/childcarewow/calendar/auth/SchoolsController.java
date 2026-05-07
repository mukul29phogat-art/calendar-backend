package com.childcarewow.calendar.auth;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the FE's school switcher. Authentication-only — the visibility scoping is enforced by
 * {@link SchoolsReadService#findVisibleTo} (ORG_ADMIN sees the whole org; everyone else sees only
 * their assigned schools). No additional policy gate: every authenticated user has a list of
 * "their" schools (even parents — who see the schools their children attend).
 */
@RestController
@RequestMapping("/api/v1/schools")
public class SchoolsController {

  private final SchoolsReadService service;

  public SchoolsController(SchoolsReadService service) {
    this.service = service;
  }

  @GetMapping
  public List<SchoolView> list(@AuthenticationPrincipal UserPrincipal actor) {
    return service.findVisibleTo(actor);
  }
}
