package com.childcarewow.calendar.calendar;

import com.childcarewow.calendar.auth.UserPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified calendar read endpoint per architecture spec § 6.5. Part 7.1 returns event items only;
 * Parts 7.2 / 7.3 layer the other four kinds (task, holiday, birthday, important).
 *
 * <p>Auth-only at the controller — visibility is enforced inside the service. The {@code filters}
 * query param is accepted for forwards-compatibility but unused in 7.1 (no kinds other than events
 * to filter); Part 7.3 wires it.
 */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {

  private final CalendarReadService service;

  public CalendarController(CalendarReadService service) {
    this.service = service;
  }

  @GetMapping
  public List<CalendarItem> read(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam UUID schoolId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) java.util.Set<String> filters) {
    // `filters` is reserved for Part 7.3 — once we emit more than one kind, this narrows the
    // response. Until then it's a no-op pass-through; clients can send it without behavior change.
    return service.read(schoolId, from, to, actor);
  }
}
