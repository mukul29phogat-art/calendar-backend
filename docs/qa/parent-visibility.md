# Parent Calendar Visibility — QA reference

Operator-facing reference for Part 11.9 of the build playbook. Backs the manual QA walkthrough that runs as part of the Series 11 release sign-off.

This doc has three sections:
1. **Visibility rules per entity** — the canonical list of what a PARENT user sees and doesn't see, with code path + test anchors.
2. **Manual walkthrough checklist** — numbered steps an operator runs in dev as Priya (PARENT of Aanya).
3. **Common-failure-point watch** — the specific gotcha the playbook calls out (soft-flag UI must HIDE, not render empty).

> **What this doc is NOT.** This is not an exhaustive specification. The spec lives in the architecture spec + locked decisions D9/D10. This doc is what an operator reads to know "where do I click to verify the parent surface."

---

## 1. Visibility rules per entity

### 1.1 Events

| Rule | Code path | Existing test anchor |
| --- | --- | --- |
| **`inviteParents=true` gate.** An event with `invite_parents=false` is never visible to parents, regardless of attendees/students/classroom. | `EventService.parentCanSee` (`src/main/java/com/childcarewow/calendar/event/EventService.java:129-158`) | `EventReadIT.parentDoesNotSeeEventWithInviteParentsFalse` |
| **Child-in-scope.** Parent sees a CLASSROOM event iff one of their children is in that classroom. SCHOOL events are visible to every parent at the school. CUSTOM events are visible iff one of their children is in `event_students`. | same | `EventReadIT.parentSeesClassroomEventForChildsClassroom` + `.parentDoesNotSeeClassroomEventForOtherClassroom` + `.parentSeesCustomEventWhenChildIsParticipant` |
| **Excluded by user_id.** If parent's own `user_id` is in `event_excluded_participants`, the event is hidden even if everything else allows it. | same | `EventReadIT.parentExcludedByUserIdCannotSee` |
| **Excluded by student_id.** If any of the parent's children's `student_id` is in `event_excluded_participants`, the event is hidden. | same | `EventReadIT.parentExcludedByChildStudentIdCannotSee` |
| **Cross-cutting:** parent on the calendar feed sees exactly the expected subset. | `CalendarReadService.read` calling through `EventService.findInWindow` | `CalendarReadServiceParentIT.parentSeesExactlyTheVisibleSubset` + `.excludedByUserIdHidesEvent` + `.excludedByChildStudentIdHidesEvent` |

### 1.2 Tasks (locked decision D10)

| Rule | Code path | Existing test anchor |
| --- | --- | --- |
| **Parents see zero tasks.** Hard-coded short-circuit: if `actor.role() == PARENT`, return empty list. No per-task filtering. | `TaskReadService.findInWindow` (`src/main/java/com/childcarewow/calendar/task/TaskReadService.java:56`) | `TaskReadIT.parentSeesEmptyList`, `CalendarTaskReadIT.parentSeesNoTasks` |
| **Cross-cutting:** `CalendarReadService.read` as parent contains zero `TaskCalendarItem` rows. | same | `CalendarReadServiceParentIT.noCalendarItemOfKindTaskInParentResponse` |

> Tasks are internal to staff/admin per the original PRD. No FE surface should ever show a Task chip to a parent — if any does, **that's a bug**.

### 1.3 Holidays

| Rule | Code path | Existing test anchor |
| --- | --- | --- |
| **Approved-only.** For PARENT actors, `approved=true` is forced regardless of any query-param filter. Pending federal holidays are hidden. | `HolidayService.findInSchool` (`src/main/java/com/childcarewow/calendar/holiday/HolidayService.java:104`) | `HolidayReadIT.parentDoesNotSeePendingFederalEvenWithoutFilter` + `.parentApprovedFalseFilterIsIgnoredAndReturnsApprovedOnly` |
| **Single-row read.** Pending federal holiday returns 404 to parents (existence-leak prevention). | same | `HolidayReadIT.parentFindByIdHidesPendingFederalAs404` + `.parentFindByIdSeesApproved` |
| **Cross-school.** Parent querying another school's holidays gets 403. | same | `HolidayReadIT.parentAtOtherSchoolGetsForbidden` |
| **Cross-cutting:** calendar feed for parent omits pending federal rows. | `HolidayRepository.findApprovedInWindow` (called by `CalendarReadService`) | `CalendarReadServiceParentIT.pendingFederalHolidayHiddenFromParent` |

### 1.4 Important dates (Series 10)

| Rule | Code path | Existing test anchor |
| --- | --- | --- |
| **`visibleToParents=true` required.** Parent sees only rows opted in by admin. | `ImportantDateReadService.isVisibleToActor` (`src/main/java/com/childcarewow/calendar/importantdate/ImportantDateReadService.java:68-83`) | `ImportantDateListIT.parentSeesNothingWhenAllRowsAreInvisible` |
| **Own-child-only for BIRTHDAY.** A `kind=BIRTHDAY` row with `visibleToParents=true` is only visible to the parent of that student. Other parents do NOT see it. | same | `ImportantDateListIT.parentDoesNotSeeOtherChildBirthdayEvenWhenVisibleToParents` |
| **IMPORTANT rows are school-wide.** A `kind=IMPORTANT` row with `visibleToParents=true` is visible to every parent at the school. | same | `ImportantDateListIT.parentSeesOwnChildBirthdayAndVisibleImportant` |
| **Cross-cutting:** calendar feed parent sees own-child birthday + public important; hides other child's birthday + private important. | `CalendarReadService` → `ImportantDateReadService.findInWindow` | `CalendarReadServiceParentIT.otherChildsBirthdayHiddenEvenWhenVisibleToParents` + `.parentSeesExactlyTheVisibleSubset` |

