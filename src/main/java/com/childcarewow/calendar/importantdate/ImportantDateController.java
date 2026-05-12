package com.childcarewow.calendar.importantdate;

import com.childcarewow.calendar.audit.Audited;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.policy.PolicyService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Important-date write surface (Part 10.1+). The read surface (calendar feed) lives on {@link
 * ImportantDateReadService} from Part 7.3. PUT/DELETE land in Part 10.2; the dedicated GET endpoint
 * with parent visibility filter lands in Part 10.3.
 */
@RestController
@RequestMapping("/api/v1/important-dates")
public class ImportantDateController {

  private final ImportantDateService service;
  private final PolicyService policy;

  public ImportantDateController(ImportantDateService service, PolicyService policy) {
    this.service = service;
    this.policy = policy;
  }

  /**
   * Dedicated GET endpoint with parent-visibility filter (Part 10.3). Auth-only at the controller —
   * visibility narrowing (PARENT clamp on {@code visible_to_parents=true} + own-child for BIRTHDAY)
   * lives in the service. Returns {@link ImportantDateView}s, not the polymorphic CalendarItem
   * shape (that's the calendar feed's wire shape from Part 7.3).
   */
  @GetMapping
  public List<ImportantDateView> list(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam UUID schoolId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return service.list(schoolId, from, to, actor);
  }

  @PostMapping
  @Audited(action = "IMPORTANT_CREATE", targetType = "IMPORTANT_DATE")
  public ResponseEntity<ImportantDateView> create(
      @AuthenticationPrincipal UserPrincipal actor,
      @Valid @RequestBody CreateImportantDateRequest req) {
    policy.assertCan(actor, "importantDate.manage");
    ImportantDateView created = service.create(req, actor);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PutMapping("/{id}")
  @Audited(action = "IMPORTANT_UPDATE", targetType = "IMPORTANT_DATE")
  public ImportantDateView update(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable UUID id,
      @Valid @RequestBody CreateImportantDateRequest req) {
    // Load + assert; same policy gate as create (admins only — `importantDate.manage`).
    service.loadForPolicyCheck(id);
    policy.assertCan(actor, "importantDate.manage");
    return service.update(id, req, actor);
  }

  @DeleteMapping("/{id}")
  @Audited(action = "IMPORTANT_DELETE", targetType = "IMPORTANT_DATE")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID id) {
    service.loadForPolicyCheck(id);
    policy.assertCan(actor, "importantDate.manage");
    service.delete(id, actor);
    return ResponseEntity.noContent().build();
  }
}
