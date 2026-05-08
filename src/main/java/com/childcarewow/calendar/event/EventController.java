package com.childcarewow.calendar.event;

import com.childcarewow.calendar.audit.Audited;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.policy.PolicyService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Event write endpoints. {@code POST /api/v1/events} (this part) and {@code PUT}/{@code DELETE}
 * (Parts 5.5/5.6). Reads land in 5.4.
 *
 * <p>Idempotency: the {@code IdempotencyFilter} (Part 3.13) is configured to apply to {@code POST
 * /api/v1/events}, so a client retrying with the same {@code Idempotency-Key} + body returns the
 * cached 201 without invoking this method.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

  private final EventService service;
  private final PolicyService policy;

  public EventController(EventService service, PolicyService policy) {
    this.service = service;
    this.policy = policy;
  }

  @PostMapping
  @Audited(action = "EVENT_CREATE", targetType = "EVENT")
  public ResponseEntity<EventView> create(
      @AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody CreateEventRequest req) {
    policy.assertCan(actor, "event.create");
    if (req.type() == EventType.SCHOOL) {
      policy.assertCan(actor, "event.create.schoolType");
    }
    EventView created = service.create(req, actor);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  /**
   * Updates an existing event. The policy gate is resource-bearing — we load the entity first so
   * {@code policyService.assertCan(actor, "event.edit", event)} can decide on STAFF type-specific
   * scoping (CLASSROOM staff need classroom membership, etc.) per Part 3.2's rules.
   */
  @PutMapping("/{id}")
  @Audited(action = "EVENT_UPDATE", targetType = "EVENT")
  public EventView update(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable UUID id,
      @Valid @RequestBody CreateEventRequest req) {
    Event existing = service.loadForPolicyCheck(id);
    policy.assertCan(actor, "event.edit", existing);
    if (req.type() == EventType.SCHOOL) {
      policy.assertCan(actor, "event.create.schoolType");
    }
    return service.update(id, req, actor);
  }

  @GetMapping("/{id}")
  public EventView findById(@AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID id) {
    return service.findById(id, actor);
  }

  /**
   * Calendar-window read. {@code from} / {@code to} are ISO-8601 datetimes; the inclusive window
   * filters by {@code start_dt}. Optional {@code type} narrows by event type. Visibility is applied
   * in the service so this controller stays auth-only.
   */
  @GetMapping
  public List<EventView> list(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam UUID schoolId,
      @RequestParam OffsetDateTime from,
      @RequestParam OffsetDateTime to,
      @RequestParam(required = false) EventType type) {
    return service.findInWindow(schoolId, from, to, type, actor);
  }
}
