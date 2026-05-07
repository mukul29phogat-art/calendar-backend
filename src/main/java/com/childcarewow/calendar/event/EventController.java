package com.childcarewow.calendar.event;

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
}
