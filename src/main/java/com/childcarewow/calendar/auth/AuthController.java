package com.childcarewow.calendar.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  /**
   * Returns the authenticated user shaped to match the frontend's {@code User} type. The frontend
   * calls this after login + on every full reload to bootstrap its auth context.
   */
  @GetMapping("/me")
  MeView me(@AuthenticationPrincipal UserPrincipal actor) {
    return MeView.from(actor);
  }
}
