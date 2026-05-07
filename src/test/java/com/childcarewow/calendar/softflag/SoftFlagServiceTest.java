package com.childcarewow.calendar.softflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.conflict.ConflictFlag;
import com.childcarewow.calendar.conflict.ConflictFlagRepository;
import com.childcarewow.calendar.conflict.FlaggedEntity;
import com.childcarewow.calendar.conflict.SoftFlagType;
import com.childcarewow.calendar.event.Event;
import com.childcarewow.calendar.event.EventRepository;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.exception.ForbiddenException;
import com.childcarewow.calendar.exception.NotFoundException;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.holiday.Holiday;
import com.childcarewow.calendar.holiday.HolidaySource;
import com.childcarewow.calendar.task.Task;
import com.childcarewow.calendar.task.TaskRepository;
import com.childcarewow.calendar.task.TaskStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit tests for {@link SoftFlagService}: insert validation, find-active filtering,
 * dismiss-idempotency, and the parent-actor guard.
 */
class SoftFlagServiceTest {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SCHOOL = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID ACTOR = UUID.fromString("33333333-0000-0000-0000-000000000001");

  private final ConflictFlagRepository repo = mock(ConflictFlagRepository.class);
  private final EventRepository eventRepo = mock(EventRepository.class);
  private final TaskRepository taskRepo = mock(TaskRepository.class);
  private final SoftFlagService service = new SoftFlagService(repo, eventRepo, taskRepo);

  // -- insert -----------------------------------------------------------------

  @Test
  void insertPersistsValidFlag() {
    when(repo.save(any(ConflictFlag.class))).thenAnswer(inv -> inv.getArgument(0));

    UUID eventId = UUID.randomUUID();
    UUID otherEventId = UUID.randomUUID();
    ConflictFlag saved =
        service.insertFlag(
            ORG,
            SCHOOL,
            FlaggedEntity.EVENT,
            eventId,
            SoftFlagType.DOUBLE_BOOKING,
            otherEventId,
            "Overlaps with another event");

    assertThat(saved.getOrgId()).isEqualTo(ORG);
    assertThat(saved.getSchoolId()).isEqualTo(SCHOOL);
    assertThat(saved.getEntityType()).isEqualTo(FlaggedEntity.EVENT);
    assertThat(saved.getEntityId()).isEqualTo(eventId);
    assertThat(saved.getConflictType()).isEqualTo(SoftFlagType.DOUBLE_BOOKING);
    assertThat(saved.getConflictingEntityId()).isEqualTo(otherEventId);
    assertThat(saved.getMessage()).isEqualTo("Overlaps with another event");
    assertThat(saved.isDismissed()).isFalse();
  }

  @Test
  void insertAllowsHolidayFlagWithoutConflictingEntity() {
    when(repo.save(any(ConflictFlag.class))).thenAnswer(inv -> inv.getArgument(0));

    UUID taskId = UUID.randomUUID();
    ConflictFlag saved =
        service.insertFlag(
            ORG,
            SCHOOL,
            FlaggedEntity.TASK,
            taskId,
            SoftFlagType.HOLIDAY,
            null,
            "Falls on holiday");

    assertThat(saved.getConflictingEntityId()).isNull();
    assertThat(saved.getConflictType()).isEqualTo(SoftFlagType.HOLIDAY);
  }

