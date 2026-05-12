package com.childcarewow.calendar.importantdate;

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

  @PostMapping
  @Audited(action = "IMPORTANT_CREATE", targetType = "IMPORTANT_DATE")
  public ResponseEntity<ImportantDateView> create(
      @AuthenticationPrincipal UserPrincipal actor,
      @Valid @RequestBody CreateImportantDateRequest req) {
    policy.assertCan(actor, "importantDate.manage");
    ImportantDateView created = service.create(req, actor);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }
}
