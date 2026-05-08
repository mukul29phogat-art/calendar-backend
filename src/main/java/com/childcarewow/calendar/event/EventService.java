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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
  private final JdbcTemplate calendarJdbc;
  private final NamedParameterJdbcTemplate platformNamedJdbc;

  @PersistenceContext private EntityManager em;

  public EventService(
      EventRepository eventRepo,
      TimezoneService timezoneService,
      PlatformEntityValidator platformValidator,
      SoftFlagService softFlagService,
      NotificationService notificationService,
      @Qualifier("calendarJdbcTemplate") JdbcTemplate calendarJdbc,
      @Qualifier("platformNamedJdbcTemplate") NamedParameterJdbcTemplate platformNamedJdbc) {
    this.eventRepo = eventRepo;
    this.timezoneService = timezoneService;
    this.platformValidator = platformValidator;
    this.softFlagService = softFlagService;
    this.notificationService = notificationService;
    this.calendarJdbc = calendarJdbc;
    this.platformNamedJdbc = platformNamedJdbc;
  }

  /** GET /events/{id} — applies the same role-aware visibility filter as the list endpoint. */
  @Transactional(readOnly = true)
  public EventView findById(UUID id, UserPrincipal actor) {
    Event event =
        eventRepo
            .findById(id)
            .filter(e -> e.getDeletedAt() == null)
            .orElseThrow(
                () -> new com.childcarewow.calendar.exception.NotFoundException("Event", id));
    if (!isVisibleTo(event, actor)) {
      // 404 (not 403) — don't leak existence of events the actor can't see.
      throw new com.childcarewow.calendar.exception.NotFoundException("Event", id);
    }
    return toViewWithJoins(event);
  }

  /**
   * GET /events?schoolId=&from=&to=&type= — calendar-window read with role-aware visibility.
   * Returns events the actor is allowed to see. PARENT visibility additionally requires {@code
   * inviteParents=true}, the actor's child to be in the event's scope, and the actor (and their
   * children) to NOT be in {@code excludedParticipantIds}.
   */
  @Transactional(readOnly = true)
  public List<EventView> findInWindow(
      UUID schoolId,
      java.time.OffsetDateTime from,
      java.time.OffsetDateTime to,
      EventType typeFilter,
      UserPrincipal actor) {
    if (schoolId == null || from == null || to == null) {
      throw new ValidationException("scope", "schoolId, from, and to are required");
    }
    List<Event> raw = eventRepo.findInWindow(schoolId, from, to, typeFilter);
    return raw.stream().filter(e -> isVisibleTo(e, actor)).map(this::toViewWithJoins).toList();
  }

  // -- visibility -------------------------------------------------------------

  private boolean isVisibleTo(Event e, UserPrincipal actor) {
    if (actor == null) {
      return false;
    }
    return switch (actor.role()) {
      case ORG_ADMIN -> actor.orgId().equals(e.getOrgId());
      case SCHOOL_ADMIN -> actor.schoolIds().contains(e.getSchoolId());
      case STAFF -> staffCanSee(e, actor);
      case PARENT -> parentCanSee(e, actor);
    };
  }

  private boolean staffCanSee(Event e, UserPrincipal actor) {
    return switch (e.getType()) {
      case SCHOOL -> actor.schoolIds().contains(e.getSchoolId());
      case CLASSROOM ->
          e.getClassroomId() != null && actor.classroomIds().contains(e.getClassroomId());
      case CUSTOM -> {
        if (actor.id().equals(e.getOrganizerUserId())) {
          yield true;
        }
        yield loadAttendees(e.getId()).contains(actor.id());
      }
    };
  }

  private boolean parentCanSee(Event e, UserPrincipal actor) {
    if (!e.isInviteParents()) {
      return false;
    }
    // Excluded check: parent themselves OR any of their children in excludedParticipantIds.
    var excluded = loadExclusions(e.getId());
    if (excluded.userIds.contains(actor.id())) {
      return false;
    }
    for (UUID childId : actor.childStudentIds()) {
      if (excluded.studentIds.contains(childId)) {
        return false;
      }
    }
    // Child-in-scope check by event type.
    return switch (e.getType()) {
      case SCHOOL -> actor.schoolIds().contains(e.getSchoolId());
      case CLASSROOM ->
          e.getClassroomId() != null && parentChildInClassroom(actor, e.getClassroomId());
      case CUSTOM -> {
        Set<UUID> students = loadStudents(e.getId());
        for (UUID childId : actor.childStudentIds()) {
          if (students.contains(childId)) {
            yield true;
          }
        }
        yield false;
      }
    };
  }

  /**
   * Direct platform-DB query: does any of {@code actor.childStudentIds()} have classroom_id =
   * {@code classroomId}? Used by parent-classroom-event visibility.
   */
  private boolean parentChildInClassroom(UserPrincipal actor, UUID classroomId) {
    if (actor.childStudentIds().isEmpty()) {
      return false;
    }
    org.springframework.jdbc.core.namedparam.MapSqlParameterSource params =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("ids", actor.childStudentIds())
            .addValue("classroomId", classroomId);
    Integer n =
        platformNamedJdbc.queryForObject(
            "SELECT COUNT(*) FROM students "
                + "WHERE id IN (:ids) AND classroom_id = :classroomId AND deleted_at IS NULL",
            params,
            Integer.class);
    return n != null && n > 0;
  }

  // -- join-table loaders + view builder -------------------------------------

  private Set<UUID> loadAttendees(UUID eventId) {
    return new java.util.HashSet<>(
        calendarJdbc.query(
            "SELECT user_id FROM event_attendees WHERE event_id = ?",
            (rs, n) -> UUID.fromString(rs.getString(1)),
            eventId));
  }

  private Set<UUID> loadStudents(UUID eventId) {
    return new java.util.HashSet<>(
        calendarJdbc.query(
            "SELECT student_id FROM event_students WHERE event_id = ?",
            (rs, n) -> UUID.fromString(rs.getString(1)),
            eventId));
  }

  private record Exclusions(Set<UUID> userIds, Set<UUID> studentIds) {}

  private Exclusions loadExclusions(UUID eventId) {
    Set<UUID> users = new java.util.HashSet<>();
    Set<UUID> students = new java.util.HashSet<>();
    calendarJdbc.query(
        "SELECT participant_id, participant_type FROM event_excluded_participants "
            + "WHERE event_id = ?",
        rs -> {
          UUID id = UUID.fromString(rs.getString("participant_id"));
          if ("USER".equals(rs.getString("participant_type"))) {
            users.add(id);
          } else {
            students.add(id);
          }
        },
        eventId);
    return new Exclusions(users, students);
  }

  /** Reads the join tables and assembles an EventView for a single event. */
  private EventView toViewWithJoins(Event e) {
    List<UUID> attendees =
        e.getType() == EventType.CUSTOM ? List.copyOf(loadAttendees(e.getId())) : List.of();
    List<UUID> students =
        e.getType() == EventType.CUSTOM ? List.copyOf(loadStudents(e.getId())) : List.of();
    List<EventView.ExcludedParticipantView> exclusions = loadExcludedViews(e.getId());
    return EventMapper.toView(
        e,
        attendees,
        students,
        exclusions,
        softFlagService.findActiveByEntity(FlaggedEntity.EVENT, e.getId()));
  }

  private List<EventView.ExcludedParticipantView> loadExcludedViews(UUID eventId) {
    return calendarJdbc.query(
        "SELECT participant_id, participant_type FROM event_excluded_participants "
            + "WHERE event_id = ?",
        (rs, n) ->
            new EventView.ExcludedParticipantView(
                UUID.fromString(rs.getString("participant_id")), rs.getString("participant_type")),
        eventId);
  }

  /**
   * Updates an existing event. Same validation as create. Holiday check skipped when {@code
   * startDt} is unchanged (per the prototype's {@code validateEventInput}). Soft-flag recompute
   * always runs because the bidirectional pair semantics need a fresh sweep on any save.
   *
   * <p>Returns {@code EventView} with refreshed join-table arrays + active soft-flags. Throws
   * {@link com.childcarewow.calendar.exception.NotFoundException} if the event doesn't exist.
   *
   * <p>Caller is responsible for the policy gate. The controller calls {@code
   * policyService.assertCan(actor, "event.edit", event)} after fetching the existing row, so the
   * resource-bearing policy decision can run on the loaded entity.
   */
  @Transactional
  public EventView update(UUID id, CreateEventRequest req, UserPrincipal actor) {
    Event existing =
        eventRepo
            .findById(id)
            .filter(e -> e.getDeletedAt() == null)
            .orElseThrow(
                () -> new com.childcarewow.calendar.exception.NotFoundException("Event", id));

    // Capture the pre-mutation snapshot for notification diffing. Defensive copy of the fields
    // we care about so subsequent mutations don't shift the snapshot's identity.
    Event prev = snapshotForDiff(existing);

    validateRequest(req);

    // Holiday check only fires when startDt actually changed.
    boolean startMoved = !existing.getStartDt().toInstant().equals(req.startDt().toInstant());
    if (startMoved) {
      LocalDate startSchoolLocal =
          timezoneService.toSchoolLocalDate(req.startDt().toInstant(), req.schoolId());
      if (timezoneService.isHolidayForSchool(req.schoolId(), startSchoolLocal)) {
        throw new EventOnHolidayException(startSchoolLocal.toString());
      }
    }

    // Platform existence checks for fields that may have changed.
    platformValidator.assertSchoolExists(req.schoolId());
    if (req.type() == EventType.CLASSROOM) {
      platformValidator.assertClassroomExists(req.classroomId());
      platformValidator.assertClassroomBelongsToSchool(req.classroomId(), req.schoolId());
    }
    if (req.organizerUserId() != null) {
      platformValidator.assertUserExists(req.organizerUserId());
    }
    if (req.type() == EventType.CUSTOM) {
      for (UUID userId : req.attendeeUserIds()) {
        platformValidator.assertUserExists(userId);
      }
      for (UUID studentId : req.studentIds()) {
        platformValidator.assertStudentExists(studentId);
      }
    }
    List<EventView.ExcludedParticipantView> resolvedExclusions = List.of();
    if (req.type() == EventType.SCHOOL && !req.excludedParticipantIds().isEmpty()) {
      resolvedExclusions = resolveExclusions(req.excludedParticipantIds());
    }

    // Mutate the managed entity
    existing.setSchoolId(req.schoolId());
    existing.setType(req.type());
    existing.setTitle(req.title().trim());
    existing.setDescription(req.description());
    existing.setClassroomId(req.classroomId());
    existing.setStartDt(req.startDt());
    existing.setEndDt(req.endDt());
    existing.setAllDay(Boolean.TRUE.equals(req.allDay()));
    existing.setOrganizerUserId(req.organizerUserId() == null ? actor.id() : req.organizerUserId());
    existing.setInviteParents(Boolean.TRUE.equals(req.inviteParents()));
    existing.setUpdatedByUserId(actor.id());

    Event saved = eventRepo.saveAndFlush(existing);
    em.refresh(saved);
    UUID eventId = saved.getId();

    // Replace join-table rows. Simpler than diffing against the prior set; CASCADE doesn't help
    // because we're not deleting the parent event. Clear-and-rebuild matches the soft-flag
    // recompute pattern from Series 3.
    if (req.type() == EventType.CUSTOM) {
      calendarJdbc.update("DELETE FROM event_attendees WHERE event_id = ?", eventId);
      calendarJdbc.update("DELETE FROM event_students WHERE event_id = ?", eventId);
      insertAttendees(eventId, req.attendeeUserIds());
      insertStudents(eventId, req.studentIds());
    } else {
      // Type may have flipped from CUSTOM → other; clear stale rows.
      calendarJdbc.update("DELETE FROM event_attendees WHERE event_id = ?", eventId);
      calendarJdbc.update("DELETE FROM event_students WHERE event_id = ?", eventId);
    }
    calendarJdbc.update("DELETE FROM event_excluded_participants WHERE event_id = ?", eventId);
    if (!resolvedExclusions.isEmpty()) {
      insertExclusions(eventId, resolvedExclusions);
    }

    // Recompute always: time/classroom/organizer changes can introduce or remove overlaps.
    softFlagService.recomputeForEvent(eventId);

    notificationService.dispatchEventUpdated(prev, saved);

    return EventMapper.toView(
        saved,
        req.type() == EventType.CUSTOM ? List.copyOf(req.attendeeUserIds()) : List.of(),
        req.type() == EventType.CUSTOM ? List.copyOf(req.studentIds()) : List.of(),
        resolvedExclusions,
        softFlagService.findActiveByEntity(FlaggedEntity.EVENT, eventId));
  }

  /** Detached snapshot used by notification diffing — captures the fields the diff cares about. */
  private static Event snapshotForDiff(Event e) {
    Event s = new Event();
    s.setId(e.getId());
    s.setOrgId(e.getOrgId());
    s.setSchoolId(e.getSchoolId());
    s.setType(e.getType());
    s.setTitle(e.getTitle());
    s.setStartDt(e.getStartDt());
    s.setEndDt(e.getEndDt());
    s.setClassroomId(e.getClassroomId());
    s.setOrganizerUserId(e.getOrganizerUserId());
    s.setInviteParents(e.isInviteParents());
    s.setCreatedByUserId(e.getCreatedByUserId());
    return s;
  }

  /** Loads an event for the controller's pre-update policy gate. 404 if missing or soft-deleted. */
  @Transactional(readOnly = true)
  public Event loadForPolicyCheck(UUID id) {
    return eventRepo
        .findById(id)
        .filter(e -> e.getDeletedAt() == null)
        .orElseThrow(() -> new com.childcarewow.calendar.exception.NotFoundException("Event", id));
  }

  /**
   * Soft-deletes the event. Cleans up the bidirectional DOUBLE_BOOKING flag pair (so the OTHER end
   * of any overlap loses its flag too) and dispatches a CANCEL notification stub. Join-table rows
   * (attendees, students, exclusions) are left intact — their event row is now soft-deleted so
   * they're invisible to all read paths, and the FK has ON DELETE CASCADE so a future hard-delete
   * sweep cleans them up automatically.
   */
  @Transactional
  public void delete(UUID id) {
    Event existing =
        eventRepo
            .findById(id)
            .filter(e -> e.getDeletedAt() == null)
            .orElseThrow(
                () -> new com.childcarewow.calendar.exception.NotFoundException("Event", id));
    existing.setDeletedAt(java.time.OffsetDateTime.now());
    eventRepo.saveAndFlush(existing);
    softFlagService.removeFlagsForEvent(id);
    notificationService.dispatchEventDeleted(existing);
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
    // CUSTOM-only: validate every attendee + student id exists in the platform DB. Caches handle
    // the per-id round-trip cost; for batches > 50 ids consider a single IN-list query later.
    if (req.type() == EventType.CUSTOM) {
      for (UUID userId : req.attendeeUserIds()) {
        platformValidator.assertUserExists(userId);
      }
      for (UUID studentId : req.studentIds()) {
        platformValidator.assertStudentExists(studentId);
      }
    }

    // SCHOOL-only: classroomId/attendees/students are explicitly NOT used. We tag each
    // excludedParticipantId as USER or STUDENT after the entity save so we have an event_id to
    // bind to. Resolution happens here so we can fail fast if any id matches neither table.
    List<EventView.ExcludedParticipantView> resolvedExclusions = List.of();
    if (req.type() == EventType.SCHOOL && !req.excludedParticipantIds().isEmpty()) {
      resolvedExclusions = resolveExclusions(req.excludedParticipantIds());
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
    UUID eventId = saved.getId();

    // CUSTOM-only: insert into the calendar-owned join tables. CASCADE delete is set on the FK
    // to events(id) so deleting the event later cleans these up automatically.
    if (req.type() == EventType.CUSTOM) {
      insertAttendees(eventId, req.attendeeUserIds());
      insertStudents(eventId, req.studentIds());
    }

    // SCHOOL-only (and any future type): insert resolved exclusions.
    if (!resolvedExclusions.isEmpty()) {
      insertExclusions(eventId, resolvedExclusions);
    }

    // Soft-flag recompute (DOUBLE_BOOKING bidirectional). Always runs, even if no overlaps —
    // it'll just clear-and-rebuild to the same state. Cheap when there's nothing in scope.
    softFlagService.recomputeForEvent(eventId);

    // Stub until 5.8.
    notificationService.dispatchEventCreated(saved);

    return EventMapper.toView(
        saved,
        req.type() == EventType.CUSTOM ? List.copyOf(req.attendeeUserIds()) : List.of(),
        req.type() == EventType.CUSTOM ? List.copyOf(req.studentIds()) : List.of(),
        resolvedExclusions,
        softFlagService.findActiveByEntity(FlaggedEntity.EVENT, eventId));
  }

  /**
   * Resolves each id to USER or STUDENT. Precedence: USER first (if both match in theory, the
   * exclusion is interpreted as the user). If neither matches, throw {@link ValidationException} —
   * better to surface a typo than silently drop the exclusion.
   */
  private List<EventView.ExcludedParticipantView> resolveExclusions(List<UUID> ids) {
    List<EventView.ExcludedParticipantView> out = new ArrayList<>(ids.size());
    for (UUID id : ids) {
      if (platformValidator.userExists(id)) {
        out.add(new EventView.ExcludedParticipantView(id, "USER"));
      } else if (platformValidator.studentExists(id)) {
        out.add(new EventView.ExcludedParticipantView(id, "STUDENT"));
      } else {
        throw new ValidationException(
            "excludedParticipantIds", "id " + id + " matches neither a user nor a student");
      }
    }
    return out;
  }

  private void insertExclusions(UUID eventId, List<EventView.ExcludedParticipantView> exclusions) {
    calendarJdbc.batchUpdate(
        "INSERT INTO event_excluded_participants "
            + "(event_id, participant_id, participant_type) VALUES (?, ?, ?) "
            + "ON CONFLICT DO NOTHING",
        exclusions.stream()
            .map(e -> new Object[] {eventId, e.participantId(), e.participantType()})
            .toList());
  }

  private void insertAttendees(UUID eventId, List<UUID> userIds) {
    if (userIds.isEmpty()) {
      return;
    }
    calendarJdbc.batchUpdate(
        "INSERT INTO event_attendees (event_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        userIds.stream().map(uid -> new Object[] {eventId, uid}).toList());
  }

  private void insertStudents(UUID eventId, List<UUID> studentIds) {
    if (studentIds.isEmpty()) {
      return;
    }
    calendarJdbc.batchUpdate(
        "INSERT INTO event_students (event_id, student_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        studentIds.stream().map(sid -> new Object[] {eventId, sid}).toList());
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
    if (req.type() == EventType.SCHOOL) {
      // SCHOOL: no classroom, no attendees, no students. Fail fast on caller mistakes.
      if (req.classroomId() != null) {
        throw new ValidationException("classroomId", "SCHOOL events cannot have a classroomId");
      }
      if (!req.attendeeUserIds().isEmpty() || !req.studentIds().isEmpty()) {
        throw new ValidationException(
            "attendeeUserIds",
            "SCHOOL events use excludedParticipantIds, not attendeeUserIds/studentIds");
      }
    }
    if (req.type() != EventType.CLASSROOM
        && req.type() != EventType.CUSTOM
        && req.type() != EventType.SCHOOL) {
      throw new ValidationException("type", "Unknown event type: " + req.type());
    }
    if (req.type() != EventType.SCHOOL && !req.excludedParticipantIds().isEmpty()) {
      throw new ValidationException(
          "excludedParticipantIds", "excludedParticipantIds is only valid for SCHOOL events");
    }
  }
}