  @Test
  void insertRejectsMissingFields() {
    assertThatThrownBy(
            () ->
                service.insertFlag(
                    null,
                    SCHOOL,
                    FlaggedEntity.EVENT,
                    UUID.randomUUID(),
                    SoftFlagType.HOLIDAY,
                    null,
                    "msg"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.insertFlag(
                    ORG,
                    null,
                    FlaggedEntity.EVENT,
                    UUID.randomUUID(),
                    SoftFlagType.HOLIDAY,
                    null,
                    "msg"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.insertFlag(
                    ORG, SCHOOL, null, UUID.randomUUID(), SoftFlagType.HOLIDAY, null, "msg"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.insertFlag(
                    ORG, SCHOOL, FlaggedEntity.EVENT, null, SoftFlagType.HOLIDAY, null, "msg"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.insertFlag(
                    ORG, SCHOOL, FlaggedEntity.EVENT, UUID.randomUUID(), null, null, "msg"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.insertFlag(
                    ORG,
                    SCHOOL,
                    FlaggedEntity.EVENT,
                    UUID.randomUUID(),
                    SoftFlagType.HOLIDAY,
                    null,
                    null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.insertFlag(
                    ORG,
                    SCHOOL,
                    FlaggedEntity.EVENT,
                    UUID.randomUUID(),
                    SoftFlagType.HOLIDAY,
                    null,
                    "  "))
        .isInstanceOf(ValidationException.class);
    verify(repo, never()).save(any());
  }

  // -- findActive -------------------------------------------------------------

  @Test
  void findActiveDelegatesToRepo() {
    UUID entityId = UUID.randomUUID();
    ConflictFlag a = new ConflictFlag();
    ConflictFlag b = new ConflictFlag();
    when(repo.findByEntityTypeAndEntityIdAndDismissedFalse(eq(FlaggedEntity.EVENT), eq(entityId)))
        .thenReturn(List.of(a, b));

    List<ConflictFlag> result = service.findActiveByEntity(FlaggedEntity.EVENT, entityId);
    assertThat(result).containsExactly(a, b);
  }

  @Test
  void findActiveEmptyWhenNoFlags() {
    UUID entityId = UUID.randomUUID();
    when(repo.findByEntityTypeAndEntityIdAndDismissedFalse(eq(FlaggedEntity.TASK), eq(entityId)))
        .thenReturn(List.of());
    assertThat(service.findActiveByEntity(FlaggedEntity.TASK, entityId)).isEmpty();
  }

  // -- dismiss ----------------------------------------------------------------

  @Test
  void dismissMarksAndStamps() {
    UUID flagId = UUID.randomUUID();
    ConflictFlag flag = new ConflictFlag();
    flag.setId(flagId);
    flag.setDismissed(false);
    when(repo.findById(eq(flagId))).thenReturn(Optional.of(flag));
    when(repo.save(eq(flag))).thenReturn(flag);

    UserPrincipal admin =
        new UserPrincipal(
            ACTOR,
            "Admin",
            "admin@ccw.test",
            Role.ORG_ADMIN,
            ORG,
            Set.of(),
            Set.of(),
            Set.of(),
            null);

    ConflictFlag dismissed = service.dismiss(flagId, admin);
    assertThat(dismissed.isDismissed()).isTrue();
    assertThat(dismissed.getDismissedByUserId()).isEqualTo(ACTOR);
    assertThat(dismissed.getDismissedAt()).isNotNull();
    verify(repo, times(1)).save(flag);
  }

  @Test
  void dismissIsIdempotent() {
    UUID flagId = UUID.randomUUID();
    ConflictFlag already = new ConflictFlag();
    already.setId(flagId);
    already.setDismissed(true);
    UUID originalDismisser = UUID.fromString("99999999-9999-9999-9999-999999999999");
    already.setDismissedByUserId(originalDismisser);
    when(repo.findById(eq(flagId))).thenReturn(Optional.of(already));

    UserPrincipal staff =
        new UserPrincipal(
            ACTOR,
            "Staff",
            "staff@ccw.test",
            Role.STAFF,
            ORG,
            Set.of(SCHOOL),
            Set.of(),
            Set.of(),
            null);

    ConflictFlag result = service.dismiss(flagId, staff);
    assertThat(result.isDismissed()).isTrue();
    assertThat(result.getDismissedByUserId())
        .as("idempotent: original dismisser preserved")
        .isEqualTo(originalDismisser);
    verify(repo, never()).save(any()); // no second save
  }

  @Test
  void dismissThrowsForUnknownFlag() {
    UUID flagId = UUID.randomUUID();
    when(repo.findById(eq(flagId))).thenReturn(Optional.empty());
    UserPrincipal admin =
        new UserPrincipal(
            ACTOR,
            "Admin",
            "admin@ccw.test",
            Role.ORG_ADMIN,
            ORG,
            Set.of(),
            Set.of(),
            Set.of(),
            null);
    assertThatThrownBy(() -> service.dismiss(flagId, admin)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void dismissForbiddenForParent() {
    UUID flagId = UUID.randomUUID();
    UserPrincipal parent =
        new UserPrincipal(
            ACTOR,
            "Parent",
            "parent@ccw.test",
            Role.PARENT,
            ORG,
            Set.of(SCHOOL),
            Set.of(),
            Set.of(),
            null);
    assertThatThrownBy(() -> service.dismiss(flagId, parent))
        .isInstanceOf(ForbiddenException.class);
    verify(repo, never()).findById(any());
  }

  @Test
  void dismissForbiddenForNullActor() {
    UUID flagId = UUID.randomUUID();
    assertThatThrownBy(() -> service.dismiss(flagId, null)).isInstanceOf(ForbiddenException.class);
    verify(repo, never()).findById(any());
  }

  // -- recomputeForEvent ------------------------------------------------------

  @Test
  void recomputeWritesBidirectionalPairForOneOverlap() {
    UUID eventId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    Event a = event(eventId, "Event A");
    Event b = event(otherId, "Event B");
    when(eventRepo.findById(eq(eventId))).thenReturn(Optional.of(a));
    when(eventRepo.findOverlapping(any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(b));
    when(repo.save(any(ConflictFlag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.recomputeForEvent(eventId);

    verify(repo).deleteDoubleBookingFlagsForEvent(eventId);
    ArgumentCaptor<ConflictFlag> cap = ArgumentCaptor.forClass(ConflictFlag.class);
    verify(repo, times(2)).save(cap.capture());
    List<ConflictFlag> saved = cap.getAllValues();

    ConflictFlag aToB = saved.get(0);
    assertThat(aToB.getEntityId()).isEqualTo(eventId);
    assertThat(aToB.getConflictingEntityId()).isEqualTo(otherId);
    assertThat(aToB.getConflictType()).isEqualTo(SoftFlagType.DOUBLE_BOOKING);
    assertThat(aToB.getEntityType()).isEqualTo(FlaggedEntity.EVENT);
    assertThat(aToB.getMessage()).contains("Event B");

    ConflictFlag bToA = saved.get(1);
    assertThat(bToA.getEntityId()).isEqualTo(otherId);
    assertThat(bToA.getConflictingEntityId()).isEqualTo(eventId);
    assertThat(bToA.getMessage()).contains("Event A");
  }

  @Test
  void recomputeWithNoOverlapsClearsButSavesNothing() {
    UUID eventId = UUID.randomUUID();
    Event a = event(eventId, "Solo");
    when(eventRepo.findById(eq(eventId))).thenReturn(Optional.of(a));
    when(eventRepo.findOverlapping(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

    service.recomputeForEvent(eventId);

    verify(repo).deleteDoubleBookingFlagsForEvent(eventId);
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeWithMultipleOverlapsWritesPairForEach() {
    UUID eventId = UUID.randomUUID();
    Event a = event(eventId, "A");
    Event b = event(UUID.randomUUID(), "B");
    Event c = event(UUID.randomUUID(), "C");
    when(eventRepo.findById(eq(eventId))).thenReturn(Optional.of(a));
    when(eventRepo.findOverlapping(any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(b, c));
    when(repo.save(any(ConflictFlag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.recomputeForEvent(eventId);

    verify(repo, times(4)).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeOnSoftDeletedEventClearsFlagsWithoutOverlapSearch() {
    UUID eventId = UUID.randomUUID();
    Event a = event(eventId, "Deleted");
    a.setDeletedAt(OffsetDateTime.now());
    when(eventRepo.findById(eq(eventId))).thenReturn(Optional.of(a));

    service.recomputeForEvent(eventId);

    verify(repo).deleteDoubleBookingFlagsForEvent(eventId);
    verify(eventRepo, never()).findOverlapping(any(), any(), any(), any(), any(), any());
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeThrowsForUnknownEvent() {
    UUID eventId = UUID.randomUUID();
    when(eventRepo.findById(eq(eventId))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.recomputeForEvent(eventId))
        .isInstanceOf(NotFoundException.class);
    verify(repo, never()).deleteDoubleBookingFlagsForEvent(any());
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeIsIdempotent() {
    UUID eventId = UUID.randomUUID();
    Event a = event(eventId, "A");
    Event b = event(UUID.randomUUID(), "B");
    when(eventRepo.findById(eq(eventId))).thenReturn(Optional.of(a));
    when(eventRepo.findOverlapping(any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(b));

    service.recomputeForEvent(eventId);
    service.recomputeForEvent(eventId);

    verify(repo, times(2)).deleteDoubleBookingFlagsForEvent(eventId);
    verify(repo, times(4)).save(any(ConflictFlag.class));
  }

  @Test
  void removeFlagsForEventDelegatesToRepo() {
    UUID eventId = UUID.randomUUID();
    service.removeFlagsForEvent(eventId);
    verify(repo).deleteDoubleBookingFlagsForEvent(eventId);
  }

  // -- recomputeForTask -------------------------------------------------------

  @Test
  void recomputeForTaskWritesBidirectionalPairForOverlap() {
    UUID taskId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    Task a = task(taskId, "Task A", LocalTime.of(9, 0), TaskStatus.TODO);
    Task b = task(otherId, "Task B", LocalTime.of(9, 30), TaskStatus.TODO);
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.of(a));
    when(taskRepo.findOverlapCandidates(any(), any(), any(), any())).thenReturn(List.of(b));
    when(repo.save(any(ConflictFlag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.recomputeForTask(taskId);

    verify(repo).deleteDoubleBookingFlagsForTask(taskId);
    ArgumentCaptor<ConflictFlag> cap = ArgumentCaptor.forClass(ConflictFlag.class);
    verify(repo, times(2)).save(cap.capture());
    List<ConflictFlag> saved = cap.getAllValues();

    assertThat(saved.get(0).getEntityType()).isEqualTo(FlaggedEntity.TASK);
    assertThat(saved.get(0).getEntityId()).isEqualTo(taskId);
    assertThat(saved.get(0).getConflictingEntityId()).isEqualTo(otherId);
    assertThat(saved.get(0).getMessage()).contains("Task B");
    assertThat(saved.get(1).getEntityId()).isEqualTo(otherId);
    assertThat(saved.get(1).getConflictingEntityId()).isEqualTo(taskId);
  }

  @Test
  void recomputeForTaskBothDueTimesNullCountsAsConflict() {
    UUID taskId = UUID.randomUUID();
    Task a = task(taskId, "A", null, TaskStatus.TODO);
    Task b = task(UUID.randomUUID(), "B", null, TaskStatus.TODO);
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.of(a));
    when(taskRepo.findOverlapCandidates(any(), any(), any(), any())).thenReturn(List.of(b));

    service.recomputeForTask(taskId);
    verify(repo, times(2)).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForTaskOneTimeNullDoesNotConflict() {
    UUID taskId = UUID.randomUUID();
    Task a = task(taskId, "A", LocalTime.of(9, 0), TaskStatus.TODO);
    Task b = task(UUID.randomUUID(), "B", null, TaskStatus.TODO);
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.of(a));
    when(taskRepo.findOverlapCandidates(any(), any(), any(), any())).thenReturn(List.of(b));

    service.recomputeForTask(taskId);
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForTaskSubjectNullOtherSetDoesNotConflict() {
    // Symmetric of the above: covers the second branch of (a == null || b == null).
    UUID taskId = UUID.randomUUID();
    Task a = task(taskId, "A", null, TaskStatus.TODO);
    Task b = task(UUID.randomUUID(), "B", LocalTime.of(9, 0), TaskStatus.TODO);
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.of(a));
    when(taskRepo.findOverlapCandidates(any(), any(), any(), any())).thenReturn(List.of(b));

    service.recomputeForTask(taskId);
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForTaskOutsideTwoHourWindowDoesNotConflict() {
    UUID taskId = UUID.randomUUID();
    Task a = task(taskId, "A", LocalTime.of(9, 0), TaskStatus.TODO);
    Task b =
        task(UUID.randomUUID(), "B", LocalTime.of(13, 0), TaskStatus.TODO); // 4h away → no conflict
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.of(a));
    when(taskRepo.findOverlapCandidates(any(), any(), any(), any())).thenReturn(List.of(b));

    service.recomputeForTask(taskId);
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForTaskExactlyTwoHoursIsConflict() {
    UUID taskId = UUID.randomUUID();
    Task a = task(taskId, "A", LocalTime.of(9, 0), TaskStatus.TODO);
    Task b = task(UUID.randomUUID(), "B", LocalTime.of(11, 0), TaskStatus.TODO); // exactly 120 min
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.of(a));
    when(taskRepo.findOverlapCandidates(any(), any(), any(), any())).thenReturn(List.of(b));

    service.recomputeForTask(taskId);
    verify(repo, times(2)).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForTaskOnSoftDeletedTaskOnlyClears() {
    UUID taskId = UUID.randomUUID();
    Task a = task(taskId, "Deleted", LocalTime.of(9, 0), TaskStatus.TODO);
    a.setDeletedAt(OffsetDateTime.now());
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.of(a));

    service.recomputeForTask(taskId);

    verify(repo).deleteDoubleBookingFlagsForTask(taskId);
    verify(taskRepo, never()).findOverlapCandidates(any(), any(), any(), any());
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForTaskWhenSubjectDoneOnlyClears() {
    UUID taskId = UUID.randomUUID();
    Task a = task(taskId, "Done", LocalTime.of(9, 0), TaskStatus.DONE);
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.of(a));

    service.recomputeForTask(taskId);

    verify(repo).deleteDoubleBookingFlagsForTask(taskId);
    verify(taskRepo, never()).findOverlapCandidates(any(), any(), any(), any());
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForTaskThrowsForUnknown() {
    UUID taskId = UUID.randomUUID();
    when(taskRepo.findById(eq(taskId))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.recomputeForTask(taskId))
        .isInstanceOf(NotFoundException.class);
    verify(repo, never()).deleteDoubleBookingFlagsForTask(any());
  }

  @Test
  void removeFlagsForTaskDelegatesToRepo() {
    UUID taskId = UUID.randomUUID();
    service.removeFlagsForTask(taskId);
    verify(repo).deleteDoubleBookingFlagsForTask(taskId);
  }

  // -- recomputeForHoliday ----------------------------------------------------

  @Test
  void recomputeForHolidayPaintsEventsAndTasks() {
    Holiday h = holiday(true);
    Event e1 = event(UUID.randomUUID(), "Event 1");
    Event e2 = event(UUID.randomUUID(), "Event 2");
    Task t1 = task(UUID.randomUUID(), "Task 1", null, TaskStatus.TODO);
    when(eventRepo.findBySchoolAndDate(eq(h.getSchoolId()), eq(h.getDate())))
        .thenReturn(List.of(e1, e2));
    when(taskRepo.findBySchoolIdAndDueDateAndDeletedAtIsNull(eq(h.getSchoolId()), eq(h.getDate())))
        .thenReturn(List.of(t1));

    service.recomputeForHoliday(h);

    verify(repo).deleteHolidayFlagsForHoliday(h.getId());
    ArgumentCaptor<ConflictFlag> cap = ArgumentCaptor.forClass(ConflictFlag.class);
    verify(repo, times(3)).save(cap.capture()); // 2 events + 1 task
    List<ConflictFlag> saved = cap.getAllValues();
    assertThat(saved)
        .allSatisfy(f -> assertThat(f.getConflictType()).isEqualTo(SoftFlagType.HOLIDAY));
    assertThat(saved).allSatisfy(f -> assertThat(f.getConflictingEntityId()).isEqualTo(h.getId()));
    assertThat(saved).allSatisfy(f -> assertThat(f.getMessage()).contains("Memorial Day"));
  }

  @Test
  void recomputeForHolidayUnapprovedClearsButPaintsNothing() {
    Holiday h = holiday(false);

    service.recomputeForHoliday(h);

    verify(repo).deleteHolidayFlagsForHoliday(h.getId());
    verify(eventRepo, never()).findBySchoolAndDate(any(), any());
    verify(taskRepo, never()).findBySchoolIdAndDueDateAndDeletedAtIsNull(any(), any());
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForHolidayWithNoMatchingEntitiesClearsButPaintsNothing() {
    Holiday h = holiday(true);
    when(eventRepo.findBySchoolAndDate(any(), any())).thenReturn(List.of());
    when(taskRepo.findBySchoolIdAndDueDateAndDeletedAtIsNull(any(), any())).thenReturn(List.of());

    service.recomputeForHoliday(h);

    verify(repo).deleteHolidayFlagsForHoliday(h.getId());
    verify(repo, never()).save(any(ConflictFlag.class));
  }

  @Test
  void recomputeForHolidayRejectsNullOrUnpersisted() {
    assertThatThrownBy(() -> service.recomputeForHoliday(null))
        .isInstanceOf(ValidationException.class);
    Holiday transientHoliday = new Holiday();
    assertThatThrownBy(() -> service.recomputeForHoliday(transientHoliday))
        .isInstanceOf(ValidationException.class);
  }

  // -- helpers ----------------------------------------------------------------

  private static Event event(UUID id, String title) {
    Event e = new Event();
    e.setId(id);
    e.setOrgId(ORG);
    e.setSchoolId(SCHOOL);
    e.setTitle(title);
    e.setType(EventType.CUSTOM);
    e.setStartDt(OffsetDateTime.parse("2026-06-01T09:00:00Z"));
    e.setEndDt(OffsetDateTime.parse("2026-06-01T10:00:00Z"));
    e.setOrganizerUserId(ACTOR);
    e.setCreatedByUserId(ACTOR);
    return e;
  }

  private static Task task(UUID id, String title, LocalTime dueTime, TaskStatus status) {
    Task t = new Task();
    t.setId(id);
    t.setOrgId(ORG);
    t.setSchoolId(SCHOOL);
    t.setTitle(title);
    t.setAssigneeUserId(ACTOR);
    t.setDueDate(LocalDate.of(2026, 6, 1));
    t.setDueTime(dueTime);
    t.setStatus(status);
    t.setCreatedByUserId(ACTOR);
    return t;
  }

  private static Holiday holiday(boolean approved) {
    Holiday h = new Holiday();
    h.setId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
    h.setOrgId(ORG);
    h.setSchoolId(SCHOOL);
    h.setDate(LocalDate.of(2026, 5, 25));
    h.setName("Memorial Day");
    h.setSource(HolidaySource.FEDERAL);
    h.setApproved(approved);
    h.setCreatedByUserId(ACTOR);
    return h;
  }
}
