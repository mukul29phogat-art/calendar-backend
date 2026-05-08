package com.childcarewow.calendar.holiday;

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
 * Holiday write endpoints. Part 6.1 covers {@code POST} (CUSTOM); 6.2 adds GETs; 6.3 adds PUT +
 * DELETE; 6.5 adds the federal-approval endpoint.
 */
@RestController
@RequestMapping("/api/v1/holidays")
public class HolidayController {

  private final HolidayService service;
  private final PolicyService policy;

  public HolidayController(HolidayService service, PolicyService policy) {
    this.service = service;
    this.policy = policy;
  }

  @PostMapping
  @Audited(action = "HOLIDAY_CREATE", targetType = "HOLIDAY")
  public ResponseEntity<HolidayView> create(
      @AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody CreateHolidayRequest req) {
    policy.assertCan(actor, "holiday.manage");
    HolidayView created = service.create(req, actor);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }
}