### 1.5 Soft flags

| Rule | Code path | Existing test anchor |
| --- | --- | --- |
| **Policy denies parent.** `policyService.assertCan(actor, "calendar.softFlag.see")` returns false for PARENT. Controllers gate any soft-flag-bearing endpoint on this. | `PolicyServiceImpl` action map | `CalendarReadServiceParentIT.softFlagPolicyDeniedForParent` |
| **Defense in depth.** `SoftFlagService.dismiss` independently re-checks `actor.role() == PARENT → ForbiddenException`. A PARENT JWT that somehow reaches the service layer still bounces. | `SoftFlagService.dismiss` (line 107 in current file) | existing `SoftFlagServiceTest` |
| **No soft-flag variant on `CalendarItem`.** The calendar feed cannot return soft flags by construction — there's no `SoftFlagCalendarItem` in the sealed interface. Parents who hit the feed get zero soft-flag rows even before policy enforcement. | `CalendarItem.java` sealed-permits list | structural (compiler-enforced) |

---

## 2. Manual walkthrough checklist

Run as Priya (PARENT of Aanya in Butterflies) in dev mode. Priya's seed UUID is `33333333-0000-0000-0000-000000000006`.

Prerequisites:
- All Series 1–10 backends running.
- FE bundle running with `VITE_USE_REAL_API_*` flags set per the deploys that have flipped.
- A "complex week" of test data seeded — re-use the `CalendarReadServiceParentIT.seedComplexWeek` fixture as a template, OR seed manually via admin sessions.

Steps:

1. **Log in as Priya.** Confirm the persona-switcher shows "Priya Singh (PARENT)" and the school dropdown locks to "Sunrise".
2. **Navigate to `/calendar`.** Confirm the FE calls `GET /api/v1/calendar` (network tab) with `from`/`to` matching the visible window.
3. **Inspect the response payload.** Every item should have a `kind` of `event`, `holiday`, `birthday`, or `important` — NEVER `task`. If you see `kind=task`, that's a bug — file against Part 11.9 carry-forward.
4. **Spot-check Event A** (CLASSROOM in Butterflies, `inviteParents=true`). Should render on the calendar.
5. **Spot-check Event B** (CLASSROOM in Caterpillars, `inviteParents=true`). Should NOT render.
6. **Spot-check Event C** (Butterflies but `inviteParents=false`). Should NOT render even though Aanya is in the classroom.
7. **Spot-check Event D** (CUSTOM with Aanya as student). Should render.
8. **Spot-check Event E** (Priya in excluded). Should NOT render.
9. **Spot-check Event F** (Aanya in excluded). Should NOT render.
10. **Verify zero tasks.** Open the Tasks page (the FE may hide it from PARENTS entirely; that's correct). Confirm no Task chips on the calendar.
11. **Verify Holiday H** (CUSTOM approved). Should render as a holiday banner.
12. **Verify Holiday I** (FEDERAL pending). Should NOT render.
13. **Verify Birthday J** (Aanya's birthday, `visibleToParents=true`). Should render with the cake icon.
14. **Verify Birthday K** (other child's, `visibleToParents=true`). Should NOT render even though the flag is true.
15. **Verify Important L** (`visibleToParents=true`). Should render.
16. **Verify Important M** (`visibleToParents=false`). Should NOT render.
17. **Open the bell.** `GET /api/v1/notifications/me` runs. Only notifications addressed to Priya appear. `X-Unread-Count` header is populated.

If any step fails, file a bug as a Part 11.9 carry-forward. The cross-cutting IT (`CalendarReadServiceParentIT`) is the regression guard — a bug here means the IT didn't cover the failing path; add a test in the same PR as the fix.

---

## 3. Common-failure-point watch

**The playbook calls out:** "Parent seeing soft flags via the detail modal. `calendar.softFlag.see` returns false for PARENT; verify the flag UI is hidden, not just empty."

**What to inspect.** In the browser DOM:
- ✗ A `<div class="soft-flag-list">` element rendering with no children. (Empty container — still leaks the existence of a soft-flag mechanism.)
- ✓ No `soft-flag-list` element in the DOM at all. (Properly hidden.)

The backend's `calendar.softFlag.see` policy gate returns false for PARENT, which means the soft-flag API endpoint returns 403. The FE should treat 403 as "no UI for this user" (hide the container) rather than "empty list" (render the container with zero items). If the FE renders the empty container, it's still leaking schema knowledge to a parent who shouldn't know soft flags exist.

The backend side of this is pinned by `CalendarReadServiceParentIT.softFlagPolicyDeniedForParent`. The FE side is operator-walkthrough material — the spec doesn't tell us exactly which DOM the FE generates, so a manual DOM inspection during the walkthrough is the test.

---

## Maintenance notes

- When a new entity kind lands on the calendar (e.g. "reminders" in a future series), add a section here AND a corresponding parent-side test in `CalendarReadServiceParentIT`.
- When `CalendarItem`'s sealed permits list changes (a new variant), this doc and the IT must update in the same PR.
- This doc is operator-facing. If a rule is non-obvious to a non-engineer, expand the rule statement with an example before adding more bullets.
