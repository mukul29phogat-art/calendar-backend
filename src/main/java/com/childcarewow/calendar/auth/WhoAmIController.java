package com.childcarewow.calendar.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smoke-test controller proving the JWT auth pipeline + UserPrincipal resolution work end-to-end.
 * Replaced by real domain controllers from Series 4 onward.
 */
@RestController
@RequestMapping("/api/v1")
public class WhoAmIController {

  @GetMapping("/whoami")
  UserPrincipal whoami(@AuthenticationPrincipal UserPrincipal actor) {
    return actor;
  }
}
