package com.childcarewow.calendar.auth;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smoke-test controller proving the JWT auth pipeline works end-to-end. Real domain controllers
 * land from Series 4 onward.
 */
@RestController
@RequestMapping("/api/v1")
public class WhoAmIController {

  @GetMapping("/whoami")
  Map<String, String> whoami(@AuthenticationPrincipal Jwt jwt) {
    return Map.of("subject", jwt.getSubject());
  }
}
