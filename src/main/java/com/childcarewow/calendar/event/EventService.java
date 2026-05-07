package com.childcarewow.calendar.event;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.conflict.FlaggedEntity;
import com.childcarewow.calendar.exception.EventOnHolidayException;
import com.childcarewow.calendar.exception.InvalidTimeRangeException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.notification.NotificationService;
import com.childcarewow.calendar.platform.PlatformEntityValidator;
import com.childcarewow.calendar.softflag.SoftFlagService;
import com.childcarewow.calendar.timezone.TimezoneService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the create / read / update / delete flow for {@link Event}. Part 5.1 implements {@link
 * #create} for {@link EventType#CLASSROOM} only; CUSTOM and SCHOOL land in 5.2 and 5.3.
 *
 * <p>The create flow stitches together every cross-cutting service we built in Series 3: {@code
 * PolicyService} (asserted at the controller), {@code TimezoneService} (school-local holiday
 * check), {@code PlatformEntityValidator} (school + classroom existence), {@code SoftFlagService}
 * (recompute after save), {@code NotificationService} (stub until 5.8), {@code AuditService} (via
 * the {@code @Audited} annotation on the controller).
 */
@Service
public class EventService {

  private final EventRepository eventRepo;
  private final TimezoneService timezoneService;
  private final PlatformEntityValidator platformValidator;
  private final SoftFlagService softFlagService;
  private final NotificationService notificationService;

  @PersistenceContext private EntityManager em;

  public EventService(
      EventRepository eventRepo,
      TimezoneService timezoneService,
      PlatformEntityValidator platformValidator,
      SoftFlagService softFlagService,
      NotificationService notificationService) {
    this.eventRepo = eventRepo;
    this.timezoneService = timezoneService;
    this.platformValidator = platformValidator;
    this.softFlagService = softFlagService;
    this.notificationService = notificationService;
  }

  @Transactional
  public EventView create(CreateEventRequest req, UserPrincipal actor) {
    validateRequest(req);

    // Holiday check uses the school's timezone, NOT UTC date (architecture spec § 3 / § 7.6).
    LocalDate startSchoolLocal =
        timezoneService.toSchoolLocalDate(req.startDt().toInstant(), req.schoolId());
    if (timezoneService.isHolidayForSchool(req.schoolId(), startSchoolLocal)) {
      throw new EventOnHolidayException(startSchoolLocal.toString());
    }

    // Platform-side existence checks (school + classroom). Caches inside PlatformEntityValidator.
    platformValidator.assertSchoolExists(req.schoolId());
    if (req.type() == EventType.CLASSROOM) {
      platformValidator.assertClassroomExists(req.classroomId());
      platformValidator.assertClassroomBelongsToSchool(req.classroomId(), req.schoolId());
    }
    if (req.organizerUserId() != null) {
      platformValidator.assertUserExists(req.organizerUserId());
    }

    Event event = new Event();
    event.setOrgId(actor.orgId());
    event.setSchoolId(req.schoolId());
    event.setType(req.type());
    event.setTitle(req.title().trim());
    event.setDescription(req.description());
    event.setClassroomId(req.classroomId());
    event.setStartDt(req.startDt());
    event.setEndDt(req.endDt());
    event.setAllDay(Boolean.TRUE.equals(req.allDay()));
    event.setOrganizerUserId(req.organizerUserId() == null ? actor.id() : req.organizerUserId());
    event.setInviteParents(Boolean.TRUE.equals(req.inviteParents()));
    event.setCreatedByUserId(actor.id());

    Event saved = eventRepo.saveAndFlush(event);
    // Refresh so DB-managed columns (created_at, updated_at) are populated for the response.
    em.refresh(saved);

    // Soft-flag recompute (DOUBLE_BOOKING bidirectional). Always runs, even if no overlaps —
    // it'll just clear-and-rebuild to the same state. Cheap when there's nothing in scope.
    softFlagService.recomputeForEvent(saved.getId());

    // Stub until 5.8.
    notificationService.dispatchEventCreated(saved);

    UUID eventId = saved.getId();
    return EventMapper.toView(
        saved, softFlagService.findActiveByEntity(FlaggedEntity.EVENT, eventId));
  }

  // -- validation -------------------------------------------------------------

  private static void validateRequest(CreateEventRequest req) {
    if (req == null) {
      throw new ValidationException("body", "request body is required");
    }
    if (req.endDt().isBefore(req.startDt()) || req.endDt().isEqual(req.startDt())) {
      throw new InvalidTimeRangeException();
    }
    if (req.type() == EventType.CLASSROOM && req.classroomId() == null) {
      throw new ValidationException("classroomId", "classroomId is required for CLASSROOM events");
    }
    if (req.type() != EventType.CLASSROOM) {
      // Part 5.1 covers CLASSROOM only — surface a clear error for early callers who try CUSTOM
      // or SCHOOL before 5.2/5.3 land. Better than a confusing partial impl.
      throw new ValidationException(
          "type",
          "Event type " + req.type() + " is not yet supported (Part 5.1 covers CLASSROOM only)");
    }
  }
}
