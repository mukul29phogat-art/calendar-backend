package com.childcarewow.calendar.holiday;

import com.childcarewow.calendar.audit.Audited;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.policy.PolicyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  /**
   * Lists holidays at one school with optional {@code approved}/{@code source} filters. PARENT role
   * is forced to {@code approved=true} regardless of the query param (federal-pending rows are
   * never visible to parents — playbook line 2754).
   */
  @GetMapping
  public List<HolidayView> list(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam UUID schoolId,
      @RequestParam(required = false) Boolean approved,
      @RequestParam(required = false) HolidaySource source) {
    return service.findInSchool(schoolId, approved, source, actor);
  }

  @GetMapping("/{id}")
  public HolidayView findById(@AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID id) {
    return service.findById(id, actor);
  }

  @PutMapping("/{id}")
  @Audited(action = "HOLIDAY_UPDATE", targetType = "HOLIDAY")
  public HolidayView update(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable UUID id,
      @Valid @RequestBody CreateHolidayRequest req) {
    policy.assertCan(actor, "holiday.manage");
    return service.update(id, req, actor);
  }

  @DeleteMapping("/{id}")
  @Audited(action = "HOLIDAY_DELETE", targetType = "HOLIDAY")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID id) {
    policy.assertCan(actor, "holiday.manage");
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
