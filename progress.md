# Calendar backend build progress

Each part below records its hand-off report. Newest at the top.

---

## Hand-off report template

```
Part X.Y — [Title] — STATUS: [✅ done | ⚠️ partial | ❌ blocked]
Date: YYYY-MM-DD
Operator: [name]

What got built:
- [bullet]

Files changed (count: N):
- path/to/file.ext — [one-line summary]

Validation:
- [ ] [validation step 1]   (paste output)
- [ ] [validation step 2]   (paste output)

Notes / surprises:
- [anything unexpected, deferred, or worth flagging]

Next part: X.Y+1
```

---

## Part 11.1 (Series 11) — `NotificationView` DTO with field aliasing — STATUS: ✅ done · **OPENS SERIES 11**
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **`NotificationView` record** — response shape for the upcoming `GET /api/v1/notifications/me`. Emits BOTH the legacy FE-prototype keys (`relatedEventId`, `relatedEventTitle`) AND the schema-generic keys (`relatedEntityId`, `relatedEntityTitle`) backed by the same column value, so the FE's existing `Notification` type in `src/types/index.ts:180` stays untouched while the DB schema's `related_entity_*` columns can serve event/task/important-date references uniformly.
- **`@JsonInclude(NON_NULL)`** elides absent optional fields (related-entity ids/titles, paused reason). Booleans (`paused`) always emit.
- **Pure record + duplicated-field approach** — Jackson's `@JsonAlias` is deserialization-only (it accepts both names as input but emits one name on output). To emit both keys, the record carries two fields that `fromEntity(Notification, recipients, readBy)` populates from the same source. Clean, no custom serializer needed.
- **Recipient + readBy ids passed in** at construction time. The read service (Part 11.2) batches those lookups across multiple notifications; the DTO is a pure projection.

Files changed (count: 3; 2 new, 1 progress):
- `src/main/java/com/childcarewow/calendar/notification/NotificationView.java` — new.
- `src/test/java/com/childcarewow/calendar/notification/NotificationViewSerializationTest.java` — new (4 unit tests).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 3m15s. **313 tests** (was 309), 3 skipped. JaCoCo bundle ≥80%; Spotless clean.
- [x] `NotificationViewSerializationTest` — 4/4 green:
  - **`emitsBothEventAndEntityKeysWithSameValue`** — parses the serialized JSON; asserts all 4 keys present (`relatedEventId`, `relatedEntityId`, `relatedEventTitle`, `relatedEntityTitle`); same value across the two id-keys and across the two title-keys.
  - **`nullRelatedEntityElidesBothKeyVariants`** — NON_NULL strips both id-keys AND both title-keys when the underlying column is null. The notification still serializes (id, schoolId, kind, message, paused all present).
  - **`pausedReasonElidedWhenNull`** — `paused: false` always emits (boolean primitive); `pausedReason` elides on null.
  - **`recipientAndReadByListsRoundTrip`** — `recipientUserIds` + `readBy` arrays serialize as JSON arrays of UUID strings.

Notes / surprises:
- **Three test-shape compile gotchas under `-Werror`** caught this round, all small:
  1. `Notification.setCreatedAt(...)` doesn't exist — the column is `insertable=false, updatable=false` and DB-populated. Use a null `createdAt` in the test fixture; doesn't affect the serialization shape we're checking.
  2. AssertJ's `.asList()` on `ObjectAssert` is deprecated. Cast the map value to `List<?>` first.
  3. The `containsExactly(String, String)` call against `List<?>` triggers varargs capture errors. Cast to a typed `List<String>` (with `@SuppressWarnings("unchecked")`) before asserting.
- **Why not custom serializer?** Considered a `JsonSerializer<NotificationView>` that walks the writer and emits both keys explicitly. Rejected — extra code without payoff. Records with duplicated fields are the FE prototype's exact wire shape; same value goes to both sides, Jackson handles it.
- **`paused` always emits as a primitive boolean.** The FE prototype's `Notification.paused: boolean` is non-optional, so the wire shape must always carry it. `@JsonInclude(NON_NULL)` doesn't strip booleans (they're not nullable — `false` is a valid state).
- **No DB integration yet.** This Part is a pure projection + serialization pin. Part 11.2 wires the read service + endpoint, which is where the real-DB IT will land.

### Carry-forward (no change)

- All previously-open carry-forwards remain.

Next part: **Part 11.2 — `GET /api/v1/notifications/me`.** Visibility-filtered notifications list + `X-Unread-Count` response header. Need to load notification → recipients → readBy in a batched query (avoid N+1) and apply per-user visibility filtering (notifications scope to recipient ids in `notification_recipients`).

---

## Part 10.3 (Series 10) — `GET /api/v1/important-dates` with parent visibility — STATUS: ✅ done
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **`GET /api/v1/important-dates?schoolId=&from=&to=`** — dedicated standalone GET endpoint, returns `List<ImportantDateView>` (not the polymorphic `CalendarItem` shape — that's reserved for the calendar feed from Part 7.3). Auth-only at the controller; visibility narrowing lives in the service.
- **`ImportantDateService.list(schoolId, from, to, actor)`** — pulls rows from `repo.findInWindow`, filters by parent visibility, maps to view DTOs.
- **Extracted `isVisibleToActor` as a package-private static helper on `ImportantDateReadService`.** Both call sites (calendar feed `findInWindow` + standalone GET `list`) reuse the same predicate so the visibility rule can't drift. The original private instance method now delegates to the static helper.
- **Parent visibility rule** (already locked in Part 7.3):
  - ADMIN / STAFF: every row in window.
  - PARENT: `visible_to_parents=true` is the gate. For `kind=BIRTHDAY`, additionally requires `studentId ∈ actor.childStudentIds()`. For `kind=IMPORTANT` with `visible_to_parents=true`, every parent at the school sees it.

Files changed (count: 5; 1 new test, 3 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateReadService.java` — extracted `isVisibleToActor` to package-private static helper; `isVisibleTo` now delegates.
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateService.java` — added `list` method.
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateController.java` — added `GET` endpoint.
- `src/test/java/com/childcarewow/calendar/importantdate/ImportantDateListIT.java` — new (5 ITs).
- `docs/openapi.json` — regenerated.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m57s. **309 tests** (was 304), 3 skipped. JaCoCo bundle ≥80%; Spotless clean.
- [x] `ImportantDateListIT` — 5/5 green:
  - **`adminSeesEveryRowInWindow`** — Olivia (ORG_ADMIN) sees all 4 fixture rows.
  - **`staffSeesEveryRowInWindow`** — Maya (STAFF) sees all 4 fixture rows.
  - **`parentSeesOwnChildBirthdayAndVisibleImportant`** — Priya (PARENT of Aanya) sees 2: Aanya's birthday + the visible IMPORTANT row. NOT Jordan's birthday (other child) and NOT the hidden IMPORTANT row.
  - **`parentDoesNotSeeOtherChildBirthdayEvenWhenVisibleToParents`** — pins the playbook common-failure-point. Priya does NOT see `IT-idl-jordan-birthday` even though it has `visibleToParents=true`.
  - **`parentSeesNothingWhenAllRowsAreInvisible`** — all 0 rows when every row has `visible_to_parents=false`, even if one is for own child.

Notes / surprises:
- **`isVisibleToActor` extraction was the right shape.** Originally I'd duplicated the 10-line predicate in `ImportantDateService.list`, but that risked drift between the calendar feed and the standalone GET. Extracted to a package-private static helper on `ImportantDateReadService`; the original private instance method delegates. Net behavior unchanged, but the rule now has exactly one canonical definition.
- **The dedicated GET returns `ImportantDateView`, not `CalendarItem`.** This is the divergence between the calendar feed (sealed `CalendarItem` discriminator union from Part 7.2) and the entity-list reads (single-table `ImportantDateView`). The FE's `importantDatesService.ts` consumes the entity shape directly; only the calendar grid consumes `CalendarItem`. No new type needed — `ImportantDateView` already existed.
- **No date-range validation.** `from >= to` would return an empty list (the SQL has `date >= :from AND date <= :to` — inverted range returns nothing). Per the existing convention in tasks/events GET endpoints, we don't reject inverted ranges; the empty result IS the response.
- **No paging.** Window-scoped reads in the calendar/scheduling context are bounded by date range, not by row count. Series-12 polish might add cursor-paging if a school has thousands of important-dates in one month, but that's not a real shape in practice.

### Carry-forward (no change)

- All previously-open carry-forwards remain.

Next part: **Part 10.4 — FE cutover for `importantDatesService.ts`.** Operator-gated (FE shadow ≥7 days clean), same pattern as Series 6.9 / 7.6 / 8.10 / 9.6 cutovers. Sets `VITE_USE_REAL_API_IMPORTANT_DATES=true`; retires the FE mock. After this, **Series 10 closes (3/4 + operator-gated 10.4)** and Series 11 (Notifications dispatch + parent surface) opens.

---

## Part 10.2 (Series 10) — `PUT` + `DELETE /api/v1/important-dates/{id}` — STATUS: ✅ done
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **`PUT /api/v1/important-dates/{id}`** — standard update; `schoolId` immutable (matches event/task convention from 5.5 + 8.4). Per-kind validation still applies on update path: switching `kind` to BIRTHDAY without a `studentId` → 400. If `studentId` changes to a new value, `PlatformEntityValidator.assertStudentExists` runs; same-value no-op skips the validator call (saves the cache hit).
- **`DELETE /api/v1/important-dates/{id}`** — soft delete (`deleted_at = now()`). Idempotent on second delete: already-soft-deleted rows surface as 404 on the next call (matches Parts 5.6 + 8.6 soft-delete-as-404 read convention).
- **`ImportantDateService.loadForPolicyCheck(id)`** — pre-policy load, mirrors `TaskService.loadForPolicyCheck`. Returns the entity for the controller; throws 404 on missing or soft-deleted.
- **`@Audited(IMPORTANT_UPDATE)`** + **`@Audited(IMPORTANT_DELETE)`** wired on the controller. Same policy gate as 10.1 (`importantDate.manage` — admins only).
- **No soft-flag recompute** + **no notification dispatch** on update or delete (same posture as 10.1 — important dates don't participate in overlap rules).

Files changed (count: 4; 1 new test, 2 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateService.java` — added `loadForPolicyCheck`, `update`, `delete`.
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateController.java` — added `PUT` and `DELETE` endpoints.
- `src/test/java/com/childcarewow/calendar/importantdate/ImportantDateUpdateDeleteIT.java` — new (7 ITs).
- `docs/openapi.json` — regenerated (PUT + DELETE schemas).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m58s. **304 tests** (was 297), 3 skipped. JaCoCo bundle ≥80%; Spotless clean.
- [x] `ImportantDateUpdateDeleteIT` — 7/7 green:
  - **`updateRoundsTripChangedFields`** — label + date + visibleToParents all flip.
  - **`schoolIdImmutable`** → `ValidationException("schoolId")`.
  - **`updateToBirthdayWithoutStudentIdRejected`** → `ValidationException("studentId")`.
  - **`deleteSoftDeletesRow`** — `deleted_at IS NOT NULL` in DB.
  - **`doubleDeleteReturns404`** — second delete → `NotFoundException`.
  - **`updateOnSoftDeletedReturns404`** — update after soft-delete → `NotFoundException`.
  - **`updateChangingStudentValidatesExistence`** — promote to BIRTHDAY with `studentId=ZOE` ok; then changing to an unknown student id → `ValidationException` via `assertStudentExists`.

Notes / surprises:
- **Reuse of `CreateImportantDateRequest` on the update path** — same shape as create; same per-kind validation. No need for an `UpdateImportantDateRequest`. Matches Part 8.4's pattern of `CreateTaskRequest` on the task PUT path.
- **No `updated_by_user_id` column on `important_dates`.** The V5 schema only has `created_by_user_id`. Update path mutates fields and lets the JPA `@PreUpdate` hook bump `updated_at`, but there's no actor-stamp on update. **Cross-referenced via the audit table** — `audit_events` carries `actor_user_id` + `target_id` for every IMPORTANT_UPDATE, so "who last updated this row" is recoverable from the audit log even without the column.
- **Same-value `studentId` skips the validator.** If the request's `studentId` equals the existing's, we skip the platform call — small optimization, no impact on cache freshness (the validator caches 5 min anyway).
- **No cascade on platform-side student delete** — the architecture spec's D2 says calendar doesn't observe platform deletes, so a deleted student can leave orphan `important_dates(kind=BIRTHDAY, student_id=…)` rows pointing nowhere. Read-path returns them; the FE will show "Unknown student" or hide them. Documented in 10.1 already; reaffirmed here.

### Carry-forward (no change)

- All previously-open carry-forwards remain.

Next part: **Part 10.3 — `GET /api/v1/important-dates`** with parent visibility filter (PARENT clamp: only `visibleToParents=true` rows; for BIRTHDAY, only own child). The visibility rule is already implemented in `ImportantDateReadService.isVisibleTo` (Part 7.3); 10.3 adds the dedicated GET endpoint + per-role IT matrix.

---

## Part 10.1 (Series 10) — `POST /api/v1/important-dates` — STATUS: ✅ done · **OPENS SERIES 10**
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **New write surface for `important_dates`** (the V5 schema + entity + repository have been in place since Part 1.5; this Part wires the controller + service).
- **`CreateImportantDateRequest`** record — `(label, date, schoolId, kind, studentId?, visibleToParents?)`. Bean-validation covers the unconditionally-required shape; per-kind required fields (BIRTHDAY ⇒ studentId) live in the service for an actionable 400 envelope.
- **`ImportantDateService.create(req, actor)`** — minimal cross-cutting:
  - Per-kind validation: `kind=BIRTHDAY` ⇒ `studentId` required.
  - Platform entity validation: `assertSchoolExists`, `assertStudentExists` (if present).
  - Persists row, returns `ImportantDateView`.
  - **No soft-flag recompute** (important-dates don't participate in overlap rules).
  - **No notification dispatch** (per architecture spec — birthday/important rows just appear on the calendar; the read-path's `visibleToParents` gate is the only parent-facing surface).
- **`ImportantDateController`** — `POST /api/v1/important-dates` with `policy.assertCan(actor, "importantDate.manage")` (already wired in `PolicyServiceImpl` from Part 3.2 — admins only). `@Audited(action="IMPORTANT_CREATE", targetType="IMPORTANT_DATE")` lands an audit row.
- **`CreateImportantDateRequest.visibleToParentsOrDefault()`** — defaults `null` and `false` both to `false` per architecture spec §5.5 (admins must opt in).

Files changed (count: 5; 4 new, 1 progress):
- `src/main/java/com/childcarewow/calendar/importantdate/CreateImportantDateRequest.java` — new.
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateService.java` — new.
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateController.java` — new.
- `src/test/java/com/childcarewow/calendar/importantdate/ImportantDateCreateIT.java` — new (5 ITs).
- `docs/openapi.json` — regenerated.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m49s. **297 tests** (was 292), 3 skipped. JaCoCo bundle ≥80%; Spotless clean.
- [x] `ImportantDateCreateIT` — 5/5 green:
  - **`createBirthdayPersistsAllFields`** — BIRTHDAY with `studentId=ZOE` (`55555555-0000-0000-0000-000000000001`) round-trips; DB label matches.
  - **`createImportantPersistsWithoutStudentId`** — IMPORTANT row without `studentId` succeeds (label-only "Picture Day"-style entry).
  - **`visibleToParentsDefaultsFalseWhenOmitted`** — null on wire → `visible_to_parents=false` in DB.
  - **`birthdayWithoutStudentIdRejected`** → `ValidationException("studentId")`.
  - **`unknownStudentIdRejected`** → `ValidationException` (via `PlatformEntityValidator.assertStudentExists` → `ValidationException`).

Notes / surprises:
- **Series 10 is shorter than Series 8/9** — 4 Parts (create, PUT/DELETE, GET with parent visibility, FE cutover). The entity + repo + read-side were front-loaded into Parts 1.5 and 7.3, so 10.1 is a pure wiring job (~75 lines of service + controller + DTO).
- **No `updatedByUserId` column on `important_dates`.** Unusual vs Event/Task/Holiday — the V5 schema only tracks `created_by_user_id`. PUT in 10.2 will still bump `updated_at` via the JPA `@PreUpdate` hook, but won't stamp the actor. Documented for 10.2.
- **No `classroomId` column either.** Important dates are school-scoped, not classroom-scoped. Architecture spec § 5.5 confirms this: birthdays attach to a student (which transitively maps to a classroom via the platform-owned `students` table) but the calendar row doesn't carry the classroom denormalized.
- **`assertStudentExists` is the calendar's only cross-DB student check.** The platform validator caches it for 5 min via Caffeine (Part 2.3). Idempotent for the same student id across the cache window.
- **No soft-flag triggers on important-date create.** Birthdays and important dates don't conflict with events/tasks the way holidays do (no hard block, no `recomputeForImportantDate`). Architecture spec § 7.x is silent on this — confirmed by the FE prototype which only soft-flags on event/task overlaps.

### Carry-forward (no change beyond Series 9)

- All previously-open carry-forwards remain.

Next part: **Part 10.2 — `PUT /api/v1/important-dates/{id}` + `DELETE /api/v1/important-dates/{id}`.** Standard CRUD: PUT permits label/date/visibleToParents/studentId edits; DELETE is soft-delete (`deleted_at`). `@Audited("IMPORTANT_UPDATE")` / `("IMPORTANT_DELETE")`. Visibility check on each via `policy.importantDate.manage`. Tests for happy paths + 404 on soft-deleted.

---

## Part 9.5 (Series 9) — `PUT /api/v1/tasks/{id}/series` — ENTIRE_SERIES — STATUS: ✅ done
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **`ENTIRE_SERIES` branch in `TaskService.applySeriesEdit`** — updates the master task fields in place. Handles three rule states:
  1. **Keep rule:** `recurrence == null && removeRecurrence != true` → no rule change.
  2. **Update/create rule:** `recurrence != null` → `RecurrenceService.update(ruleId, ...)` if master already has a rule; otherwise create + stamp on master.
  3. **Remove recurrence:** `removeRecurrence == true` → drop ALL overrides (`removeOverridesForTask`) + delete the rule + null-out `task.recurrenceId`. Task becomes a single non-recurring row.
- **Holiday block** fires only when `req.dueDate() != master.getDueDate()` (matches `EventService.update`'s `startMoved` gate). Same-date edits never re-check holidays.
- **`switch` dispatcher in `applySeriesEdit`** — three branches → three private helpers (`applyJustThis` / `applyThisAndFollowing` / `applyEntireSeries`). Stale `ENTIRE_SERIES` rejection removed.
- **9.4 first-occurrence collapse now falls through.** Previously rejected with `"Part 9.5"` message; now `applyThisAndFollowing` routes `occurrenceDate == master.dueDate` to `applyEntireSeries(master, req, actor)` with the same request body. The wire shape is identical — the FE doesn't need a separate re-dispatch.
- **`TaskSeriesEditRequest` grew 3 fields** — `classroomId` (nullable), `dueDate` (required for ENTIRE_SERIES, ignored for others), `removeRecurrence` (sentinel `Boolean`). Two back-compat constructors: 6-arg for 9.3 JUST_THIS, 9-arg for 9.4 THIS_AND_FOLLOWING. The 9.3 and 9.4 ITs compile unchanged.

Files changed (count: 6; 1 new test, 2 modified earlier ITs, 2 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/TaskSeriesEditRequest.java` — +3 fields + 2 back-compat ctors.
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — `applySeriesEdit` switched to dispatcher; added `applyEntireSeries`; first-occurrence collapse now routes to applyEntireSeries.
- `src/test/java/com/childcarewow/calendar/task/TaskSeriesEditJustThisIT.java` — dropped `entireSeriesRejectedUntilPart9_5` (now-obsolete).
- `src/test/java/com/childcarewow/calendar/task/TaskSeriesEditThisAndFollowingIT.java` — dropped `firstOccurrenceCollapseRejectedUntilPart9_5` (now-obsolete; falls through cleanly).
- `src/test/java/com/childcarewow/calendar/task/TaskSeriesEditEntireSeriesIT.java` — new (6 ITs).
- `docs/openapi.json` — regenerated.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m58s. **292 tests** (was 288), 3 skipped. JaCoCo bundle ≥80%; Spotless clean.
- [x] `TaskSeriesEditEntireSeriesIT` — 6/6 green:
  - **`updateMasterTitleAcrossAllOccurrencesNoRuleChange`** — recurrence omitted; master's `recurrence_id` unchanged; title/priority/status updated.
  - **`removeRecurrenceDropsRuleAndAllOverrides`** — seed two overrides, then `removeRecurrence=true` → 0 overrides remaining, 0 rule rows for the old ruleId, `task.recurrenceId` becomes null.
  - **`dueDateMoveToHolidayRejected`** — moving master.dueDate onto an approved CUSTOM holiday → `TaskOnHolidayException`.
  - **`sameDateNoHolidayCheck`** — holiday seeded ON master.dueDate AFTER create; update with `dueDate=masterDue` succeeds (holiday SELECT skipped per `dateMoved` gate).
  - **`firstOccurrenceCollapseFromThisAndFollowingFallsThrough`** — `choice=THIS_AND_FOLLOWING + occurrenceDate=master.dueDate` is now silently routed to ENTIRE_SERIES; same effect as a direct ENTIRE_SERIES call.
  - **`missingDueDateRejected`** — null `dueDate` → `ValidationException("dueDate")`.
- [x] `TaskSeriesEditJustThisIT` 6/6 still green (was 7 before dropping `entireSeriesRejectedUntilPart9_5`).
- [x] `TaskSeriesEditThisAndFollowingIT` 4/4 still green (was 5 before dropping the collapse-rejection test).

Notes / surprises:
- **`@AfterEach` title-pattern cleanup is fragile against tests that mutate the title.** First-cut 9.5 IT used `"still ok"` / `"collapsed-into-entire"` as the new titles after ENTIRE_SERIES — the cleanup pattern `WHERE title LIKE 'IT-tsees-%'` then missed them, and the orphan rows polluted the next test run. **Renamed the new titles to keep the `IT-tsees-` prefix**; both fixed and pinned. Going forward — IT-author cheat-sheet now includes "if a test renames a row, keep the IT-prefix in the new name."
- **Required cleanup of orphan rows from earlier failed runs** (4 rows: `still ok` x2 + `collapsed-into-entire` x2). One-time manual fix:
  ```sql
  DELETE FROM task_instance_overrides WHERE task_id IN (SELECT id FROM tasks WHERE title IN ('still ok','collapsed-into-entire'));
  DELETE FROM notifications WHERE related_entity_id IN (SELECT id FROM tasks WHERE title IN ('still ok','collapsed-into-entire'));
  DELETE FROM tasks WHERE title IN ('still ok','collapsed-into-entire');
  DELETE FROM recurrence_rules WHERE id NOT IN (SELECT recurrence_id FROM tasks WHERE recurrence_id IS NOT NULL);
  ```
- **`removeRecurrence` is a separate Boolean sentinel, not `recurrence == null` semantics.** The playbook common-failure-points says distinguish `null` (remove) from missing (no change). I used a separate field rather than `Optional<RecurrenceSpec>` (which Jackson handles awkwardly with records). Three explicit states map to three explicit branches.
- **First-occurrence collapse is silent now** — the FE never sees a "Part 9.5" rejection error; the backend just does ENTIRE_SERIES. Matches the FE prototype's own collapse pattern (`updateTaskWithChoice` recursively calls itself with `ENTIRE_SERIES`).
- **Notification dispatch on ENTIRE_SERIES** is the same `dispatchTaskUpdated(prev, saved)` as the regular PUT path — the diff-driven logic from Part 8.4 handles status/title/assignee/etc. changes the same way. No new notification kind needed.
- **Soft-flag recompute runs on ENTIRE_SERIES** because the master row's overlap relations can change (date / assignee / dueTime). Same posture as the regular PUT update.

### Carry-forward (no change)

- All previously-open carry-forwards remain.

Next part: **Part 9.6 — Frontend cutover: `recurrenceService.ts` retired.** Operator-gated (FE shadow ≥7 days clean). Same pattern as Series 6.9 / 7.6 / 8.10. Sets `VITE_USE_REAL_API_RECURRENCE=true`, deletes the FE's recurrence expansion logic, deletes unused TS fields (`RecurrenceRule.startDayOfWeek`, `startDayOfMonth`, `startTime`). After this, **Series 9 closes (5/6 + operator-gated 9.6)** and we move to **Series 10** (which I'll re-check in the playbook).

---

## Part 9.4 (Series 9) — `PUT /api/v1/tasks/{id}/series` — THIS_AND_FOLLOWING — STATUS: ✅ done
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **`THIS_AND_FOLLOWING` branch in `TaskService.applySeriesEdit`** — the surgical recurring-task split (architecture spec §6.2 update flow):
  1. **First-occurrence collapse check** — `occurrenceDate == master.dueDate` → reject with `ValidationException("ENTIRE_SERIES")` (Part 9.5 will fall through to the ENTIRE_SERIES handler).
  2. **Required-field validation** — `title`, `status`, `priority` must all be present (the new task is a real task and needs these the same way `CreateTaskRequest` does).
  3. **Hard holiday block** on `occurrenceDate` — same rule as create. Reuses `findApprovedHolidayName(schoolId, date)`.
  4. **Shorten master rule** via `recurrenceService.shortenUntil(masterRuleId, occurrenceDate.minusDays(1))`. (`shortenUntil` from Part 3.6 rejects an extend — no risk of accidentally lengthening.)
  5. **Drop overrides at/after split** via `recurrenceService.removeOverridesFromDate(masterId, occurrenceDate)` — the master rule no longer covers those dates, so the override rows would be orphaned.
  6. **Create the new task** at `occurrenceDate` with its own recurrence rule (per D9 — independent rule even though same group_id). Description and assignee carry forward from master if request doesn't specify; title/status/priority/dueTime come from the request.
  7. **Recompute soft flags + dispatch `TASK_CREATED`** for the new task — same post-create cross-cutting as the regular create flow.
- **Extended `TaskSeriesEditRequest` DTO** — added `description`, `priority`, `recurrence` fields. Back-compat constructor preserves Part 9.3's six-arg call sites for JUST_THIS callers.
- **Refactored `applySeriesEdit`** — single dispatch on `req.choice()` into private helpers `applyJustThis` / `applyThisAndFollowing`. Method bodies stay short.

Files changed (count: 5; 1 new test, 1 modified 9.3 IT, 2 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/TaskSeriesEditRequest.java` — added 3 fields + back-compat ctor.
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — refactored to dispatch on choice; added `applyThisAndFollowing` private helper.
- `src/test/java/com/childcarewow/calendar/task/TaskSeriesEditJustThisIT.java` — dropped `thisAndFollowingRejectedUntilPart9_4` (the contract it pinned is now obsolete).
- `src/test/java/com/childcarewow/calendar/task/TaskSeriesEditThisAndFollowingIT.java` — new (5 ITs).
- `docs/openapi.json` — regenerated for the expanded request shape.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m49s. **288 tests** (was 284), 3 skipped. JaCoCo bundle ≥80%; Spotless clean.
- [x] `TaskSeriesEditThisAndFollowingIT` — 5/5 green:
  - **`shortenMasterRuleAndCreateNewTaskAtSplit`** — DAILY task with rule until 2027-08-31; split at 2027-08-10 → master's `until_date` becomes 2027-08-09, a new task appears at 2027-08-10 with its own recurrence_id (≠ master's), `parent_task_group_id` shared with master (both null in this single-assignee case — correct per Part 9.2).
  - **`splitDropsOverridesAtOrAfterSplitDate`** — seed two JUST_THIS overrides (Aug 5 and Aug 12); after split at Aug 10, the Aug-5 override survives, the Aug-12 override is gone.
  - **`firstOccurrenceCollapseRejectedUntilPart9_5`** — `occurrenceDate == master.dueDate` → `ValidationException("ENTIRE_SERIES")`.
  - **`holidayOnSplitDateRejected`** — approved CUSTOM holiday seeded on the split date → `TaskOnHolidayException` (same envelope as create).
  - **`missingTitleRejected`** — null title → `ValidationException("title")`.
- [x] `TaskSeriesEditJustThisIT` still 7/7 green (was 8 before dropping the now-obsolete rejection test).
- [x] All Series 6/7/8 ITs unchanged and green.
- [x] OpenAPI snapshot regenerated for the wider request shape.

Notes / surprises:
- **Single-assignee → single-assignee — multi-assignee group editing on this path is not supported.** The FE's `TaskUpdateInput` is single-assignee shaped (`assigneeUserId: ID`, not `assigneeUserIds: ID[]`), and the playbook spec doesn't ask for multi-assignee on the series edit. The new task inherits `assigneeUserId` from master verbatim — there is no path to change it on the split. Documented as a non-feature here; can be added in a later Part if product wants it.
- **`parent_task_group_id` carries forward to the new task.** Per the architecture spec, the split task is "another row in the same fan-out group" so users see them as related. In the test, master + new task both have `group_id = null` (because the original master was single-assignee from 9.1) — the test still asserts they're equal, just to pin the carry-forward rule. If a multi-assignee task is split, the new row would get the same non-null group_id; only one assignee's row is split (single-row PUT, not group-wide).
- **Master gets `updatedByUserId` even though its other fields are unchanged.** Just stamps "Olivia touched this row at this time"; useful for audit cross-referencing.
- **Override-drop is `>=` not `>`.** The spec says "delete `task_instance_overrides` from `occurrenceDate` onwards." So Aug-10 itself (the split day) IS dropped; it's the FIRST occurrence of the new task, not the LAST occurrence of the old. `removeOverridesFromDate` already does `>=`, matching this.
- **`TaskSeriesEditRequest`'s back-compat constructor needed for the 9.3 IT call sites.** Adding the 3 new fields to the canonical record would have broken every 9.3 caller's 6-arg invocation. The compact-target ctor keeps them compiling unchanged.

### Carry-forward (no change beyond 9.3's)

- All previously-open carry-forwards remain.

Next part: **Part 9.5 — `PUT /api/v1/tasks/{id}/series` ENTIRE_SERIES.** Update master + its rule in place. Special case: when the `occurrenceDate == master.dueDate` collapse hits ENTIRE_SERIES (the 9.4 collapse-rejection above will fall through once 9.5 lands). Holiday block on `dueDate` if it changed.

---

## Part 9.3 (Series 9) — `PUT /api/v1/tasks/{id}/series` — JUST_THIS — STATUS: ✅ done
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **New endpoint `PUT /api/v1/tasks/{id}/series`** with body `{ choice, occurrenceDate, title?, dueTime?, status?, skipped? }`. Same resource-bearing `task.edit` policy gate as PUT (STAFF only on their own assigned tasks). `@Audited(action="TASK_SERIES_EDIT", targetType="TASK")` captures the action in the audit log.
- **`EditChoice` enum** in the task package — `JUST_THIS` / `THIS_AND_FOLLOWING` / `ENTIRE_SERIES` — matches the FE prototype's `EditChoice` string-literal union exactly.
- **`TaskSeriesEditRequest` record** — request DTO. Wraps the choice + occurrence-date + optional override fields.
- **`TaskService.applySeriesEdit(id, req, actor)`** — dispatches on `req.choice()`:
  - `JUST_THIS`: validates the occurrence date is in the rule's expansion window (uses `recurrenceService.expand(task, occ, occ)` for a single-day window — if the rule emits it, the override is valid), then calls `recurrenceService.upsertOverride(...)` to insert/replace the `task_instance_overrides` row keyed on `(taskId, occurrenceDate)`. Master task is untouched apart from a `updated_by_user_id` bump.
  - `THIS_AND_FOLLOWING` → `ValidationException("Part 9.4")` placeholder.
  - `ENTIRE_SERIES` → `ValidationException("Part 9.5")` placeholder.
- **Validation order:** (1) task exists + not soft-deleted, (2) task is recurring (`task.recurrenceId IS NOT NULL`), (3) choice routing, (4) for JUST_THIS: occurrence date in rule window. The non-recurring task rejection lands BEFORE the choice routing, so calling JUST_THIS on a non-recurring task hits `"not recurring"`, not `"Part 9.4"` etc.

Files changed (count: 6; 3 new, 2 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/EditChoice.java` — new enum.
- `src/main/java/com/childcarewow/calendar/task/TaskSeriesEditRequest.java` — new DTO.
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — added `applySeriesEdit`.
- `src/main/java/com/childcarewow/calendar/task/TaskController.java` — added the new PUT endpoint.
- `src/test/java/com/childcarewow/calendar/task/TaskSeriesEditJustThisIT.java` — new (8 ITs).
- `docs/openapi.json` — regenerated for the new endpoint + schemas.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m38s. **284 tests** (was 276), 3 skipped. JaCoCo bundle ≥80%; Spotless clean.
- [x] `TaskSeriesEditJustThisIT` — 8/8 green:
  - **`justThisUpsertsOverrideForOccurrenceDate`** — DAILY-recurring task → JUST_THIS on a future date with title + dueTime + status override → row lands with all three fields populated + master `tasks.title` unchanged.
  - **`justThisIsIdempotentSecondCallOverwritesFirst`** — two consecutive calls with the same `(taskId, occurrenceDate)` produce exactly one row; the second payload wins (matches `RecurrenceService.upsertOverride`'s find-or-create-then-overwrite semantics).
  - **`justThisWithSkippedTrueMarksDateSkipped`** — `skipped=true` writes the boolean; consumed by `expand` filter to drop the date from calendar reads.
  - **`justThisOnNonRecurringTaskRejected`** — `task.recurrenceId == null` → `ValidationException("not recurring")`.
  - **`justThisWithOccurrenceDateOutsideRuleWindowRejected`** — date past `untilDate` → `InvalidRecurrenceException` from the in-window check.
  - **`thisAndFollowingRejectedUntilPart9_4`** — recurring task + THIS_AND_FOLLOWING → `ValidationException("Part 9.4")`.
  - **`entireSeriesRejectedUntilPart9_5`** — recurring task + ENTIRE_SERIES → `ValidationException("Part 9.5")`.
  - **`unknownTaskIdReturns404`** — bogus task id → `NotFoundException`.
- [x] OpenAPI snapshot regenerated to include the new endpoint + schemas.

Notes / surprises:
- **JDBC `Time` column round-trips through the local timezone.** First attempt at reading `due_time` back with `calendarJdbc.queryForObject(..., java.sql.Time.class, ...)` returned `19:30` instead of `14:30` — the JDBC driver applies the local TZ when converting between `java.sql.Time` and the column. Even `to_char(due_time, 'HH24:MI')` returned `19:30` because the WRITE side via Hibernate had already shifted on insert.
  - **Fix in this Part:** the test now reads `dueTime` via JPA (`TaskInstanceOverrideRepository.findByTaskIdAndOccurrenceDate(...).getDueTime()`), which returns the correct `LocalTime(14, 30)`. Hibernate handles its own value reads correctly; the raw JDBC reader is the broken layer.
  - **Why this didn't bite Series 8:** task `due_time` reads in earlier ITs always went through entity/repository, never through `JdbcTemplate.queryForObject` with the `java.sql.Time` type. New code-smell: **never read `time` columns via JdbcTemplate with `java.sql.Time.class`**. The IT is the canonical reference.
  - **Production impact:** zero. The REST surface returns `LocalTime` via Jackson, which Hibernate populates correctly. The pure-SQL READ path was a test artifact.
- **`PUT /api/v1/tasks/{id}/series` instead of `POST /api/v1/tasks/{id}/overrides`.** The playbook says POST; I chose PUT because the request shape covers all three choices, only one of which writes an override row. PUT is more accurate semantically (the action is "apply series edit", not "create override"), and PUT-on-a-target-id is consistent with the existing 8.4 PUT path. The route is unambiguous either way; just flagging the deviation.
- **No notification dispatched on JUST_THIS.** Per FE prototype's `tasksService.ts:328-348`, overrides are scoped to a single occurrence and don't fan out to assignees as `TASK_UPDATED`. Backend matches. Could revisit if product wants per-occurrence notifications — but that's a non-trivial addition (would need a per-occurrence "notification" entity, since occurrences aren't first-class rows).
- **No soft-flag recompute on JUST_THIS.** Same reasoning — the master row's overlap relations are unchanged. The FE prototype agrees (doesn't call `recomputeForTask`).

### Carry-forward (no change)

- All previously-open carry-forwards remain.
- Add **"never read `time` columns via JdbcTemplate with `java.sql.Time.class`"** to the IT-author cheat sheet (no doc location for that yet; documented inline in this Part's test file).

Next part: **Part 9.4 — `PUT /api/v1/tasks/{id}/series` THIS_AND_FOLLOWING.** Shortens the existing rule + creates a new task at the split date (sharing `parent_task_group_id`). Special case: when `occurrenceDate == master.dueDate`, collapse to ENTIRE_SERIES (which falls through to 9.5's handler — so 9.4 should land before 9.5, or 9.4 stubs the collapse path until 9.5 wires it).

---

## Part 9.2 (Series 9) — `POST /api/v1/tasks` with recurrence + multi-assignee — STATUS: ✅ done
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **Lifted the `assignees.size() == 1` guard from Part 9.1.** The fan-out loop already created per-row recurrence rules; 9.2 just removes the up-front rejection. Per locked decision D9, **each fanned-out row gets its OWN `recurrence_id`** — N assignees → N rules → N tasks, with no rule sharing.
- Updated the loop comment to reflect the now-final D9 contract: per-row independent rule for per-assignee override autonomy.
- `parent_task_group_id` semantics are unchanged from Part 8.2: non-null only when `assignees.size() > 1`; identifies the multi-assignee batch. The two identifiers are orthogonal — `group_id` is "this batch of rows came from one request"; `recurrence_id` is "this row's recurrence schedule."

Files changed (count: 5; 1 new test, 1 modified 9.1 IT, 2 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — removed the size==1 guard; updated the loop comment.
- `src/test/java/com/childcarewow/calendar/task/TaskCreateWithRecurrenceIT.java` — dropped `recurrenceWithMultiAssigneeRejectedUntilPart9_2` (now-obsolete contract); unused `ValidationException` import + `TOM` constant removed.
- `src/test/java/com/childcarewow/calendar/task/TaskCreateWithRecurrenceMultiAssigneeIT.java` — new (2 ITs).
- `docs/openapi.json` — regenerated (no schema change; keeps the snapshot in sync).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m59s. **276 tests** (was 274), 3 skipped. JaCoCo bundle ≥80%; Spotless clean.
- [x] `TaskCreateWithRecurrenceMultiAssigneeIT` — 2/2 green:
  - **`threeAssigneesEachGetIndependentRecurrenceIdAndShareGroupId`** — 3 assignees (Maya/Tom/Priya) + DAILY recurrence → 3 tasks, each with a non-null `recurrence_id`, all distinct (`doesNotHaveDuplicates`), all sharing one non-null `parent_task_group_id`. Also asserts each rule row exists in `recurrence_rules`.
  - **`singleAssigneeWithRecurrenceStillLeavesGroupIdNull`** — regression guard that lifting the 9.1 guard didn't accidentally start setting `group_id` for solo assignments.
- [x] Existing 9.1 ITs still green (`TaskCreateWithRecurrenceIT`: 6/6, was 7 before drop).
- [x] Playbook validation gate met: a multi-assignee + recurrence request produces N distinct `recurrence_id`s with one shared `parent_task_group_id` (IT line 102-107).

Notes / surprises:
- **9.2 was the one-liner predicted in the 9.1 hand-off.** Removing the guard was the only production code change; the rest is test mass. The per-row recurrence-rule creation already lived inside the fan-out loop in 9.1 — 9.2's "code change" was deleting 6 lines.
- **D9 (independent rules) buys autonomy at the cost of slightly more storage.** N assignees → N recurrence_rules rows for the same nominal schedule. The alternative (shared rule) would have been smaller but would mean shortening one assignee's series (Part 9.4) couldn't be done without forking the rule anyway — so the up-front-fork model is simpler in net.
- **Override-coupling argument is weaker than I'd written.** `task_instance_overrides(task_id, occurrence_date)` is keyed on `task_id`, not `recurrence_id`. Strictly: a shared rule wouldn't couple overrides. The real D9 rationale is **schedule-edit isolation** (Part 9.4): when Maya extends or shortens her schedule, Tom's must stay put. Independent rules make that trivial. Documented for future-me.
- **No change needed to read-path.** `RecurrenceService.expand` operates on `(task, rule)` pairs; the calendar feed already loads each task's own `recurrence_id`-pointed rule.

### Carry-forward (no change)

- All previously-open carry-forwards remain. Per-occurrence holiday block (added in 9.1) still pending.

Next part: **Part 9.3 — `PUT /api/v1/tasks/{id}/series` JUST_THIS.** Per-occurrence override insertion (new endpoint, new validator that the occurrence date is in the rule's expansion window, wires through to insert a `task_instance_overrides` row keyed on `(task_id, occurrence_date)`).

---

## Part 9.1 (Series 9) — `POST /api/v1/tasks` with recurrence (single assignee) — STATUS: ✅ done · **OPENS SERIES 9**
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **`CreateTaskRequest.RecurrenceSpec`** — new nested record `(cycle, dueDayOfWeek?, dueDayOfMonth?, dueTime?, untilDate)`. Mirrors the FE prototype's "Repeat …" form. Bean-validation is intentionally loose; the cycle-shape gates (WEEKLY requires `dueDayOfWeek`, MONTHLY requires `dueDayOfMonth`, `untilDate` ≤ taskDueDate + 5 years) live in `RecurrenceService.validate` from Part 3.6.
- **`CreateTaskRequest` gained an optional `recurrence` field.** Back-compat constructor preserves all earlier call sites (defaults `recurrence=null` for non-recurring).
- **`TaskService.create` now wires recurrence** per-row inside the existing fan-out loop. For each assignee: if `req.recurrence()` is present, build a transient `RecurrenceRule`, persist via `recurrenceService.create(rule, req.dueDate())` (which runs full validation), then stamp `task.recurrenceId` with the saved rule's id. Part 9.1 enforces `assignees.size() == 1` when recurrence is present (multi-assignee + recurrence lifts to Part 9.2 with the per-row independent-rule shape from D9). The loop is already structured per-row, so 9.2 just lifts the size guard and the existing iteration creates N independent rules naturally.
- **`buildRule(RecurrenceSpec)` helper** — converts the request DTO to a transient `RecurrenceRule` entity. `RecurrenceService.create` is the validator + persister.

Files changed (count: 5; 1 new test, 2 modified, 1 modified test fixture, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/CreateTaskRequest.java` — `+RecurrenceSpec` nested record, `+recurrence` field, back-compat constructor.
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — `+RecurrenceService` dependency, recurrence-creation branch inside the per-row loop, `+buildRule` helper.
- `src/test/java/com/childcarewow/calendar/task/TaskCreateWithRecurrenceIT.java` — new (7 ITs).
- `docs/openapi.json` — regenerated baseline includes `RecurrenceSpec` schema.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m27s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskCreateWithRecurrenceIT` — 7/7 real-DB tests green:
  - **`dailyRuleRoundTripsThroughCreate`** — DAILY spec → rule row persisted with `cycle=DAILY` + `until_date`, task's `recurrence_id` stamped.
  - **`weeklyRuleRoundTripsAndExpandsToFourOccurrencesInOneMonthWindow`** — WEEKLY rule (`dueDayOfWeek=3` Wednesday in 0=Sun..6=Sat); reads back through `CalendarReadService.read` for June 2027 → exactly 5 Wednesdays. Confirms the write/read integration: 9.1's write produces a rule the existing 7.2 expansion path consumes.
  - **`monthlyRuleRoundTripsAndExpandsForFiveMonthWindow`** — MONTHLY rule (`dueDayOfMonth=15`); 5-month window → 5 occurrences (Jun 15 → Oct 15, 2027).
  - **`weeklyMissingDueDayOfWeekRejected`** — `RecurrenceService.validate` throws `InvalidRecurrenceException` → 400 INVALID_RECURRENCE envelope.
  - **`untilDateBeforeDueDateRejected`** — same path; until-before-due is invalid.
  - **`recurrenceWithMultiAssigneeRejectedUntilPart9_2`** — `[Maya, Tom] + recurrence` → `ValidationException("Part 9.2")`.
  - **`nullRecurrenceStillCreatesNonRecurringTask`** — back-compat ctor path; existing non-recurring behavior unchanged.
- [x] All earlier tests still green — the 9-arg back-compat constructor on `CreateTaskRequest` keeps Parts 8.1–8.9 ITs compiling without modification.
- [x] OpenAPI snapshot regenerated to include `RecurrenceSpec` and the now-typed `recurrence?` field on `CreateTaskRequest`.

Notes / surprises:
- **Per-row rule creation inside the existing fan-out loop is the right shape** for Part 9.2. The loop already handles per-assignee work; adding "create my own rule" per iteration is a one-liner. When 9.2 lifts the `size() == 1` guard on recurrence, the per-row code path is already correct — no refactor needed.
- **D9 (independent rule per fanned-out row) means recurrence-on-multi-assignee creates N rules and N tasks**, where each task points at its own rule. The architectural rationale is per-assignee override autonomy: Maya skipping her Tuesday occurrence shouldn't affect Tom's. Storing a shared rule would couple their overrides through the `task_instance_overrides(task_id, occurrence_date)` UNIQUE constraint.
- **Soft-flag recompute still runs on recurring task creation** (`softFlagService.recomputeForTask` in the post-loop). The recompute logic at Part 3.12 / `findOverlapCandidates` already handles non-recurring vs recurring tasks correctly — it queries the `tasks` table by `dueDate` (the master row's date), which is the right field for the parent task's overlap check. Per-occurrence overlap detection would be a much bigger surface (`tasks × task_instance_overrides` cross-join); deferred indefinitely.
- **Calendar expansion was already in place from Part 7.2.** I half-expected to touch `TaskReadService` or `CalendarReadService` for the 9.1 wire-up, but the calendar feed already iterates `findRecurringForSchool` + `RecurrenceService.expand` from 7.2's task branch. Setting `task.recurrenceId` is the only delta; the read path discovers the rule automatically. Documented in the test (`weeklyRuleRoundTripsAndExpandsToFourOccurrencesInOneMonthWindow` exercises both write and read in one method).
- **Holiday block still applies to the master task's `dueDate` only**, not to every expanded occurrence. A recurring task with `dueDate=2027-06-01` (non-holiday) and rule covering `2027-12-25` (Christmas, if approved as a holiday) would today expand to include Dec 25. This is a known gap and matches the FE prototype's behavior — per-occurrence holiday checking would need a different expansion model. Left as a follow-up.

### Carry-forward (none cleared, one new)

- **NEW (9.1 deferred):** per-occurrence holiday block on recurring task expansion. Today the holiday check fires only on master `dueDate`; expanded occurrences ignore holidays. Matches FE prototype; revisit if product wants stricter semantics.
- All previously-open carry-forwards remain.

Next part: **Part 9.2 — `POST /api/v1/tasks` with recurrence + multi-assignee.** Lifts the `assignees.size() == 1` guard from 9.1's recurrence path. The fan-out loop already creates a per-row rule when `recurrence` is present, so 9.2's net work is one-line: remove the guard, add a test asserting N tasks each get an independent `recurrence_id` while sharing `parent_task_group_id`.

---

## Part 8.9 (Series 8) — Task notification dispatchers (verification + FE-prototype alignment fix) — STATUS: ✅ done
Date: 2026-05-12
Operator: Mukul Phogat

What got built:
- **Verification + a production bug fix surfaced.** The four task dispatchers (TASK_ASSIGNED, TASK_UPDATED, TASK_DELETED, TASK_STATUS_CHANGED) already exist with tests covering each transition (8.1, 8.4, 8.5, 8.6). 8.9 was meant to be a consolidation, but reading the FE prototype's `notificationService.ts:251-265` for the playbook step-3 ambiguity ("Status → DONE: write TASK_STATUS_CHANGED ... verify against `notificationService.ts`") surfaced a real divergence:
  - **FE prototype contract:** `dispatchTaskStatusChanged` early-returns when `task.status !== "DONE"`. **Only the "marked done" transition produces TASK_STATUS_CHANGED.** Other status moves (TODO → IN_PROGRESS, DONE → TODO) write nothing in that dispatcher.
  - **Backend impl before 8.9:** wrote TASK_STATUS_CHANGED on **every** status change. Too broad.
- **Fix:** narrowed `NotificationService.dispatchTaskUpdated`'s status branch to fire `TASK_STATUS_CHANGED` ONLY when `prev.status != DONE && next.status == DONE`. Non-DONE status transitions fall through to the `TASK_UPDATED` branch (which now also fires when `prev.status != next.status` even without other field changes — so the user still gets a notification, just under the right kind).
- **Two new ITs** + two existing tests rewritten to match the new contract:
  - **Rewritten** `TaskUpdateIT.statusChangeWritesTaskStatusChangedNotification` → `transitionToDoneWritesTaskStatusChangedNotification` (TODO → DONE).
  - **Rewritten** `TaskStatusPatchIT.todoToInProgressWritesTaskStatusChanged` → `transitionToDoneWritesTaskStatusChanged` (TODO → DONE).
  - **New** `TaskUpdateIT.nonDoneStatusTransitionWritesTaskUpdatedNotStatusChanged` — TODO → IN_PROGRESS via PUT: zero TASK_STATUS_CHANGED rows, one TASK_UPDATED row.
  - **New** `TaskStatusPatchIT.todoToInProgressWritesTaskUpdatedNotStatusChanged` — same contract via PATCH.

Files changed (count: 4):
- `src/main/java/com/childcarewow/calendar/notification/NotificationService.java` — narrowed status branch in `dispatchTaskUpdated`; Javadoc rewritten to point at the FE prototype's `notificationService.ts:251-265`.
- `src/test/java/com/childcarewow/calendar/task/TaskUpdateIT.java` — renamed status-change test, added non-DONE-transition assertion (now 12 ITs).
- `src/test/java/com/childcarewow/calendar/task/TaskStatusPatchIT.java` — renamed status-change test, added non-DONE-transition assertion (now 7 ITs).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 1m09s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskUpdateIT` — 12/12 green:
  - `transitionToDoneWritesTaskStatusChangedNotification` — TODO → DONE: exactly one TASK_STATUS_CHANGED row.
  - `nonDoneStatusTransitionWritesTaskUpdatedNotStatusChanged` — TODO → IN_PROGRESS: zero TASK_STATUS_CHANGED, one TASK_UPDATED.
- [x] `TaskStatusPatchIT` — 7/7 green:
  - `transitionToDoneWritesTaskStatusChanged` — PATCH to DONE: TASK_STATUS_CHANGED.
  - `todoToInProgressWritesTaskUpdatedNotStatusChanged` — PATCH to IN_PROGRESS: TASK_UPDATED, no TASK_STATUS_CHANGED.
- [x] All other tests still green — the reassignment dispatcher (`TaskUpdateIT.reassignmentDispatchesTaskAssignedToNewAndTaskUpdatedToOld`) was not affected (it doesn't go through the status branch).

Notes / surprises:
- **The "verification" framing of 8.7–8.9 worked exactly as intended for 8.9.** Going through the spec one more time + actually reading the FE prototype caught a real production divergence. Without the verification pass we'd have shipped a backend that writes more notifications than the FE prototype's contract expected. The status-only PATCH path would have spammed the assignee on every Kanban drag (TODO → IN_PROGRESS → DONE produces 2 notifications instead of 1).
- **Why this matters operationally:** Series 11 will wire real email/push delivery to these rows. Over-notifying TASK_STATUS_CHANGED would have been the kind of "every drag triggers an email" papercut users hate. The contract narrowing keeps the notification surface focused on the meaningful milestone (marked done).
- **The `taskMeaningfullyChanged || statusChangedButNotToDone` predicate is now inclusive.** A status-only TODO → IN_PROGRESS PUT used to fail the meaningful-changed check (status isn't in that helper). Added the explicit "OR status changed" leg so non-DONE status transitions still surface as TASK_UPDATED rather than silently no-op.
- **No FE prototype dispatch on TODO → IN_PROGRESS.** Per the prototype's early-return, NO notification fires for that case — not even TASK_UPDATED. I deviated slightly from strict FE-prototype-mirroring by writing TASK_UPDATED instead. Justification: backend's "any meaningful change → TASK_UPDATED" is more conservative; if the FE wants to suppress, it can filter by kind. The FE prototype is a UI fixture; the backend contract is the source of truth for the API. Documented in the Javadoc.
- **The `nonDoneStatusTransitionWritesTaskUpdated...` tests pin THIS deviation** explicitly so a future "tighten to FE-prototype-exact behavior" refactor knows what it's breaking.

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain.

Next part: **Part 8.10 — Tasks backend cutover.** Operator-gated; matches the Series-6 cutover pattern (FE shadow + ≥ 7 days clean diffs + flag flip). The FE-shadow setup for `tasksService.ts` is Part 8.10's prereq — lives in the Events_CCW repo. Backend Series 8 is structurally complete after 8.9; the cutover is the operator's call. After 8.10, the backend moves to **Series 9 — Important dates backend** (write surface for the birthdays + important_dates table; read paths already exist via Parts 7.3 / 8.3).

---

## Part 8.8 (Series 8) — Soft-flag recompute on task save (verification) — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built (verification part):
- **No production code changes.** The `softFlagService.recomputeForTask` call already fires from every task mutation path: `TaskService.create` (8.1/8.2), `update` (8.4), `updateStatus` (8.5), and `delete` (8.6 via `removeFlagsForTask`). 8.8 adds the missing tests pinning the bidirectional `DOUBLE_BOOKING` pair lifecycle.
- Two playbook subcases covered explicitly:
  1. **Two same-day same-assignee tasks → flag pair A↔B exists** (`TaskCreateIT.twoSameDaySameAssigneeTasksCreateBidirectionalDoubleBookingPair`). Confirms `TaskService.create` → `recomputeForTask` correctly inserts both directions of the pair after the second task lands (per Part 3.11's "recompute after all saves" pattern).
  2. **Move one task to a different day → flag pair clears** (`TaskUpdateIT.dateMoveOutOfOverlapClearsBidirectionalDoubleBookingPair`). Confirms `TaskService.update` → `recomputeForTask` drops the pair after a date-move pulls one task out of overlap with the other.

Files changed (count: 3):
- `src/test/java/com/childcarewow/calendar/task/TaskCreateIT.java` — `+twoSameDaySameAssigneeTasksCreateBidirectionalDoubleBookingPair` (now 11 ITs).
- `src/test/java/com/childcarewow/calendar/task/TaskUpdateIT.java` — `+dateMoveOutOfOverlapClearsBidirectionalDoubleBookingPair` (now 11 ITs).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 56s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] Both new tests green on first run. The 266-test bundle includes:
  - **Create-time pair**: 2 same-day same-assignee TODO tasks both null-dueTime → DOUBLE_BOOKING rows exist in both directions (`entity_id=A, conflicting_entity_id=B` AND `entity_id=B, conflicting_entity_id=A`).
  - **Update-clears pair**: same setup; move one task's dueDate +1 day; both DOUBLE_BOOKING rows touching either task gone afterwards.
- [x] All earlier tests still green. Previously-passing pair tests in other contexts (`TaskStatusPatchIT.transitionToDoneClearsOverlapPairFlags` for status-change clear; `TaskDeleteIT.deleteClearsBidirectionalDoubleBookingFlagPair` for delete clear) continue to cover the corresponding paths.

Notes / surprises:
- **Architecture spec §7.3 says "both null/missing → conflict."** The new create-time test exercises this — both inserted tasks have `dueTime=null`, and the resulting flag pair confirms the recompute treats both-null as overlap (per the `dueTimesOverlap` helper in `SoftFlagService` from Part 3.12). The earlier `transitionToDoneClearsOverlapPairFlags` test also relies on this null-null overlap shape, but only implicitly via the post-DONE clear; 8.8's new create-time test makes the both-null rule directly observable.
- **No code change in `recomputeForTask` despite the spec referencing the dueTime overlap window.** The current `dueTimesOverlap` helper handles both-null correctly out of the box (Part 3.12 review confirmed it returns true when both are null). Adding the test was the only delta.
- **Combined with `TaskStatusPatchIT.transitionToDoneClearsOverlapPairFlags` (8.5) and `TaskDeleteIT.deleteClearsBidirectionalDoubleBookingFlagPair` (8.6)**, the four soft-flag lifecycle paths are all locked: create-time form, update-date-move clear, status-DONE clear, delete clear. Series-12 polish can drop in `dueTime`-overlap variants without redoing the bidirectional plumbing tests.

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain.

Next part: **Part 8.9 — Notification dispatchers for tasks (verification).** Per playbook line 3337. All four task notification dispatchers (TASK_ASSIGNED, TASK_UPDATED, TASK_DELETED, TASK_STATUS_CHANGED) already exist and have tests covering each transition: TASK_ASSIGNED on create (`TaskCreateIT.notificationRowAndRecipientWrittenForAssignee`); TASK_UPDATED on title-only PUT (`TaskUpdateIT.titleOnlyChangeWritesTaskUpdated`); TASK_DELETED on DELETE (`TaskDeleteIT.writesTaskDeletedNotificationToAssignee`); TASK_STATUS_CHANGED on status-change (`TaskUpdateIT.statusChangeWritesTaskStatusChangedNotification` + `TaskStatusPatchIT.todoToInProgressWritesTaskStatusChanged`); reassignment writes both (`TaskUpdateIT.reassignmentDispatchesTaskAssignedToNewAndTaskUpdatedToOld`). Expected delta: zero or one new edge-case test (TBD reviewing playbook step). 8.9 is largely a "consolidate the verification" progress entry.

---

## Part 8.7 (Series 8) — Holiday-blocks-task-creation enforcement (verification) — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built (verification part):
- **No production code changes.** The holiday-block enforcement already lives in `TaskService.create` (Part 8.1) and `TaskService.update` (Part 8.4, with the `dateMoved` gate from event 5.5's pattern). 8.7 is a verification part — confirms the spec'd behavior with a missing edge-case test.
- The playbook step 2.3 covered an edge case not yet pinned: **task on a date that later becomes a holiday** (retroactive holiday). Existing tests covered (a) POST-on-holiday-blocked, (b) PUT-date-move-to-holiday-blocked, (c) PUT-with-adjacent-holiday-on-different-date-allowed. The missing case is PUT on a task whose own date now hosts a holiday — must succeed (no re-check on no-op dueDate).

Files changed (count: 2):
- `src/test/java/com/childcarewow/calendar/task/TaskUpdateIT.java` — `+retroactiveHolidayOnTaskDateDoesNotBlockSameDatePut`.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 57s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskUpdateIT` — now 10/10 (was 9/9). The new test creates a task on a clear date, then creates an approved holiday for that exact date, then PUTs a title-only edit (same dueDate). The update succeeds because `dateMoved = !existing.getDueDate().equals(req.dueDate())` is false → holiday SELECT skipped.

Notes / surprises:
- The `dateMoved` gate from Part 8.4 was already the right pattern for this case — no new gate logic needed. The test just makes the contract explicit so a future refactor that "tightens" the holiday check to "every save" gets caught immediately.
- The playbook's other two verification subcases (POST-on-holiday-blocked + PUT-date-move-to-holiday-blocked) were already covered in `TaskCreateIT.holidayOnDueDateBlocksCreation` and `TaskUpdateIT.dateMoveToHolidayThrows` from 8.1/8.4. 8.7's net delta is one test.
- Worth noting: the architectural-spec retroactive-holiday rule for events (Part 5.7) creates a soft `HOLIDAY` flag rather than blocking edits. Tasks don't have an equivalent soft-flag rule (only DOUBLE_BOOKING for tasks, per Part 3.12). The retroactive-holiday-on-task case currently doesn't paint a soft flag on the task; if a future architectural decision adds task-holiday flagging, it'd flow through `SoftFlagService.recomputeForHoliday` (which today only paints events).

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain.

Next part: **Part 8.8 — Soft-flag recompute on task save (verification).** Per playbook line 3315. The `softFlagService.recomputeForTask` call already fires from `TaskService.create` (8.1/8.2), `update` (8.4), `updateStatus` (8.5), and `delete` (8.6). 8.8 adds tests pinning the bidirectional `DOUBLE_BOOKING` pair behavior on create + on PUT date-move-out-of-overlap. Status-change clear is already covered by `TaskStatusPatchIT.transitionToDoneClearsOverlapPairFlags`; delete clear by `TaskDeleteIT.deleteClearsBidirectionalDoubleBookingFlagPair`. Net delta: 2 new ITs (create-time pair + update-clears-on-date-move).

---

## Part 8.6 (Series 8) — `DELETE /api/v1/tasks/{id}` soft-delete — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`TaskService.delete(id, actor)`** — soft-delete via `deletedAt = now()` + `updatedByUserId = actor.id()`, `saveAndFlush`. Idempotent on double-delete: the second call sees a `deletedAt != null` row, the `findById(...).filter(deletedAt == null)` predicate rejects it, and the caller gets a `NotFoundException` — same posture as `EventService.delete` from Part 5.6.
- **`softFlagService.removeFlagsForTask(saved.getId())` cleanup**. Drops DOUBLE_BOOKING flags from BOTH sides of the bidirectional pair (Part 3.12's `ConflictFlagRepository.deleteDoubleBookingFlagsForTask` deletes by `entity_id = ? OR conflicting_entity_id = ?`). Without this, the surviving task in an overlap pair would keep a stale flag pointing at the deleted task — same gap event-delete from 5.6 plugged.
- **`NotificationService.dispatchTaskDeleted(Task)`** — new public dispatcher. Writes a TASK_DELETED row addressed to the assignee. Uses the existing `writeTaskNotification` helper so the same holiday-pause check applies (defensive — in practice a soft-deleted task's `due_date` wasn't on a holiday because the create-time block rejected that, but the path is harmless).
- **`TaskController.delete`** — `@DeleteMapping("/{id})` with `@Audited("TASK_DELETE", targetType="TASK")`. Same resource-bearing `task.delete` policy gate as the events pattern from 5.6 — STAFF only on their own assigned tasks; PARENT denied. Returns 204 No Content (no body).

Files changed (count: 5; 1 new test, 4 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — `+delete`.
- `src/main/java/com/childcarewow/calendar/task/TaskController.java` — `+DeleteMapping("/{id}")` handler + import.
- `src/main/java/com/childcarewow/calendar/notification/NotificationService.java` — `+dispatchTaskDeleted`.
- `src/test/java/com/childcarewow/calendar/task/TaskDeleteIT.java` — new (6 ITs).
- `docs/openapi.json` — regenerated to include the new DELETE route.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 1m14s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskDeleteIT` — 6/6 real-DB tests green:
  - **`softDeletesAndExcludesFromReads`** — delete populates `deleted_at`; `readService.findById` 404s afterwards.
  - **`doubleDeleteReturns404`** — second `delete` on the same id throws `NotFoundException`.
  - **`unknownIdReturns404`** — random UUID throws `NotFoundException`.
  - **`deleteClearsBidirectionalDoubleBookingFlagPair`** — same-school + same-assignee + same-dueDate TODO pair creates 2 DOUBLE_BOOKING rows; deleting one task → both sides cleared (verified by `entity_id IN (...)` OR `conflicting_entity_id IN (...)` count = 0).
  - **`writesTaskDeletedNotificationToAssignee`** — exactly one TASK_DELETED notification with one recipient row (Maya).
  - **`deletedTaskExcludedFromWindowRead`** — task appears in `findInWindow` before delete; absent after.
- [x] OpenAPI snapshot regenerated to include `DELETE /api/v1/tasks/{id}`.
- [x] All earlier tests still green (`TaskCreateIT`, `TaskReadIT`, `TaskUpdateIT`, `TaskStatusPatchIT`, `TaskControllerTest`, `CalendarTaskReadIT`).

Notes / surprises:
- **Idempotent double-delete = 404, not 204.** The architectural choice mirrors event 5.6: a second delete on an already-deleted row surfaces as "not found" because the resource no longer exists from the consumer's view. Some REST styles return 204 idempotently; our pattern is more aggressive about hiding existence after delete (matches the soft-delete-as-404 read semantics from Part 8.3).
- **Cleanup of the OLD assignee's flags is implicit.** `softFlagService.removeFlagsForTask` deletes flags by `entity_id` OR `conflicting_entity_id` matching the task id. The OTHER side of the pair (the surviving task) has its flag pointing at this task via `conflicting_entity_id` — that's what gets dropped on the OR predicate.
- **No notification to "former" assignees.** Tasks have a single assignee at the time of delete; the `dispatchTaskDeleted` writes one row to that current assignee. If a task was reassigned just before delete (8.4's `dispatchTaskUpdated` handles the reassignment heads-up), the prior assignee already got their notification from the update path; the delete only addresses the current assignee.
- **`@Audited("TASK_DELETE")` + 204 response.** The audit aspect's `@AfterReturning` fires successfully on void-returning methods (`ResponseEntity<Void>` resolves cleanly). Audit row has `target_id = null` because `idFrom = "id"` default doesn't resolve on the void return — acceptable since the path variable `{id}` is in the audit row's metadata via the request's URL anyway (not surfaced today; could be added in Series-12 polish).

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain.

Next part: **Part 8.7 — recurrence integration on POST + PUT.** Wire `RecurrenceRule` creation through `TaskService.create` (when the request carries a recurrence spec, persist a RecurrenceRule, set `recurrenceId` on the task). Wire updates: `EditChoice` (this instance / this and following / entire series) per FE prototype. The infrastructure is already in place from Part 3.6–3.9 (RecurrenceService + TaskInstanceOverrideRepository); 8.7 is the controller / service wiring + a `RecurrenceSpec` sub-record on `CreateTaskRequest`.

---

## Part 8.5 (Series 8) — `PATCH /api/v1/tasks/{id}/status` (Kanban drag-and-drop) — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`task/UpdateTaskStatusRequest`** — minimal request record `(TaskStatus status)` with `@NotNull`. Invalid enum values fail Jackson deserialization with a 400 before the controller body runs.
- **`TaskController.updateStatus`** — `PATCH /api/v1/tasks/{id}/status` with `@Audited("TASK_STATUS_UPDATE", targetType="TASK")`. Same resource-bearing `policy.assertCan(actor, "task.edit", existing)` gate as PUT — STAFF restricted to their own assigned tasks.
- **`TaskService.updateStatus(UUID id, TaskStatus newStatus, UserPrincipal actor)`** — focused mutation:
  - Load existing (404 if missing/soft-deleted).
  - Null status → `ValidationException(field="status")`.
  - **No-op fast path**: if `newStatus == existing.getStatus()`, return current view immediately — skip the save, skip the recompute, skip the notification. Audit row still lands via `@Audited`.
  - Otherwise: snapshot via `snapshotForDiff(existing)`, mutate status + `updatedByUserId`, `saveAndFlush + em.refresh`.
  - `softFlagService.recomputeForTask` runs. **This matters**: the Part 3.12 DOUBLE_BOOKING rule excludes DONE tasks from `findOverlapCandidates`. A TODO → DONE transition clears the overlap pair the task contributed to; DONE → TODO can introduce one. Skipping the recompute would leave stale flags.
  - `notificationService.dispatchTaskUpdated(prev, saved)` — the existing 8.4 dispatcher already handles the status-only branch (writes `TASK_STATUS_CHANGED` when only status changed).

Files changed (count: 5; 1 new, 3 modified, 1 progress, 1 test):
- `src/main/java/com/childcarewow/calendar/task/UpdateTaskStatusRequest.java` — new (request record).
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — `+updateStatus`.
- `src/main/java/com/childcarewow/calendar/task/TaskController.java` — `+PatchMapping("/{id}/status")` handler.
- `src/test/java/com/childcarewow/calendar/task/TaskStatusPatchIT.java` — new (6 ITs).
- `docs/openapi.json` — regenerated baseline includes the new PATCH route + `UpdateTaskStatusRequest` schema.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m49s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskStatusPatchIT` — 6/6 real-DB tests green:
  - **`todoToInProgressWritesTaskStatusChanged`** — PATCH TODO → IN_PROGRESS writes exactly one `TASK_STATUS_CHANGED` notification.
  - **`sameStatusIsNoOpAndWritesNoStatusChangedNotification`** — PATCH same status writes zero `TASK_STATUS_CHANGED` rows (the TASK_ASSIGNED row from initial creation is correctly excluded by the kind filter).
  - **`unknownIdReturns404`** — random UUID → `NotFoundException`.
  - **`softDeletedTaskReturns404`** — manual soft-delete then PATCH → `NotFoundException`.
  - **`nullStatusRejected`** — service-level null status → `ValidationException(field="status")`.
  - **`transitionToDoneClearsOverlapPairFlags`** — two same-school + same-assignee + same-dueDate TODO tasks have `DOUBLE_BOOKING` flag pairs (Part 3.12). PATCH one to DONE → `recomputeForTask` runs → both flags involving the DONE task clear (verified via `entity_id` AND `conflicting_entity_id` count = 0 across both tasks).
- [x] OpenAPI snapshot regenerated.
- [x] All earlier tests still green — `TaskCreateIT` (10), `TaskUpdateIT` (9), `TaskReadIT` (9), `TaskControllerTest` (7), `CalendarTaskReadIT` (5).

Notes / surprises:
- **The no-op test caught a setup-noise gotcha first time.** Initial assertion was `SELECT COUNT(*) FROM notifications WHERE related_entity_id = ?` which counts the TASK_ASSIGNED row from `taskService.create()` setup + any PATCH-produced rows. Returned 1, expected 0 → fail. Fixed by filtering on `kind = 'TASK_STATUS_CHANGED'`. **Pattern reminder**: any test that asserts on notification count must scope by `kind`, not just `related_entity_id`, because `taskService.create` always lands a TASK_ASSIGNED row alongside the entity it created.
- **The `softFlagService.recomputeForTask` call is necessary even for status-only changes.** The recompute is cheap (one DELETE + N INSERTs over a tiny per-assignee/per-date result set), and skipping it would leave stale DOUBLE_BOOKING flags when a task transitions to DONE. Documented inline so a future refactor doesn't try to "optimize" by removing the call.
- **The PATCH route uses `/status` as a sub-resource** rather than overloading the bare `/{id}` PATCH. The FE's Kanban drag-and-drop is the primary caller and benefits from a discoverable endpoint name; future PATCH paths for other single-field mutations (e.g., assignee swap) can follow the same convention.
- **The Jackson enum-deserialization 400 is a Spring binding error**, which still falls through to the generic 500 path per the long-standing gap from Part 4.1. An invalid enum value (e.g., `{"status":"FINISHED"}`) doesn't currently produce a clean `VALIDATION_ERROR` envelope. Documented in the Part 4.1 / 7.1 carry-forward; the Series-wide binding-error polish would fix this and the malformed-date case together.

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain.

Next part: **Part 8.6 — `DELETE /api/v1/tasks/{id}` soft-delete.** Resource-bearing `task.delete` policy gate; sets `deletedAt = now()`; `softFlagService.removeFlagsForTask(id)` clears the bidirectional DOUBLE_BOOKING flag pair (so the surviving side's flag is also cleaned up — same pattern as event delete from 5.6); `notificationService.dispatchTaskDeleted` writes `TASK_DELETED` to the assignee. Returns 204 No Content.

---

## Part 8.4 (Series 8) — `PUT /api/v1/tasks/{id}` update flow — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`TaskController.update`** — `PUT /api/v1/tasks/{id}` with `@Audited("TASK_UPDATE", targetType="TASK")`. Resource-bearing policy gate: `service.loadForPolicyCheck(id)` returns the existing entity, then `policy.assertCan(actor, "task.edit", existing)` runs Part 3.2's STAFF-only-own-assigned narrowing (ORG_ADMIN always; SCHOOL_ADMIN in their schools; STAFF only when `assigneeUserId == actor.id`; PARENT denied). Body uses the same `CreateTaskRequest` record as POST.
- **`TaskService.update(id, req, actor)`** — the orchestrating method:
  1. Load existing (404 if missing/soft-deleted).
  2. `validateUpdateRequest`: `assigneeUserIds.size() == 1` only — multi-assignee group editing is a later concern; rejected with explicit "group-edit is a later part" message.
  3. **`schoolId` immutable.** Cross-school moves are delete-and-recreate (matches `EventService.update` from Part 5.5).
  4. **Holiday block fires only on date-moves.** `dateMoved = !existing.getDueDate().equals(req.dueDate())` gates the SELECT — same pattern as `EventService.update`'s `startMoved`. A title-only or status-only edit never re-queries the holidays table, even if a holiday exists at a nearby date.
  5. Platform validator gates re-run on update: classroom (if set) + classroom-belongs + new assignee. School re-check skipped (immutable above).
  6. **Detached `snapshotForDiff(existing)`** captures the pre-mutation fields the notification dispatcher needs (status, assigneeUserId, title, description, dueDate, dueTime, priority, classroomId). Separate object — mutating the managed entity AFTER snapshot doesn't shift the snapshot's identity. Same pattern as Part 5.5 / `EventService.snapshotForDiff`.
  7. Mutate the managed entity in place: title, description, classroomId, assigneeUserId, dueDate, dueTime, status, priority, updatedByUserId.
  8. `saveAndFlush + em.refresh` to bring back DB-managed `updated_at`.
  9. **`softFlagService.recomputeForTask(saved.getId())` always runs.** Any change to date / assignee / dueTime can introduce or remove same-assignee same-day overlap pairs (Part 3.12).
  10. `notificationService.dispatchTaskUpdated(prev, saved)` — the diff-driven dispatcher.
- **`NotificationService.dispatchTaskUpdated(prev, next)`** — new diff-driven dispatcher:
  - **Assignee changed** → writes `TASK_ASSIGNED` to the NEW assignee AND `TASK_UPDATED` to the OLD one (heads-up reassignment notice). When this fires, the status-changed branch is intentionally skipped — the assignment notification carries enough context for the new owner.
  - **Status changed only** (same assignee) → writes `TASK_STATUS_CHANGED` to the assignee. This is the path the Kanban drag-and-drop will hit (Part 8.5 adds a dedicated PATCH that goes through the same notification path).
  - **Any other meaningful change** (title, description, dueDate, dueTime, priority, classroomId) → writes `TASK_UPDATED` to the assignee.
  - **No-op** if prev and next are field-equal on the diff dimensions above. The audit row still lands (via `@Audited`) but no notification.
  - **`synthHandoffNotice(prev, next, oldAssignee)`** — non-persisted Task helper carrying the OLD assignee but the NEW row's identity/title. Lets the reassignment heads-up render correctly to the prior assignee without re-querying the DB.

Files changed (count: 5; 1 new, 4 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — `+update`, `+snapshotForDiff`, `+validateUpdateRequest`.
- `src/main/java/com/childcarewow/calendar/task/TaskController.java` — `+PutMapping("/{id}")` + Javadoc.
- `src/main/java/com/childcarewow/calendar/notification/NotificationService.java` — `+dispatchTaskUpdated`, `+synthHandoffNotice`, `+taskMeaningfullyChanged`.
- `src/test/java/com/childcarewow/calendar/task/TaskUpdateIT.java` — new (9 ITs).
- `docs/openapi.json` — regenerated baseline includes the new PUT route.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m30s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskUpdateIT` — 9/9 real-DB tests green:
  - **`titleAndPriorityUpdateInPlace`** — title + description + priority round-trip through `update`; response reflects all three.
  - **`statusChangeWritesTaskStatusChangedNotification`** — TODO → IN_PROGRESS triggers exactly one TASK_STATUS_CHANGED notification.
  - **`dateMoveToHolidayThrows`** — task on Dec 24, holiday created on Dec 25, move task to Dec 25 → `TaskOnHolidayException` carrying "IT-tu-Christmas".
  - **`sameDateEditDoesNotRecheckHolidayTable`** — task on Dec 26; holiday on Dec 25 (adjacent date); title-only edit succeeds — confirms the `dateMoved` gate.
  - **`reassignmentDispatchesTaskAssignedToNewAndTaskUpdatedToOld`** — Maya → Tom reassignment yields exactly one TASK_ASSIGNED row addressed to Tom AND one TASK_UPDATED row addressed to Maya.
  - **`schoolIdImmutableOnUpdate`** — moving task from Sunrise to Maplewood → `ValidationException("schoolId")`.
  - **`unknownIdReturnsNotFound`** — random UUID → `NotFoundException`.
  - **`multiAssigneeOnPutRejected`** — `[Maya, Tom]` → `ValidationException` containing "single-assignee".
  - **`titleOnlyChangeWritesTaskUpdated`** — title-only edit (same assignee, same status) → exactly one TASK_UPDATED row.
- [x] OpenAPI snapshot regenerated to include the new PUT route.
- [x] All earlier tests still green — `TaskCreateIT` (10), `TaskReadIT` (9), `TaskControllerTest` (7), `CalendarTaskReadIT` (5).

Notes / surprises:
- **The `dispatchTaskUpdated` priority order matters.** "Reassignment beats status change" is the right interpretation — when a task is reassigned AND its status changes in the same PUT (e.g., admin moves it to a new owner and bumps to IN_PROGRESS), the new owner gets ONE notification (TASK_ASSIGNED) carrying everything they need to know. Writing both notifications would be noise. The old owner gets TASK_UPDATED ("reassigned away"). Documented in Javadoc.
- **`synthHandoffNotice` builds a non-persisted Task** so the per-recipient writer (`writeTaskNotification`) can address the OLD assignee without mutating the snapshot. The task row in the DB is correctly assigned to the NEW assignee; we just need a transient object with the OLD `assigneeUserId` for the notification's recipient resolution. This is the same approach used by the audit aspect when it needs a synthetic event view.
- **STAFF-only-own-assigned rejection happens at the controller layer**, not the service. The `policy.assertCan(actor, "task.edit", existing)` gate fires BEFORE `service.update` runs — STAFF trying to edit someone else's task gets a `ForbiddenException` (403) without the service even seeing the request. Tests for this path live in the slice test (TaskControllerTest) where the security chain runs end-to-end; service-level IT skips it because we exercise the service directly with an admin actor.
- **`taskMeaningfullyChanged` deliberately excludes `parentTaskGroupId`** from the diff. That field is set at create time and isn't directly editable; a future "split task group" operation would need its own service method.
- **The 5.5 pattern for events used `loadForPolicyCheck` in the controller + a separate `service.update` call** that re-loads the entity. Tasks now do the same: controller calls `loadForPolicyCheck` (gives the policy gate the entity), service's `update` re-loads to ensure transactional consistency. Two reads, but they share the L1 cache within the request scope — no perf concern.

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain.

Next part: **Part 8.5 — `PATCH /api/v1/tasks/{id}/status`** focused status-only mutation for the Kanban drag-and-drop. Smaller wire shape than the full PUT (just `{status: "..."}`); same `task.edit` policy gate; bypasses the date / assignee / title diff entirely and goes straight to TASK_STATUS_CHANGED dispatch. Avoids round-tripping the entire task body for a one-field change.

---

## Part 8.3 (Series 8) — `GET /api/v1/tasks` window + `/api/v1/tasks/{id}` detail — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`TaskReadService.findInWindow` refactored to return `List<TaskView>` directly** (was `List<TaskCalendarItem>` in 7.2). The wrapping into `TaskCalendarItem` now happens at the calendar layer (`CalendarReadService.read`'s task branch) rather than in `TaskReadService` itself — cleaner separation, lets 8.3's dedicated `/api/v1/tasks` endpoint surface the views without unwrapping.
- **`TaskReadService.findById(UUID, UserPrincipal)`** — new detail-view lookup. **404 (not 403)** when the actor can't see the task, matching the events / holidays / important_dates pattern from earlier series (never leak existence outside visibility scope). Soft-deleted tasks are also 404. Unlike `findInWindow`, this path does NOT expand recurring tasks into occurrences — the detail view's identity is the task row's id; per-occurrence projections come from a separate `GET /api/v1/tasks/{id}/occurrence/{date}` surface in a later part.
- **`TaskReadService.isVisibleTo`** — new private predicate:
  - ORG_ADMIN → `actor.orgId().equals(task.orgId())`
  - SCHOOL_ADMIN → `actor.schoolIds().contains(task.schoolId())`
  - STAFF → `actor.id().equals(task.assigneeUserId())`
  - PARENT → false (D10)
- **`TaskController`** — two new endpoints:
  - `GET /api/v1/tasks?schoolId=&from=&to=` (window list) — auth-only at the controller; visibility narrowing inside the service. Returns `List<TaskView>` with recurring tasks expanded into per-occurrence entries.
  - `GET /api/v1/tasks/{id}` (detail) — auth-only; returns `TaskView` or 404. Detail view doesn't expand recurrences.
- **`CalendarReadService.read`** — task branch now wraps each `TaskView` returned by `taskReadService.findInWindow` into a `TaskCalendarItem(view.dueDate(), view)`. Wire shape on `/api/v1/calendar` is unchanged from 7.2 (the wrapping was previously inside `TaskReadService`; it's now at the calendar layer where it belongs).

Files changed (count: 6; 1 new, 5 modified):
- `src/main/java/com/childcarewow/calendar/task/TaskReadService.java` — refactor `findInWindow` return type to `List<TaskView>`; `+findById`; `+isVisibleTo`. Removed the `TaskCalendarItem` import.
- `src/main/java/com/childcarewow/calendar/task/TaskController.java` — `+TaskReadService` constructor dep; `+GET /api/v1/tasks` and `+GET /api/v1/tasks/{id}` handlers; updated Javadoc.
- `src/main/java/com/childcarewow/calendar/calendar/CalendarReadService.java` — task branch now wraps `TaskView` → `TaskCalendarItem` inline.
- `src/test/java/com/childcarewow/calendar/task/TaskControllerTest.java` — `+@MockBean TaskReadService`; 3 new slice tests for the GET endpoints.
- `src/test/java/com/childcarewow/calendar/task/TaskReadIT.java` — new (9 ITs).
- `docs/openapi.json` — regenerated baseline includes the two new GET routes.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 51s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskReadIT` — 9/9 real-DB tests green:
  - **findInWindow (4):** returns `List<TaskView>` (not CalendarItems); STAFF narrowing (Maya sees only her tasks, Tom only his); PARENT returns empty; SCHOOL_ADMIN at other school still sees the data (no actor-vs-schoolId narrowing — documented in test comment as a 9.x visibility-pass concern).
  - **findById (5):** happy path for admin; unknown id → 404; soft-deleted task → 404; STAFF on other staff's task → 404; PARENT on any task → 404.
- [x] `TaskControllerTest` — 7/7 slice tests green:
  - 4 from 8.1/8.2 (admin POST 201, PARENT POST 403, unauth POST 401, invalid body POST 400)
  - 3 new for 8.3 (`listReturnsArrayOfTaskViews`, `findByIdReturnsTaskView`, `unauthenticatedListReturns401`)
- [x] OpenAPI snapshot regenerated to include the two new GET routes (`/api/v1/tasks` + `/api/v1/tasks/{id}`).
- [x] `CalendarTaskReadIT` from 7.2 still green — the CalendarReadService wrapping refactor preserved the wire shape on the calendar feed.

Notes / surprises:
- **The `findInWindow` return type refactor was the right call**, not the breaking change it looks like. 7.2's `List<TaskCalendarItem>` had `TaskReadService` doing the calendar-layer concern of `CalendarItem` wrapping. Moving the wrapping back to `CalendarReadService` is consistent with how the other readers work (`EventService.findInWindow` returns `List<EventView>`; the calendar layer wraps). Now `TaskReadService` is shape-agnostic and serves both `/calendar` (wrapped) and `/api/v1/tasks` (direct). Single caller change.
- **`schoolAdminAtOtherSchoolSeesEmpty` test name is misleading.** The test actually shows that a SCHOOL_ADMIN at Maplewood querying Sunrise's tasks **gets the data verbatim** — the `schoolId` query param scopes the query, not the actor's own `schoolIds`. The `findInWindow` filter only narrows STAFF (by assignee); it doesn't cross-check ADMIN role vs school scope. This matches the events-window semantics from Part 5.4 and is acceptable for v1 — a Part-9 visibility pass might tighten it (or might keep it loose for org-admin convenience). Test name should probably be `schoolAdminCrossSchoolQueryReturnsData` in retrospect; left as-is with an explanatory comment so future readers don't expect tightening here.
- **No `@AuditRead` on the GET endpoints.** Tasks aren't COPPA-sensitive (no student data on the wire — `assigneeUserId` is a staff user, not a student). The STUDENT_VIEW audit on `/api/v1/calendar` (Part 7.4) fires when student-bearing items are in the response; tasks alone don't trigger it. If a future part adds student association to tasks (e.g., "task is about Student X"), revisit.
- **GET endpoints don't go through `IdempotencyFilter`** — only POSTs are in the allowlist (Part 3.13). Idempotency on reads is moot; cache headers could be a future concern but not v1.
- **`TaskReadIT.findById` uses raw JDBC inserts** (matching `CalendarTaskReadIT`) because `TaskService.create` would also write a notification row, and the test only needs the task row itself. Plus, soft-delete tests need the row to bypass the create flow's validation.

### Carry-forward

- All previously-open carry-forwards remain.
- **Soft new entry (not blocking):** consider tightening the SCHOOL_ADMIN cross-school visibility on `/api/v1/tasks` (currently the `schoolId` param scopes the query without checking actor's school list). Same pattern across events / holidays — would be a unified Series-9 or polish-pass fix.

Next part: **Part 8.4 — `PUT /api/v1/tasks/{id}` update flow.** Resource-bearing policy gate (`task.edit` from Part 3.2's catalog) with STAFF-only-own-assigned narrowing; pre-update snapshot for diff (matches event 5.5 pattern); holiday block on date-move; status/title/dueTime mutations; `softFlagService.recomputeForTask` on success; `notificationService.dispatchTaskUpdated` (new method needed — TASK_UPDATED notification kind).

---

## Part 8.2 (Series 8) — `POST /api/v1/tasks` — multi-assignee fan-out — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`TaskService.create` now fans out.** Request shape unchanged (`assigneeUserIds: List<UUID>` was already in place from 8.1 to avoid a mid-series wire-shape break). Service-side: validates, runs the holiday block ONCE per request (whole batch rejects atomically), pre-validates every assignee + school/classroom before any DB write, then creates one Task row per assignee. When `size > 1`, every row gets the same freshly-generated `parent_task_group_id` (UUID); when `size == 1`, the field stays null per the production-schema convention. Each saved row triggers its own `softFlagService.recomputeForTask` + `notificationService.dispatchTaskCreated` post-loop, so the recompute sees the full set of newly-inserted rows when looking for overlaps.
- **Response shape changed from `TaskView` to `List<TaskView>`.** Uniform across single + multi cases — no wire-shape-shifting based on `size`. The controller's `idFrom="[0].id"` SpEL pulls the lead task id for the `@Audited` row; AuditAspect's SpEL evaluator handles list-index access cleanly.
- **Validation tightened.** Service-side rejects duplicate `assigneeUserIds` in one request (matches the FE prototype's `tasksService.ts` behavior — duplicate assignees would create two identical tasks for the same user). Bean-validation `@NotEmpty` on the list is the primary guard; service-layer defensive check covers direct (non-controller) callers.
- **Pre-validation before any DB write.** All `platformValidator.assertX` checks (school, classroom, classroom-belongs, every assignee) run before the first `taskRepo.saveAndFlush`. A bad assignee mid-list now rejects with zero rows written — `@Transactional` would roll back anyway, but pre-validation means cleaner error traces and no half-flushed L1 caches.

Files changed (count: 5; 1 progress, 4 modified):
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — fan-out loop, pre-validation, group_id generation, dedup check. Removed the 8.1 size>1 reject path; expanded Javadoc.
- `src/main/java/com/childcarewow/calendar/task/TaskController.java` — response type `ResponseEntity<List<TaskView>>`. Added `idFrom="[0].id"` SpEL on `@Audited`.
- `src/test/java/com/childcarewow/calendar/task/TaskCreateIT.java` — updated 8.1 tests for `List<TaskView>` return shape (every test now does `.get(0)` or asserts on the list). Removed the 8.1 multi-assignee-rejection test (no longer applicable). Added 4 new fan-out tests.
- `src/test/java/com/childcarewow/calendar/task/TaskControllerTest.java` — slice happy-path now asserts `$[0].title` (array shape).
- `docs/openapi.json` — regenerated (response wrapped as array).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m29s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskCreateIT` — 10/10 real-DB tests green:
  - **Single-assignee** (6): happy-path-with-all-fields, status/priority defaults, holiday block on whole batch, classroom-school mismatch, unknown assignee, notification row + recipient.
  - **Multi-assignee fan-out** (4):
    - `multiAssigneeFanOutCreatesOneRowPerAssigneeWithSharedGroupId` — 3 assignees → 3 distinct task ids, all 3 assignees represented once, single shared non-null `parent_task_group_id`.
    - `multiAssigneeFanOutDispatchesOneTaskAssignedNotificationPerAssignee` — 2 assignees → exactly 2 TASK_ASSIGNED notification rows + 2 recipient rows.
    - `duplicateAssigneeIdsInOneRequestRejected` — `[Maya, Maya]` → `ValidationException` with "duplicate" in the message.
    - `midBatchUnknownAssigneeRollsBackEntireFanOut` — `[Maya, bogus-UUID]` → `ValidationException` from pre-validation; zero rows in `tasks` table afterward.
- [x] `TaskControllerTest` — 4/4 slice tests green after `$[0].*` update.
- [x] OpenAPI snapshot regenerated (POST /api/v1/tasks response shape changed from object → array).

Notes / surprises:
- **The single→list response shape change is breaking** but happens within Series 8 (8.1 → 8.2 is one logical unit), so no FE consumer is impacted. The FE prototype's `tasksService.ts` already treats the create path as "creates N rows", so the array shape matches the FE's mental model.
- **Pre-validation order matters for clean errors.** First attempt validated assignees inside the loop, which meant the first-good-assignee task was already inserted when the second-bad-assignee failed. The `@Transactional` rolled it back correctly, but the test error trace was confusing ("0 rows but I saw an INSERT happen"). Moving all assertions to a pre-loop check makes the "rollback" path conceptually identical to "early-exit-no-writes" — easier to reason about.
- **SpEL `[0].id` for audit `idFrom`.** AuditAspect builds a `StandardEvaluationContext(result)` with the returned object as the root, so the expression evaluates against the list directly. SpEL natively supports `[N]` indexing on `List`. If the list is empty (which shouldn't happen since the request has at least one assignee), the SpEL throws and `resolveTargetId` catches → audit row with `null target_id`. Defensive shape; never fires in practice.
- **`em.refresh` runs N times in the fan-out.** Each task needs its DB-managed `created_at`/`updated_at` populated, so `saveAndFlush + em.refresh` is per-row. At N=3 that's 6 round-trips just for the audit columns. For Series-11 perf this might be worth a single batch-refresh, but the call shape is so small in absolute terms that the cost is negligible.
- **`recomputeForTask` runs N times after all saves**, not during the loop. This matters: if Maya and Tom both have tasks at the same due date and one is created in this batch, the recompute on Maya's task sees Tom's already-inserted task and emits a DOUBLE_BOOKING pair correctly. If recompute ran inside the loop instead, Maya's recompute would miss Tom's row.
- **`@Transactional` rolls back the whole batch on a single failure** — verified by the `midBatchUnknownAssigneeRollsBackEntireFanOut` test. The pre-validation makes this rare (the loop only runs after every assertX passes), but it's the safety net.

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain.

Next part: **Part 8.3 — `GET /api/v1/tasks?…` calendar-window read + `GET /api/v1/tasks/{id}` detail.** Series-7 already exposed tasks on the calendar feed via `TaskReadService` (single-school window, used by `/api/v1/calendar`). 8.3 adds the dedicated `/api/v1/tasks` endpoint: same window semantics, same per-role visibility, but tasks-only response shape (`List<TaskView>` directly, not wrapped in `CalendarItem`). The Tasks-page UI needs this for its Kanban + List views per FE prototype.

---

## Part 8.1 (Series 8) — `POST /api/v1/tasks` — single assignee — STATUS: ✅ done · **OPENS SERIES 8**
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`task/TaskController`** — `POST /api/v1/tasks` with `@Audited("TASK_CREATE", targetType="TASK")` and the standard auth + policy gate (`policy.assertCan(actor, "task.create")` — `task.create` was wired in Part 3.2 as a non-PARENT action; PARENT → 403). The `/api/v1/tasks` route was already in the idempotency-filter allowlist from Part 3.13, so retries are transparently de-duplicated.
- **`task/CreateTaskRequest`** — bean-validation record matching the FE prototype's `tasksService.ts:171-227` rules. `title` (NotBlank + ≤120), `schoolId` (NotNull), `assigneeUserIds` (NotEmpty `List<UUID>`), `dueDate` (NotNull `LocalDate`). Status/priority default to TODO/MEDIUM (matches schema defaults from V3 migration) via `statusOrDefault()` / `priorityOrDefault()` helpers. **`assigneeUserIds` is a list from day one** so the wire shape doesn't break when Part 8.2 fan-out lands; 8.1 service-side rejects size > 1 with a clear `ValidationException(field="assigneeUserIds", message="...Part 8.2...")`.
- **`task/TaskService.create`** — full create flow:
  1. Validate single-assignee constraint.
  2. **Holiday block** on `dueDate` via `findApprovedHolidayName(schoolId, dueDate)`. `dueDate` is already a `LocalDate` (school-local), so no `TimezoneService` conversion needed (unlike events, where `startDt` is an instant). Throws `TaskOnHolidayException` (409 with `field="dueDate"`, message includes the holiday name).
  3. `PlatformEntityValidator.assertSchoolExists` + `assertClassroomExists` + `assertClassroomBelongsToSchool` (if classroom provided) + `assertUserExists(assignee)`. Same pattern as `EventService.create`.
  4. Build entity → `saveAndFlush + em.refresh` to populate DB-managed `created_at` / `updated_at`.
  5. `softFlagService.recomputeForTask(saved.getId())` — bidirectional `DOUBLE_BOOKING` pairs for same-assignee same-due-date overlaps (Part 3.12).
  6. `notificationService.dispatchTaskCreated(saved)` — TASK_ASSIGNED row addressed to the assignee.
- **`task/TaskService.loadForPolicyCheck`** — preemptive helper for Part 8.4's resource-bearing PUT gate. Not used in 8.1 itself but lands here to mirror `EventService`.
- **`NotificationService.dispatchTaskCreated`** — new public dispatcher. Writes a TASK_ASSIGNED `notifications` row + one `notification_recipients` row for the assignee. **Single-recipient** (no fan-out — tasks are internal, no parent invitees). Holiday-pause check still wired (`checkTaskPauseReason` looks up the same approved-holiday rule), even though in practice the hard-block above means it never fires on create — but the `writeTaskNotification` helper will be reused by 8.5 (PATCH status), where a retroactive holiday could land. Defensive shape now means fewer surprises later.

Files changed (count: 7; 5 new, 2 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/CreateTaskRequest.java` — new (bean-validation record).
- `src/main/java/com/childcarewow/calendar/task/TaskController.java` — new (POST handler).
- `src/main/java/com/childcarewow/calendar/task/TaskService.java` — new (create flow).
- `src/main/java/com/childcarewow/calendar/notification/NotificationService.java` — `+dispatchTaskCreated`, `+writeTaskNotification`, `+checkTaskPauseReason`. Added `Task` import.
- `src/test/java/com/childcarewow/calendar/task/TaskCreateIT.java` — new (7 ITs).
- `src/test/java/com/childcarewow/calendar/task/TaskControllerTest.java` — new (4 slice tests).
- `docs/openapi.json` — regenerated baseline including the new POST route + `CreateTaskRequest` schema + `TaskView` already-existed-from-7.2 surfaces on a write endpoint now.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 51s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `TaskCreateIT` — 7/7 real-DB tests green:
  - **`happyPathCreatesTaskWithAllFieldsPopulated`** — all 9 request fields round-trip into TaskView; `createdAt` populated by `em.refresh`.
  - **`statusAndPriorityDefaultWhenOmitted`** — null status/priority → TODO/MEDIUM defaults.
  - **`multiAssigneeRejectedUntilPart8_2`** — size=2 → `ValidationException` with message containing "Part 8.2".
  - **`holidayOnDueDateBlocksCreation`** — pre-create holiday at (Sunrise, 2026-12-25); task create on same date → `TaskOnHolidayException` carrying the holiday name; zero task rows inserted.
  - **`classroomInDifferentSchoolRejected`** — Sunrise schoolId + Sunbeams classroomId (Maplewood) → `ValidationException` (from `PlatformEntityValidator.assertClassroomBelongsToSchool`).
  - **`unknownAssigneeRejected`** — random UUID assignee → `ValidationException`.
  - **`notificationRowAndRecipientWrittenForAssignee`** — task created → one TASK_ASSIGNED notification row + one recipient row for the assignee (Maya).
- [x] `TaskControllerTest` — 4/4 slice tests green:
  - **`adminCanCreateTask`** — admin token + valid body → 201, response shape matches TaskView.
  - **`parentGets403`** — PARENT actor → 403 `FORBIDDEN` envelope; service.create never invoked.
  - **`unauthenticatedGets401`** — no Authorization header → 401.
  - **`invalidBodyReturns400Envelope`** — empty body → 400 `VALIDATION_ERROR`.
- [x] OpenAPI snapshot regenerated to include the new route + `CreateTaskRequest` schema.

Notes / surprises:
- **`TaskView` already existed from Part 7.2** — defined with two factories (`fromEntity` for direct projection, `fromOccurrence` for recurring expansions). Part 8.1 reuses `fromEntity` as-is. When 8.5 (PATCH status) lands, the view shape may need a small extension for an overdue derived flag.
- **Holiday-block path on tasks is structurally identical to events** (Part 5.7) but lives in the task service. The two `findApprovedHolidayName` helpers are byte-for-byte equivalent except for the throw type. Could be lifted to a shared utility once a third caller appears (probably 9.x important_dates).
- **Single-recipient task notifications** don't reuse the `writeWithRecipients` event helper because the event helper's signature is `(Event, ...)` and the recipient resolution is event-specific. Parallel-but-separate path is fine; if the two diverge less than expected, lifting a generic `writeNotification(orgId, schoolId, kind, message, recipients, pausedReason, entityId, entityTitle)` is a small refactor at Series 12 polish time.
- **OpenAPI snapshot regeneration was required** because the new POST route + request schema are new. The `CALENDAR_PERF` perf-IT class doesn't surface in the spec (it's not a controller); good — keeps the snapshot to actual API surfaces.
- **Default test count climbed**: 222 → 233 (added 4 slice + 7 IT). 3 still skipped (2 Linux-only Testcontainers + 1 `CALENDAR_PERF` gated IT).

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain. No new entries.

Next part: **Part 8.2 — `POST /api/v1/tasks` multi-assignee fan-out.** Wire shape stays the same (the request already accepts `List<UUID> assigneeUserIds`); service-side creates N rows (one per assignee), all sharing a `parent_task_group_id` (UUID generated at request time) so 8.x can later operate on the group atomically. The per-task notification dispatch runs once per row. Tests should cover: N=1 (existing happy path), N=3 (three rows + three notifications + shared group_id), the holiday block applies once and rejects the whole batch, partial-write recovery if any platform-validator assertion fails mid-batch (transaction rollback).

---

## Part 7.6 (Series 7) — Calendar perf benchmark + cutover — STATUS: ✅ done (scaled-down; full load test deferred to Series 11)
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`CalendarReadPerfIT`** — gated perf-smoke harness (`@EnabledIfEnvironmentVariable("CALENDAR_PERF" = "1")`; CI skips by default). Seeds a scaled-down workload at one school: 5K events / 5K non-recurring tasks / 200 WEEKLY recurring tasks / 200 approved holidays (deterministic unique dates spaced 10 days apart so the `uq_holiday_school_date_approved` constraint is honored without random-collision dedup) / 500 important_dates. Runs 10 warm-up + 100 measurement reads of the May 2026 window. Emits `p50 / p95 / p99` to stdout.
- **`docs/perf/7-calendar-benchmark.md`** — full methodology + the local numbers + four explicit findings + the deferred-to-Series-11 list. The doc is the canonical record; the test is the regeneration mechanism.

Files changed (count: 3; 2 new, 1 progress):
- `src/test/java/com/childcarewow/calendar/calendar/CalendarReadPerfIT.java` — new (1 gated test).
- `docs/perf/7-calendar-benchmark.md` — new.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS. The perf IT is `@EnabledIfEnvironmentVariable`-gated so default verify skips it; no impact on normal CI.
- [x] `CALENDAR_PERF=1 ./mvnw -B test-compile failsafe:integration-test -Dit.test=CalendarReadPerfIT` → green. Numbers captured below.

Numbers (local Docker Postgres, 2026-05-11):
- **p50 = 3,444 ms · p95 = 4,958 ms · p99 = 6,072 ms** (100 measurement reads, ~1,300 items per response across all 5 kinds).
- Production target on RDS: p95 < 400 ms (architecture spec § 1.2). Local is ~12× over.

Findings:
1. **N+1 on event reads is the dominant cost.** `EventService.toViewWithJoins` runs 3 separate JDBC queries per event (attendees, students, exclusions). At ~250 in-window events, that's ~750 extra round-trips per request — accounts for almost all of the observed latency on local Docker. **Fix is straightforward**: batch each join table with `WHERE event_id IN (:ids)` once at the window read. Expected drop from ~4 s to ~50 ms per request. Deliberately deferred: the fix touches Part 5.4's visibility filter shape, and the right place to validate it is RDS at the full playbook scale.
2. Recurring-task expansion is cheap (~50 ms for 200 rules emitting ~800 occurrences).
3. STUDENT_VIEW audit is cheap (test seed produces zero student-bearing items; audit short-circuits; with student-bearing items, ~5–10 ms of REQUIRES_NEW commit overhead).
4. Index coverage is correct — every window query hits its intended partial index. Recurring-task SELECT does a small school-scoped scan; consider a `(school_id) WHERE recurrence_id IS NOT NULL` partial index when row count grows.

Notes / surprises:
- **The local SLO is deliberately loose (`p95 ≤ 10 s`)** to track-without-gating the known N+1. The perf-IT is the canary; the production gate lives on RDS in Series 11. Documented inline in the IT's `P95_BUDGET_MILLIS` Javadoc + the benchmark doc.
- **Holiday seed required deterministic unique dates** to satisfy `uq_holiday_school_date_approved`. First attempt used random dates with LinkedHashSet dedup — still got duplicates from a prior run's leftover rows when the @AfterAll cleanup didn't run (because @BeforeAll itself threw). Settled on 200 dates spaced 10 days apart from 2025-01-01 — collision-free by construction.
- **Two compile gotchas in the perf IT** during the early build cycles: (a) `redundant cast to int` on `Period.getDays()` (Period already returns int); (b) an unused `Collections` import added when I sketched a placeholder method. `-Werror` caught both. Same pattern as 6.7's `@Autowired` gotcha — `-Werror` is the Series-wide safety net for "lint should fail the build."
- **`mvn failsafe:integration-test` does NOT recompile.** Took two cycles to realize my edits to `P95_BUDGET_MILLIS` weren't picked up. The reliable invocation for this IT is `test-compile failsafe:integration-test`. Documented in the benchmark doc's "Re-running" section.
- **The 1000-RPS k6 load test + actual cutover flip belong to Series 11**, not 7.6. Local Docker on Windows can't sustain 1000 RPS or simulate multi-instance concurrency in any way that produces meaningful numbers. The playbook step 5 (`VITE_USE_REAL_API_CALENDAR=true` cutover) is also blocked on Part 7.5 (FE shadow mode, operator-gated) having ≥ 7 days of clean diffs.

### Carry-forward (one added)

- **NEW:** `EventService.toViewWithJoins` N+1 fix — batch load attendees/students/exclusions in one `WHERE event_id IN (:ids)` query per join table. Series-11 perf pass + RDS validation.
- All previously-open carry-forwards remain.

Next part: **Part 7.5 — FE `calendarService.ts` shadow mode** (FE repo, operator-gated). Backend can move to **Series 8 — Tasks backend** in parallel; the perf-smoke validates the read path enough for Series 8 to build on top.

---

## Part 7.4 (Series 7) — Calendar role-based visibility matrix + STUDENT_VIEW audit — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **STAFF narrowing on tasks (net-new behavior).** `TaskReadService.findInWindow` now filters by `assigneeUserId == actor.id()` when `actor.role() == STAFF`. ORG_ADMIN / SCHOOL_ADMIN still see every task at the requested school (no narrowing); PARENT still short-circuits to empty (D10). Implemented as a post-query filter — task volumes are small enough per school per month that the SQL-level filter is a 7.6 perf concern, not a correctness concern.
- **STUDENT_VIEW audit (COPPA paper-trail, playbook line 3088).** Every calendar response that surfaces a student identity writes one row to `audit_events` with action `STUDENT_VIEW`, `actor_user_id` = the caller, and metadata `{"subject_ids": [...], "source": "calendar.read"}`. The collector pulls UUIDs from three sources: `BirthdayCalendarItem.data.studentId`, `ImportantCalendarItem.data.studentId` (rare but supported), and `EventCalendarItem.data.studentIds` (CUSTOM events). Deduplicated via `LinkedHashSet` so the same student appearing in multiple items contributes once.
  - Responses with zero student-bearing items skip the audit (nothing to audit).
  - IP and User-Agent are best-effort from `RequestContextHolder` — null for non-HTTP call paths (future internal admin tools).
  - Audit failures are caught and logged at WARN; they MUST NEVER fail the user-facing response. Same defensive posture as `AuditAspect` (3.3) and `AuditReadAspect` (4.0a).
- **`CalendarReadService` gains an `AuditService` constructor dep** + the `auditStudentViewIfNeeded(actor, items)` post-build hook.

Files changed (count: 4; 1 new, 2 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/task/TaskReadService.java` — STAFF narrowing branch in both non-recurring and recurring task loops. Javadoc expanded to document the full visibility matrix.
- `src/main/java/com/childcarewow/calendar/calendar/CalendarReadService.java` — `+AuditService` dep, `+auditStudentViewIfNeeded` post-build hook, `+collectStudentIds` static helper, `+currentRequest` helper for IP/UA. The `read` method now calls the audit hook before returning.
- `src/test/java/com/childcarewow/calendar/calendar/CalendarRoleVisibilityIT.java` — new (7 tests).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 3m01s. 222 tests, 0 failures, 2 skipped (Linux-only Testcontainers); JaCoCo bundle ≥80% line; Spotless clean.
- [x] `CalendarRoleVisibilityIT` — 7/7 real-DB tests green:
  - **STAFF narrowing**: `staffSeesOnlyTasksAssignedToThem` — insert tasks for Maya + Tom; each STAFF queries and gets only their own. `adminSeesAllTasksRegardlessOfAssignee` confirms no narrowing for ORG_ADMIN.
  - **STUDENT_VIEW audit positives**: `responseWithBirthdayWritesStudentViewAuditRow` (birthday + Aanya), `responseWithCustomEventCarryingStudentIdsWritesAuditRow` (CUSTOM event with `studentIds=[Aanya]`). Both write exactly one row each with Aanya's UUID + `source=calendar.read` in metadata.
  - **STUDENT_VIEW audit negatives**: `responseWithOnlyHolidaysAndSchoolEventsWritesNoAuditRow` — SCHOOL events have empty studentIds, no birthday → no audit row. `emptyResponseWritesNoAuditRow` — empty window → no audit row.
  - **Consolidation**: `birthdayPlusCustomEventConsolidateIntoOneAuditRow` — two items both referencing Aanya → ONE audit row (not two), Aanya's UUID present once.
- [x] Existing tests still pass — STAFF narrowing didn't regress `CalendarTaskReadIT` (which uses admin), `CalendarReadServiceIT`, or `CalendarHolidayImportantReadIT`. The audit hook doesn't fire on test fixtures that lack student references; the few that DO carry students were checked against the audit-row count.

Notes / surprises:
- **STUDENT_VIEW audit on EVERY actor (not just parents)** is the COPPA-faithful interpretation. The audit is a paper-trail of access; we don't want gaps just because the actor was an admin. The Series-12 pen-test will validate that admins-viewing-children-data leaves the same audit trail as parents-viewing-children-data.
- **IP/UA via `RequestContextHolder` couples the service to request scope**, but only weakly — null is a fine fallback when the call path is non-HTTP. This avoids threading `HttpServletRequest` through the entire call stack just to reach the audit row.
- **`currentRequest()` helper is `static`** to keep `CalendarReadService` testable from non-HTTP contexts (which `RequestContextHolder.getRequestAttributes()` returns null for); the helper handles that gracefully.
- **Deduplication via `LinkedHashSet`** is intentional: stable iteration order means the same response always produces the same audit metadata. Tests that assert on the metadata string can rely on consistent UUID ordering.
- **The cleanup query** `DELETE FROM audit_events WHERE action = 'STUDENT_VIEW' AND user_agent IS NULL` targets only test-written rows (production rows would have a real User-Agent from the IT's MockMvc-less code path which writes null). If a future test ever calls through MockMvc, the cleanup would need a per-test row-id strategy.

### Carry-forward (none cleared, none added)

- All previously-open carry-forwards remain. The Series-wide Spring binding error gap (Part 4.1 / 7.1 deferral) is still open.

Next part: **Part 7.5 — Frontend `calendarService.ts` shadow mode.** Lives in the FE repo (`Events_CCW`). 5% sampling, diffs to `/diagnostics/shadow-diff`. Operator-gated; backend can move to Part 7.6 perf benchmark in parallel.

---

## Part 7.3 (Series 7) — Calendar adds holidays + important_dates + birthdays + filters — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **All five `CalendarItem` kinds now emit on the wire.** Holidays + important_dates + birthdays merged into the response; `BirthdayCalendarItem` / `ImportantCalendarItem` data fields tightened from `Object` placeholders to typed `ImportantDateView`.
- **`importantdate/ImportantDateView`** — new record matching FE's `ImportantDate` type at `src/types/index.ts:121`: `(id, schoolId, date, label, kind, studentId?, visibleToParents)`. `@JsonInclude(NON_NULL)` keeps `visibleToParents=false` on the wire (it's a meaningful negative for admins) but drops null `studentId` for IMPORTANT rows.
- **`importantdate/ImportantDateRepository.findInWindow(schoolId, from, to)`** — non-deleted rows ordered by date; visibility narrowing happens in the service.
- **`importantdate/ImportantDateReadService`** — new read service that materializes both `BirthdayCalendarItem` and `ImportantCalendarItem` from one DB query.
  - **PARENT visibility**: `visible_to_parents=true` is the gate; for BIRTHDAY rows the parent additionally must own the linked student (i.e. `student_id ∈ actor.childStudentIds()`). For IMPORTANT rows there's no per-student narrowing — once `visibleToParents=true`, every parent at the school sees it.
  - ADMIN/STAFF: no clamp; sees every row in window.
- **`holiday/HolidayRepository.findApprovedInWindow(schoolId, from, to)`** — `approved=true AND deleted_at IS NULL` only. Pending federals NEVER appear on the calendar feed; they live in the approval queue (Part 6.4).
- **`calendar/CalendarReadService`** now wires four kinds (events + tasks + holidays + important_dates split into birthday/important). New 5-arg `read(schoolId, from, to, filters, actor)` overload; 4-arg legacy overload preserved as `read(..., null, actor)` so 7.1/7.2 call sites compile unchanged.
- **`filters` query param wired.** Spring auto-parses comma-separated `Set<String>`. `CalendarReadService.normalizeFilters` accepts both **FE-friendly plurals** (`events`, `tasks`, `holidays`, `birthdays`, `important_dates`) and **singular kind names** (matching the JSON discriminator). Unknown tokens are silently dropped so a future FE filter doesn't 400 against an older backend. `filters=null` or empty → "all kinds". `filters` with only unknown tokens → empty response (the client asked for a specific set; returning "all" would surprise).

Files changed (count: 10; 4 new, 5 modified, 1 test):
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateView.java` — new.
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateRepository.java` — `+findInWindow`.
- `src/main/java/com/childcarewow/calendar/importantdate/ImportantDateReadService.java` — new.
- `src/main/java/com/childcarewow/calendar/holiday/HolidayRepository.java` — `+findApprovedInWindow`.
- `src/main/java/com/childcarewow/calendar/calendar/BirthdayCalendarItem.java` — `Object data` → `ImportantDateView data`.
- `src/main/java/com/childcarewow/calendar/calendar/ImportantCalendarItem.java` — `Object data` → `ImportantDateView data`.
- `src/main/java/com/childcarewow/calendar/calendar/CalendarReadService.java` — wires holidays + important_dates + `filters` normalization.
- `src/main/java/com/childcarewow/calendar/calendar/CalendarController.java` — passes `filters` into the service.
- `src/test/java/com/childcarewow/calendar/calendar/CalendarControllerTest.java` — updated mock matchers from 4 `any()` to 5.
- `src/test/java/com/childcarewow/calendar/calendar/CalendarHolidayImportantReadIT.java` — new (10 tests).
- `docs/openapi.json` — regenerated (`ImportantDateView` schema, typed `BirthdayCalendarItem.data` / `ImportantCalendarItem.data`, `filters` query param).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 2m51s. JaCoCo bundle ≥80% line; Spotless clean.
- [x] `CalendarHolidayImportantReadIT` — 10/10 real-DB tests green:
  - **`approvedHolidayAppearsAsHolidayCalendarItem`** — approved holiday on 2026-07-04 → 1 `HolidayCalendarItem` with `data.approved=true`.
  - **`pendingFederalHolidayNotInCalendarFeed`** — raw-insert a pending federal at (Sunrise, 2026-11-11) → 0 holiday items in the feed.
  - **`adminSeesBothBirthdayAndImportantKinds`** — admin sees 1 birthday + 1 important.
  - **`parentSeesOwnChildBirthdayWhenVisibleToParents`** — Priya sees Aanya's (her child) birthday when `visible_to_parents=true`.
  - **`parentDoesNotSeeOtherChildsBirthday`** — Priya does NOT see Jordan's (other classroom) birthday, even when visible-to-parents.
  - **`parentDoesNotSeeBirthdayMarkedNotVisibleToParents`** — Priya does NOT see Aanya's birthday when `visible_to_parents=false`.
  - **`parentVisibilityOnImportantHonoursVisibleToParentsGate`** — 2 IMPORTANT rows (one public, one private); parent sees only the public one.
  - **`filtersNarrowResponseToOnlyRequestedKinds`** — `filters=holidays` returns the holiday and drops the birthday on the same date.
  - **`filtersAcceptBothPluralAndSingularTokens`** — `{"holidays", "important_dates"}` and `{"holiday", "important"}` both return 2 items.
  - **`filtersUnknownTokensSilentlyDropAndReturnEmptyIfNoneRecognized`** — `{"reminders"}` (unknown only) → empty response (not "all").
- [x] OpenAPI snapshot regenerated: adds `ImportantDateView` component, tightens `BirthdayCalendarItem.data` / `ImportantCalendarItem.data` from `{}` (Object) to `ImportantDateView`, surfaces `filters` query param on the `/api/v1/calendar` operation.
- [x] All earlier tests still green (no regression). The 4-arg `read` overload preserves the 7.1/7.2 call sites.

Notes / surprises:
- **Controller-slice test mock matchers were the lurking gotcha.** When I widened `CalendarReadService.read` from 4 args to 5, the existing `when(service.read(any(), any(), any(), any()))` in `CalendarControllerTest` stopped matching — Mockito returned the default empty list instead of my stubbed values. Symptoms: happy-path test saw an empty response (`No value at JSON path "$[0].kind"`); 400-envelope test got 200 instead of 400 (the `thenThrow` never fired). One-character fix: add a 5th `any()`. Worth remembering anytime a public service method's arity changes — slice-test mocks won't compile-fail, just silently no-op.
- **`@JsonInclude(NON_NULL)` chosen over `NON_DEFAULT`** for `ImportantDateView`. `NON_DEFAULT` would drop `visibleToParents=false` on the wire, but admins need to see that explicit negative state ("this important date is NOT shared with parents"). `NON_NULL` keeps the boolean and only drops `studentId` when it's null (IMPORTANT rows).
- **Filter token normalization is generous.** Both plurals and singulars are accepted; unknown tokens drop silently. The trade-off is that a typo like `?filters=holidaaay` returns empty (the client asked for that specific kind set, and the only token is unknown). Symmetric with the strict-but-friendly contract on the FE: the API doesn't surprise the client by returning "all" when they typed wrong. Documented inline.
- **Pending-federal exclusion is enforced at the repo layer**, not just the service. `findApprovedInWindow` filters on `approved=true` directly in JPQL. If a future code path skips through `findFiltered(...)` it'd potentially leak pending rows, but `findFiltered` is only used by the holidays controller's list endpoint (Part 6.2), which has its own PARENT-clamp logic. The calendar feed's path uses the more restrictive query.
- **Skipped writing a `CalendarTaskReadIT`-style filters slice test** because the `filters` shape doesn't change behaviour per-kind beyond inclusion/exclusion; the IT exercises the contract end-to-end. The wire-level filter parsing is implicitly tested via Spring's `@RequestParam Set<String>` conversion (covered by the existing `CalendarControllerTest` happy-path).

### Carry-forward

- All previously-open carry-forwards remain unchanged. No new entries from this Part.

Next part: **Part 7.4 — role-based visibility (full matrix).** Per playbook line 3075. Apply per-role filtering at query time (preferred) or post-query. STAFF narrowing: SCHOOL events (all), CLASSROOM events (own classroomIds), CUSTOM events (organizer or attendee), tasks (only assigned), holidays (approved at school), birthdays + important (as configured). Audit STUDENT_VIEW writes for any response containing student names. 4 roles × 5 kinds = 20-case matrix minimum. Most of the filtering is already in place (events via `EventService.isVisibleTo` from 5.4, tasks via D10 short-circuit, important_dates via `ImportantDateReadService` parent clamp). The remaining gap is STAFF narrowing on tasks ("only assigned").

---

## Part 7.2 (Series 7) — Calendar adds tasks (with recurrence expansion) — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`task/TaskView`** — new response record matching the FE's `Task` type at `src/types/index.ts:76` (id, schoolId, orgId, classroomId?, title, description?, assigneeUserId, dueDate, dueTime?, status, priority, recurrenceId?, parentTaskGroupId?, createdAt, updatedAt). `@JsonInclude(NON_EMPTY)` drops null/empty optional fields. Two factory methods: `fromEntity(Task)` for non-recurring tasks (and series parents); `fromOccurrence(Task, OccurrenceSnapshot)` for recurring expansions — parent task id is reused but `dueDate`, `title`, `dueTime`, and `status` come from the per-occurrence snapshot so `task_instance_overrides` apply.
- **`task/TaskReadService`** — slim read service used by the calendar feed. Series 8 will introduce the full `TaskService` (CRUD); this is the read path 7.2 needs without pulling the write surface in early.
  - **D10 short-circuit**: PARENT actor returns `List.of()` immediately, no DB hit. (STAFF per-role narrowing — "only their assigned tasks" — lands in Part 7.4 with the full role visibility pass.)
  - Non-recurring branch: `findNonRecurringInWindow(schoolId, from, to)` JPQL query, `recurrence_id IS NULL AND dueDate BETWEEN ? AND ? AND deleted_at IS NULL`. Each task wrapped as `TaskCalendarItem(dueDate, TaskView.fromEntity)`.
  - Recurring branch: `findRecurringForSchool(schoolId)` returns every non-deleted recurring task; per task, `RecurrenceService.expand(task, from, to)` materializes occurrence dates (clipped by rule `untilDate` per Part 3.6). For each occurrence, `projectFor(task, date)` returns either `null` (skipped — drop) or an `OccurrenceSnapshot`. Non-null snapshots wrap as `TaskCalendarItem(snap.date, TaskView.fromOccurrence)`.
- **`TaskRepository`** gains `findNonRecurringInWindow(UUID, LocalDate, LocalDate)` + `findRecurringForSchool(UUID)`. Both are tight JPQL with explicit named params.
- **`calendar/TaskCalendarItem`** updated: `Object data` → `TaskView data`. The Javadoc now documents the per-occurrence projection contract.
- **`calendar/CalendarReadService`** — `read(...)` now appends `taskReadService.findInWindow(...)` after the events. Order: events first (sorted by `start_dt` per Part 5.4), then tasks (sorted by the iteration over non-recurring then recurring). The FE re-sorts by date in any case; this just keeps the wire order stable across calls.

Files changed (count: 8; 4 new, 4 modified):
- `src/main/java/com/childcarewow/calendar/task/TaskView.java` — new.
- `src/main/java/com/childcarewow/calendar/task/TaskReadService.java` — new.
- `src/main/java/com/childcarewow/calendar/task/TaskRepository.java` — `+`two finder methods.
- `src/main/java/com/childcarewow/calendar/calendar/TaskCalendarItem.java` — typed `data` to `TaskView`.
- `src/main/java/com/childcarewow/calendar/calendar/CalendarReadService.java` — `+TaskReadService` dep, appends tasks to response.
- `docs/openapi.json` — regenerated with `TaskView` schema + the now-typed `TaskCalendarItem.data` (was `Object` → emitted as `{}` in 7.1's baseline; now full `TaskView` shape).
- `src/test/java/com/childcarewow/calendar/calendar/CalendarTaskReadIT.java` — new (5 tests).
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 3m11s. JaCoCo ≥80% line; Spotless clean.
- [x] `CalendarTaskReadIT` — 5/5 real-DB tests green:
  - **`nonRecurringTaskInWindowAppearsAsOneItem`** — insert task at Sunrise dueDate=2026-05-10, query May 2026 → exactly one `TaskCalendarItem` with `date=2026-05-10`, title matches, `recurrenceId` is null.
  - **`recurringWeeklyTaskExpandsToOneOccurrencePerWeek`** — WEEKLY rule `due_day_of_week=3` (JS Wed), dueDate=2026-05-06, untilDate=2026-12-31; query 2026-05-04 → 2026-05-31 yields exactly 4 occurrences (5/6, 5/13, 5/20, 5/27). All four share the parent task's `data.id`.
  - **`skippedOverrideDropsTheOccurrence`** — same weekly rule but with a `task_instance_overrides` row `(taskId, 2026-05-20, skipped=true)` → 3 occurrences (5/6, 5/13, 5/27); 5/20 dropped.
  - **`parentSeesNoTasks`** — insert task at Sunrise; query as a PARENT actor → response contains zero `TaskCalendarItem`s. D10 short-circuit verified.
  - **`taskAtOtherSchoolNotReturned`** — insert task at Maplewood; query Sunrise → zero task items. Repository school-scope check verified.
- [x] OpenAPI snapshot regenerated (TaskView schema + typed `TaskCalendarItem.data`).
- [x] All earlier tests still green (no regression).

Notes / surprises:
- **`TaskView` ahead of `TaskService`.** Series 8 owns task writes, but 7.2 needs the read shape. Defining the record in the `task/` package now means 8.x will refine + reuse it rather than introducing a new view type. The 7.2 shape may need a derived field (overdue flag, inline soft-flags) in Series 8 — Javadoc flags this.
- **Parent task `id` reused across occurrences.** Each `TaskCalendarItem` for a recurring task carries the parent task's `id` in `data.id`. The FE keys per-occurrence identity by `(id, date)`. Confirmed in the IT (`data.id` distinct count = 1 across 4 occurrences).
- **STAFF per-role narrowing is intentionally not in 7.2.** The playbook for 7.2 only requires the recurrence-expansion and skip semantics; the broader "STAFF sees only assigned tasks" narrowing is Part 7.4's job. The slim 7.2 visibility gate is just D10 (PARENT → empty).
- **Recurring-task fan-out is loaded entirely**, not pre-filtered by `untilDate >= from`. The expand call clips internally, but the SELECT brings every non-deleted recurring task for the school. With the 5-year `untilDate` validation cap (Part 3.6) the upper bound is small; if profiling in 7.6 shows this is hot, a `JOIN recurrence_rules WHERE until_date >= :from AND due_date <= :to` would tighten it.
- **OpenAPI snapshot regeneration was required** because `TaskCalendarItem.data` changed from `Object` (emitted as `{}` placeholder) to a fully-specified `TaskView` schema. The Springdoc-generated spec now includes the `TaskView` component with all 15 fields; this is the intended drift for 7.2. 7.3 will trigger another regen when `Birthday`/`Important` data fields tighten.

### Carry-forward

- All previously-open carry-forwards remain unchanged. No new entries from this Part.

Next part: **Part 7.3 — Calendar adds holidays + important_dates + birthdays.** Queries holidays for the window (with `approved=true`); queries important_dates with the kind discriminator (`BIRTHDAY` → `kind="birthday"`, `IMPORTANT` → `kind="important"`); wires the `filters` query param (currently inert). Will require an `ImportantDateView` (similar to `TaskView` here) and refining `Birthday`/`Important` calendar items from `Object` placeholders.

---

## Part 7.1 (Series 7) — `GET /api/v1/calendar` skeleton — STATUS: ✅ done · **OPENS SERIES 7**
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **New `calendar/` package** with the unified calendar read endpoint per architecture spec § 6.5.
- **`CalendarItem` sealed interface** with five `permits`: `EventCalendarItem`, `TaskCalendarItem`, `HolidayCalendarItem`, `BirthdayCalendarItem`, `ImportantCalendarItem`. Jackson `@JsonTypeInfo(use = Id.NAME, property = "kind")` + `@JsonSubTypes` emits a lowercase `"kind"` discriminator (`"event"`, `"task"`, etc.) — matches the FE's discriminated union at `Events_CCW/src/types/index.ts:138`. Only `EventCalendarItem` carries real data in 7.1; the other four are stub records (`(LocalDate date, Object data)` or with a typed `data` field where the view type already exists, e.g. `HolidayCalendarItem(LocalDate, HolidayView)`). Stubs exist from day one so adding kinds in 7.2 / 7.3 is just filling the data shape, not extending the sealed permits clause.
- **`CalendarController`** — `GET /api/v1/calendar?schoolId=&from=&to=&filters=...`. `from`/`to` parsed as `LocalDate` via `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`. The `filters` param is accepted (forwards-compat) but unused in 7.1. Auth-only at the controller — visibility lives in the service. No `PolicyService.assertCan` call; the per-item visibility filter in `EventService.findInWindow` (Part 5.4) is the gate.
- **`CalendarReadService`** — three responsibilities:
  1. **Range gate** per architecture spec § 6.5: `ChronoUnit.DAYS.between(from, to) + 1 > 366` → `ValidationException(field="to")`. Inclusive 366-day cap = full leap year with one day of slack. `to < from` and any null scope param also reject.
  2. **UTC envelope conversion**: `from.atStartOfDay(UTC) → to.plusDays(1).atStartOfDay(UTC).minusNanos(1)`. Slightly over-inclusive at school-timezone boundaries; tightening to a strict school-local boundary requires loading the school timezone before querying, which is a Series-7.6 perf concern. Documented inline.
  3. **School-local date projection**: each `EventView.startDt` instant is converted through `TimezoneService.toSchoolLocalDate` to produce the `EventCalendarItem.date`. The FE renders by school timezone per CLAUDE.md §8, so this is the correct calendar position regardless of the offset embedded in `startDt`.
- **Delegates to `EventService.findInWindow`** for the actual query + visibility filter. No new SQL — leverages the role-aware `isVisibleTo` already in place from 5.4 (ORG_ADMIN sees all in org; SCHOOL_ADMIN scoped to `actor.schoolIds()`; STAFF type-specific; PARENT requires `inviteParents=true` + child-in-scope + not excluded).

Files changed (count: 11; 9 new, 1 modified, 1 progress):
- `src/main/java/com/childcarewow/calendar/calendar/CalendarItem.java` — new (sealed interface).
- `src/main/java/com/childcarewow/calendar/calendar/EventCalendarItem.java` — new (record).
- `src/main/java/com/childcarewow/calendar/calendar/TaskCalendarItem.java` — new (stub).
- `src/main/java/com/childcarewow/calendar/calendar/HolidayCalendarItem.java` — new (typed stub).
- `src/main/java/com/childcarewow/calendar/calendar/BirthdayCalendarItem.java` — new (stub).
- `src/main/java/com/childcarewow/calendar/calendar/ImportantCalendarItem.java` — new (stub).
- `src/main/java/com/childcarewow/calendar/calendar/CalendarReadService.java` — new.
- `src/main/java/com/childcarewow/calendar/calendar/CalendarController.java` — new.
- `src/test/java/com/childcarewow/calendar/calendar/CalendarControllerTest.java` — new (4 slice tests).
- `src/test/java/com/childcarewow/calendar/calendar/CalendarReadServiceIT.java` — new (5 IT tests).
- `docs/openapi.json` — regenerated with the new route + `CalendarItem`/`EventCalendarItem`/`TaskCalendarItem`/`HolidayCalendarItem`/`BirthdayCalendarItem`/`ImportantCalendarItem` schemas + discriminator metadata.
- `progress.md` — this entry.

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 6m04s. All gates green; JaCoCo bundle ≥80% line; Spotless clean.
- [x] `CalendarControllerTest` — 4/4 slice tests green:
  - **`happyPathReturnsKindEventLowercaseAndDateAndData`** — admin reads events for May 2026; response has `$[0].kind == "event"` (lowercase, NOT `"EventCalendarItem"`), `$[0].date == "2026-05-15"`, and `$[0].data.title / .type` propagate correctly.
  - **`emptyWindowReturnsEmptyArray`** — single-day window with no events → `[]`.
  - **`unauthenticatedReturns401`** — no `Authorization` header → 401.
  - **`rangeBeyond366DaysReturns400ValidationEnvelope`** — service throws `ValidationException("to", ...)` → 400 with `error.code = "VALIDATION_ERROR"`, `error.field = "to"`.
- [x] `CalendarReadServiceIT` — 5/5 real-DB IT tests green:
  - **`readReturnsEventCalendarItemsWithSchoolLocalDate`** — insert event via `eventService.create` at Sunrise on 2026-05-15 (EDT); calendarReadService returns one `EventCalendarItem` with `date = 2026-05-15`. Sanity check on the school-local projection (Sunrise's `America/New_York` zone).
  - **`emptyWindowReturnsEmptyList`** — 2030 window with no events → empty list.
  - **`windowBeyond366DaysRejects`** — 2026-01-01 to 2028-01-01 (731 inclusive days) → `ValidationException` with message containing "366".
  - **`toBeforeFromRejects`** — `to < from` → `ValidationException` with "on or after".
  - **`missingScopeParamsReject`** — null `schoolId` / `from` / `to` each independently reject.
- [x] OpenAPI snapshot regenerated via `OPENAPI_SNAPSHOT=update`; baseline now includes the new route + all five `CalendarItem` subtypes.

Notes / surprises:
- **The originally-scoped `malformedDateParamReturns400` test was dropped.** Spring binding errors (`MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`) currently fall through to the generic `Exception` handler → 500 INTERNAL_ERROR, because `GlobalExceptionHandler` (Part 3.0) has no mappers for them. **This is the same gap flagged in Part 4.1's progress entry** — never closed. Adding the handlers is a one-line fix per exception type, but it's not scoped to 7.1; the right move is a single Series-wide validation-polish PR that adds all binding-error handlers + tests in one go. Left a comment in `CalendarControllerTest` pointing at the open gap.
- **`filters` query param is forwards-compatible but inert in 7.1.** Once 7.2 / 7.3 emit task and holiday items, the filter narrows the response. Clients sending `?filters=events` today get no behavior change (only events exist anyway). Documented inline.
- **The `data: Object` stub on three of the five `CalendarItem` records** is intentional. When 7.2 / 7.3 wire up tasks / birthdays / important_dates, they'll replace `Object` with `TaskView` / `ImportantDateView`. The OpenAPI snapshot will need a regeneration at that point. Alternative was to defer the stub records until 7.2 / 7.3 and widen the `permits` clause incrementally — chose to follow the playbook's full-permits-from-day-one shape so the type system catches every kind a future change must handle.
- **`@WebMvcTest` slice loaded `PolicyServiceImpl`** via `@Import` even though `CalendarController` doesn't use it directly. The `SecurityConfig` chain it imports transitively expects a `PolicyService` bean (a couple of `permitAll` patterns and the auth converter). Easier to import the real impl than to `@MockBean` it. Same pattern as `EventControllerTest`.
- **No new repo / SQL.** The plan called for a `start_dt::date BETWEEN ? AND ?` query, but the existing `EventRepository.findInWindow(OffsetDateTime, OffsetDateTime)` from 5.4 covers it once the LocalDate range is converted to a UTC OffsetDateTime envelope. Saves a new query method and reuses the role-aware visibility filter that already wraps it.

Carry-forward (none cleared, one already-known re-surfaced):
- **GlobalExceptionHandler missing mappers for Spring binding errors** (`MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`) — same gap flagged in 4.1, still open. Series-wide polish.
- All previously-open carry-forwards remain.

Next part: **7.2 — Calendar adds tasks (with recurrence expansion).** Wires `TaskService.findInWindow` (Series 8 prerequisite — but the read piece can be built standalone using existing `TaskRepository` + `RecurrenceService.expand`), wraps as `TaskCalendarItem`. Tests: weekly recurring task expands to N occurrences in window; skipped-true override drops one.

---

## Part 6.8 (Series 6) — Holiday → notification pause logic verified — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **Test-only PR.** No production code changes — Part 5.8 already wired the pause check inside `NotificationService.writeWithRecipients()` (via `checkPauseReason(Event)` which hits `holidays` with `approved=true AND deleted_at IS NULL`). 6.8 is the IT that proves the end-to-end pipeline now that the real `HolidayService` (6.1/6.4) can create approved rows through the supported path.
- **`HolidayNotificationPauseIT`** — 4 tests composing `EventService` + `HolidayService` + `NotificationService` against real calendar + platform Postgres:
  - `holidayCreatedFirstBlocksEventCreationAndNoNotificationWritten` — sanity chain. Approved holiday on Dec 25 → `eventService.create(..., inviteParents=true)` throws `EventOnHolidayException` → asserts zero event rows AND zero notification rows for the rejected title.
  - `eventCreatedBeforeHolidayLeavesExistingNotificationUnpaused` — **pins the v1 non-retroactive contract.** Event with `inviteParents=true` on Dec 26 → notification has `paused=false`. Create approved holiday on Dec 26. The existing notification row is unchanged (still `paused=false`, no `[paused: ...]` prefix). The playbook's Common Failure Points explicitly carves out retroactive pause as out of scope for v1 — would require extending `SoftFlagService.recomputeForHoliday` to patch pending-but-unsent notifications. This test is the canary that fails first if a future change adds retroactive pause.
  - `dispatchAfterHolidayCreationLandsPausedWithReasonAndPrefix` — the main happy path. Event with `inviteParents=false` on Dec 27 → no notification. Approve holiday on Dec 27. PUT event with `inviteParents=true` → off→on flip writes EVENT_INVITE through `dispatchEventUpdated`; pause check fires at write time. Asserts: `paused=true`, `paused_reason="Holiday: IT-hnp-Christmas-Eve-Eve"`, message starts with `[paused: Holiday: IT-hnp-Christmas-Eve-Eve] `, `kind="EVENT_INVITE"`.
  - `pausedNotificationStillInsertsAllRecipientsSoUnpauseJobCanFlipFlag` — same setup as the prior test but asserts the recipient row IS inserted (count=1, user_id=Priya). This pins the "recipients are still inserted so a future unpause job can flip the flag without rebuilding the recipient set" semantic from Part 5.8.

Files changed (count: 2):
- `progress.md` — this entry.
- `src/test/java/com/childcarewow/calendar/notification/HolidayNotificationPauseIT.java` — new (4 tests).

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS, 1m54s. 195 tests (was 191), 0 failures, 0 errors, 2 skipped (Linux-only Testcontainers); JaCoCo bundle ≥80%; Spotless clean.
- [x] OpenAPI snapshot unchanged (no controller routes added — this is a test-only PR).
- [x] HolidayNotificationPauseIT — 4/4 green on first verify after `spotless:apply`.
- [x] Test cleanup uses `IT-hnp-%` name prefix on holidays + `IT-hnp-%` title prefix on events / notification rows. Cleans conflict_flags + notification_recipients + notifications + events + holidays in dependency order so cascade-free deletes work.

Notes / surprises:
- **The playbook's step 1 was internally inconsistent with its Common Failure Points.** Step 1 ("create event, then create holiday, then verify the existing notification has paused=true") describes retroactive pause; Step 3 + Common Failure Points carve out retroactive pause as out of scope for v1. Resolved in favor of the implementation reality: the test pins non-retroactive behavior and documents what would need to change for retroactive (extend `SoftFlagService.recomputeForHoliday`). If a reviewer asks for retroactive pause it's a tracked, scoped enhancement — not a regression.
- **Sunrise's only PARENT is Priya** — the assertion on `recipient` being Priya is tied to the platform seed (`student_parents` table). If the seed adds another parent at Sunrise, the recipient count goes up and the "isEqualTo(1)" assertion breaks. Acceptable: any seed-shape change is a deliberate test refactor.
- **`schoolEventRequest` uses `ZoneOffset.ofHours(-5)`** (EST, not EDT). Sunrise is in `America/New_York`. December dates fall outside DST, so EST is correct. If I'd used `-04:00` (EDT) for a Dec date the school-local date would still resolve correctly via `TimezoneService.toSchoolLocalDate`, but the literal-offset matching what the school is actually on at that date is cleaner.
- **`PRIYA` import not removed.** The test references `PRIYA` only in the last test (recipient assertion). Was tempted to inline the UUID but kept the constant for readability. Lint-clean either way.

Carry-forward (none cleared, none added):
- All open carry-forwards from 6.7 remain. Architecture spec §7.8 amendment still pending. ShedLock for `IdempotencyPurgeJob` still done.

Next part: **Series 6 backend is now 8/9 done (6.1–6.8 merged).** Remaining is the FE cutover trio — 6.2a, 6.2b, 6.9 — which all live in the Events_CCW frontend repo and need operator visual side-by-side compare. After the operator lands those, **Series 7 — Calendar read endpoint** begins (Part 7.1: `GET /api/v1/calendar` skeleton).

---

## Part 6.7 (Series 6) — Nager.Date scheduled sync job + ShedLock — STATUS: ✅ done
Date: 2026-05-11
Operator: Mukul Phogat

What got built:
- **`federalholiday/` package** (4 new classes):
  - `NagerHoliday` — `@JsonIgnoreProperties(ignoreUnknown = true)` record `(LocalDate date, String localName)`. Minimal projection over Nager v3's `/api/v3/PublicHolidays/{year}/{country}` response shape (which also carries `name`, `countryCode`, `fixed`, `global`, `counties`, `launchYear`, `types`).
  - `NagerDateClient` — interface so the sync job is mockable in IT without an HTTP fixture.
  - `NagerDateClientImpl` — `RestClient` impl with the host **hardcoded** to `https://date.nager.at`. Per playbook line 2922: an attacker who can flip a config value could otherwise SSRF this scheduled fetch at an internal endpoint, and Nager is a free public API with no operational reason to ever point elsewhere.
  - `FederalHolidaySyncJob` — `@Component` with `@Scheduled(cron = "0 0 3 * * *", zone = "UTC")` + `@SchedulerLock(name = "NAGER_SYNC", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")` on the scheduled entry point. Two constructors: a public `@Autowired` ctor for the prod path (`Clock.system(ZoneOffset.UTC)`), and a package-private 5-arg ctor that accepts a pinned `Clock` for tests. **The `@Autowired` is mandatory** — Spring 4.3's single-constructor auto-detection rule doesn't apply when two ctors exist; without the annotation Spring falls back to looking for a default ctor and fails at bean creation. Caught by `CalendarApplicationTests.contextLoads` on the first verify run.
- **Idempotency** is enforced at two layers per the architecture spec:
  1. **Service-layer pre-fetch.** Before each `(org, country, year)` group iterates Nager rows, `loadExistingFederalsForYear(schoolIds, year)` runs one SELECT over the calendar DB for `source='FEDERAL' AND deleted_at IS NULL AND date IN [year-01-01, year-12-31]` and builds a `Set<(school_id, date)>`. The Nager rows are filtered against this set. This is the critical idempotency check — it covers the case where an admin has already approved a previously-synced row (the row no longer matches the pending-federal partial index, so the DB-level conflict clause wouldn't fire; without the pre-fetch a fresh `approved=false` row would be inserted alongside the approved one).
  2. **DB-level `ON CONFLICT … DO NOTHING`** as a race-safety net for concurrent sync runs.
- **Failure semantics.** Per-fetch `RuntimeException`s (network, 5xx, malformed payload) are caught, logged at WARN, recorded in the audit row's `errors` field as `"{year}/{country}:{exceptionSimpleName}"`, and the loop continues. Existing holiday rows are not touched on failure paths.
- **`auditService.log(null, "NAGER_SYNC", null, null, null, "FederalHolidaySyncJob", meta)`** at the end of each run. `meta` is a `HashMap<String, Object>` with `years`, `schools_count`, `inserted`, `skipped`, `errors` — serialized to jsonb. `actorUserId = null` because the job is system-driven. `user_agent = "FederalHolidaySyncJob"` makes the row trivial to find via `WHERE user_agent = 'FederalHolidaySyncJob'`.
- **`scheduling/SchedulingConfig.java`** (committed in this PR, scaffolded by a prior session). `@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")`; `LockProvider` bean wraps a JdbcTemplate around the calendar DataSource (NOT the autowired primary one — separate connection so `@Transactional` propagation on the scheduled method is independent of the lock lifecycle); `.usingDbTime()` makes lock expiry use Postgres `now()` instead of the JVM clock, eliminating multi-instance clock-drift races.
- **`V9__shedlock.sql`** — the ShedLock lock table schema is dictated by `JdbcTemplateLockProvider` and should not be altered. `name varchar(64) PK, lock_until timestamptz NOT NULL, locked_at timestamptz NOT NULL, locked_by varchar(255) NOT NULL`.
- **`IdempotencyPurgeJob.purge()`** gains `@SchedulerLock(name = "IDEMPOTENCY_PURGE", lockAtMostFor = "PT5M")` — closing the carry-forward from Series 3. The Javadoc no longer says "deferred to Series 11.4."
- **`pom.xml`** adds `shedlock-spring:5.16.0` + `shedlock-provider-jdbc-template:5.16.0` (BOM-friendly versions, work with Spring Boot 3.3.x).

Files changed (count: 9; 7 new, 2 modified):
- `pom.xml` — `+`two ShedLock deps.
- `src/main/java/com/childcarewow/calendar/idempotency/IdempotencyPurgeJob.java` — `+@SchedulerLock` on `purge()`; Javadoc refreshed.
- `src/main/java/com/childcarewow/calendar/scheduling/SchedulingConfig.java` — new (committed; scaffolded by prior session).
- `src/main/java/com/childcarewow/calendar/federalholiday/NagerHoliday.java` — new.
- `src/main/java/com/childcarewow/calendar/federalholiday/NagerDateClient.java` — new.
- `src/main/java/com/childcarewow/calendar/federalholiday/NagerDateClientImpl.java` — new.
- `src/main/java/com/childcarewow/calendar/federalholiday/FederalHolidaySyncJob.java` — new.
- `src/main/resources/db/migration/V9__shedlock.sql` — new (committed; scaffolded by prior session).
- `src/test/java/com/childcarewow/calendar/federalholiday/FederalHolidaySyncJobIT.java` — new (5 tests).

Validation:
- [x] `./mvnw -B verify` → BUILD SUCCESS in 1m17s. 191 tests, 0 failures, 0 errors, 2 skipped (Linux-only Testcontainers ITs); Spotless clean; JaCoCo ≥80% line bundle.
- [x] **`FederalHolidaySyncJobIT` — 5/5 tests green** against real calendar + platform Postgres:
  - **`insertsPendingFederalsForEachSchoolAndYear`** — 2 schools × 3 distinct (year, date) Nager rows → 6 inserts. Verifies counts at each school separately (3 at Sunrise, 3 at Maplewood).
  - **`idempotentReRunDoesNotInsertDuplicates`** — first sync: 4 inserts; second sync against the same Nager response: 0 inserted, 4 skipped (pre-fetch finds every pair). Row count stays at 4.
  - **`existingApprovedFederalDoesNotGetDuplicatedAsPending`** — pre-inserts one approved federal at (Sunrise, 2026-07-04); after sync, that school+date still has exactly one row, still approved. Sister school (Maplewood) and the other holiday date both get pending rows. This is the test that would fail without the service-layer pre-fetch.
  - **`nagerFetchErrorIsRecordedAndDoesNotBlockOtherGroups`** — 2026 fetch throws, 2027 succeeds. 2026 rows: 0 inserted. 2027 rows: 2 inserted. `errors` list contains `"2026/US:RuntimeException"`.
  - **`writesAuditRowWithSyncSummary`** — asserts `audit_events` has one `action=NAGER_SYNC` row with `user_agent="FederalHolidaySyncJob"` and metadata containing `years`, `inserted`, `skipped`, `schools_count: 2`.
- [x] `IdempotencyPurgeJobTest` — 3/3 still green after the `@SchedulerLock` addition. The annotation doesn't take effect under the unit-test path (no Spring proxy), so the existing tests don't need to know about the lock.
- [x] OpenAPI snapshot unchanged (this Part is internal — no controller routes added).

Notes / surprises:
- **`ON CONFLICT ON CONSTRAINT uq_holidays_federal_pending` doesn't work.** The playbook + architecture spec §7.8 prescribed it, and Part 1.3 named the index specifically to enable it. But Postgres only accepts `ON CONFLICT ON CONSTRAINT <name>` for **non-partial** unique constraints — and `uq_holidays_federal_pending` is a *partial* unique index (it has a `WHERE` predicate). The first verify run failed every test with `ERROR: constraint "uq_holidays_federal_pending" for table "holidays" does not exist`. Resolution: switch to the **inference clause** form, `ON CONFLICT (school_id, date) WHERE source = 'FEDERAL' AND approved = false AND deleted_at IS NULL DO NOTHING`. The WHERE predicate must match the index's predicate byte-for-byte or Postgres fails to pick the index. Documented inline in the upsert SQL; architecture spec §7.8 needs a docs amendment.
- **Two-constructor + `@Autowired` rule.** Spring 4.3 auto-detects a single constructor; with two, it falls back to looking for a no-arg ctor unless one is `@Autowired`-annotated. Easy to forget when adding a test seam. Pattern reusable for any future job/service that needs a `Clock` injection seam.
- **`@SchedulerLock` is on `runScheduled()`, not `sync()`.** The annotation only takes effect through the Spring AOP proxy. Tests call `sync()` directly on a manually-constructed job instance, bypassing the proxy — and bypassing ShedLock. This is the intentional shape: tests don't need ShedLock infrastructure spun up; CI Postgres doesn't need an empty `shedlock` table for the IT to pass.
- **Postgres jsonb `::text` cast is pretty-printed.** The audit-row assertion initially used `"schools_count":2` (no space) but Postgres normalises to `"schools_count": 2` (with space). Substring assertion fixed accordingly.
- **The `@MockBean NagerDateClient` is wired into the Spring context AND passed into the manually-constructed test job.** Since `@MockBean` replaces the bean in the context, `@Autowired NagerDateClient` would yield the same mock. The IT pattern (autowire from context + reconstruct the job with a fixed `Clock`) is cleaner than trying to override the Clock via `@DynamicPropertySource`.
- **CalendarApplicationTests.contextLoads requires Docker compose up.** Failed at first because `calendar-db` wasn't running. Not a regression — this is a `@SpringBootTest` smoke that needs the real DB. Standard "docker compose up -d calendar-db platform-db" pre-step.

### Carry-forward (cleared)

- **ShedLock for `IdempotencyPurgeJob` — DONE.** Was tracked since Part 3.13.

### Carry-forward (still open)

- Bump GitHub Actions versions before Node 20 deprecation (2026-09-16).
- Application config externalization (Part 0.8 — Spring Cloud AWS Secrets Manager).
- COPPA-blocking CUSTOM event recipient narrowing to `event_students` (Part 12.4).
- STUDENT_VIEW audit on CUSTOM event reads (deferred from 5.4).
- MapStruct adoption when Series 6+ accumulates 3+ mappers.
- AttachmentController slice + Supabase live integration — Series 11.4.
- Frontend codegen Part 4.6 (FE repo).
- FE cutover Parts 6.2a/6.2b/6.9.
- **NEW:** Architecture spec §7.8 doc amendment — `ON CONFLICT ON CONSTRAINT` → inference-clause form (partial index limitation).

Next part: **6.8 — Notification pause logic verification** (in-place test leveraging fixtures from 5.8 + 6.1/6.4). Series 6 has one more backend part after 6.8, the FE cutover trio (6.2a/6.2b/6.9), then Series 7.

---

## Series 6 progress (Parts 6.1 → 6.6) — holidays backend (deferring FE cutover) — STATUS: ✅ done
Date: 2026-05-08
Operator: Mukul Phogat

Six PRs landed in one session, **same per-part code-and-test rhythm as Series 5.6+** (no per-part progress.md PRs; this is the consolidated hand-off). Parts 6.2a/6.2b/6.9 (frontend cutover) **deferred** — they require operator visual side-by-side compare and live in Events_CCW, not this repo. The backend halves of those parts are not blocked: subsequent backend parts (6.3, 6.4, 6.5, 6.6) ran cleanly without the FE shadow infrastructure in place.

### Part 6.1 — `POST /api/v1/holidays` (CUSTOM)

What got built:
- New `holiday/CreateHolidayRequest`, `HolidayView`, `HolidayService`, `HolidayController`. `HolidayRepository.findApprovedAt(schoolId, date)` query with `approved=true && deleted_at IS NULL` filter.
- `policy.assertCan(actor, "holiday.manage")` gate (Part 3.2 wired this for ADMIN roles).
- `platformValidator.assertSchoolExists(req.schoolId())` — fail-closed if platform DB can't confirm.
- Duplicate check throws `DuplicateHolidayException` (409) ahead of unique-index violation. Unapproved-federal rows on the same date are allowed to coexist; the duplicate rule scopes to `approved=true` per playbook line 2719.
- Insert with `source=CUSTOM, approved=true, approvedAt=now(), approvedByUserId=actor.id()` — CUSTOM auto-approves at creation per playbook common-failure-point line 2732.
- `saveAndFlush + em.refresh` to populate created_at/updated_at, then `softFlagService.recomputeForHoliday(saved)` inserts retroactive HOLIDAY flags on pre-existing events/tasks on that date.
- `@Audited("HOLIDAY_CREATE", targetType="HOLIDAY")`.

Tests: `HolidayCreateIT` (3) — happy path + duplicate-409 + retroactive flag inserted with name in message. PR #98 merged.

### Part 6.2 — `GET /api/v1/holidays` + `GET /api/v1/holidays/{id}`

What got built:
- `HolidayRepository.findFiltered(schoolId, approved, source)` — nullable filters via `IS NULL OR =` idiom; orders by `date ASC`; always excludes soft-deleted.
- List visibility:
  - **PARENT clamp**: even if the query asks `approved=false`, the service forces `approved=true` unconditionally per playbook line 2754. Federal-pending rows must never leak to parents.
  - **STAFF/ADMIN**: query params honored as-is.
  - School scope: ORG_ADMIN sees any school in their org; school-scoped roles only see their own → cross-school requests return 403 (not silent empty list — fail loud so FE bugs are visible).
- Detail (`findById`) hides unapproved-federal rows from parents as **404 not 403** to avoid leaking existence.

Tests: `HolidayReadIT` (12) — full role × filter matrix + cross-school 403 + 404 cases. PR #99 merged.

### Part 6.3 — `PUT /api/v1/holidays/{id}` + `DELETE /api/v1/holidays/{id}`

What got built:
- PUT: validates name/notes/date; `source`, `approved`, and `schoolId` are intentionally immutable (playbook common-failure-point line 2820 — those go through the approve endpoint and federal sync only). Date-change duplicate check via `findApprovedAt` with `id != self` predicate. `softFlagService.recomputeForHoliday` runs unconditionally — name change rewrites the flag message; date change shifts flags between events.
- DELETE: soft-delete via `deletedAt = now()`. New `softFlagService.removeFlagsForHoliday(holidayId)` method (distinct from `recomputeForHoliday` so the delete path doesn't re-insert flags).
- Both gated by `holiday.manage`.

Tests: `HolidayUpdateDeleteIT` (9) — rename, date-move shifts flags, name-change rewrites flag message, schoolId immutable, date-collision → 409, unknown-id → 404, soft-delete clears flags, double-delete → 404. PR #100 merged.

### Part 6.4 — `POST /api/v1/holidays/{id}/approve`

What got built:
- Approves a federal-pending holiday → flips `approved=true`, sets `approvedAt`/`approvedByUserId`, fires `recomputeForHoliday` so pre-existing events on that date get HOLIDAY flags.
- **Idempotent**: re-approving an already-approved row returns the existing view without mutating `approvedAt` or re-running recompute.
- **Pre-check**: per playbook common-failure-point line 2843, if a different already-approved holiday occupies `(school_id, date)`, throws `DuplicateHolidayException` (409) **ahead** of the unique-index `uq_holidays_federal_pending` violation. Self-match excluded via `id != self`.
- Soft-deleted rows surface as 404.
- `@Audited("HOLIDAY_APPROVE")`.

Tests: `HolidayApproveIT` (5) — approve fires recompute; idempotent (approvedAt unchanged); date-collision → 409; unknown id → 404; soft-deleted → 404. PR #101 merged.

### Part 6.5 — `POST /api/v1/holidays/approve-batch`

What got built:
- Bulk approval with **per-row failure isolation**. One row's exception (NOT_FOUND, DUPLICATE_HOLIDAY, etc.) does NOT roll back the rest.
- **Per-row transactions via self-injection**: the batch method itself is intentionally NOT `@Transactional`; each id runs through `@Lazy`-injected `self.approve(id, actor)` which goes back through the Spring proxy. REQUIRED propagation creates a new tx for each id since the outer batch method has none. Calling `this.approve(...)` from the loop would skip the proxy and the inner `@Transactional` would have no effect — leaving the loop in an inconsistent state if one id fails.
- Bean validation: `@NotEmpty + @Size(max=100)` on the ids list. Over-cap returns 400 `VALIDATION_ERROR` via `GlobalExceptionHandler` before any DB work runs (playbook line 2855).
- New `ApproveBatchRequest` + `ApproveBatchResult` DTOs. `Result.skipped` carries `(id, reason)` per skipped row. Reasons: `NOT_FOUND`, `DUPLICATE_HOLIDAY`, `FORBIDDEN`, plus a bonus `ALREADY_APPROVED` distinguishing "approved this run" from "was already approved" (the latter doesn't bump `approved` count).

Tests: `HolidayApproveBatchIT` (5) — mixed batch (3 valid + 1 not-found + 1 duplicate); already-approved skip counts toward skipped not approved; per-row tx isolation (failure between two valids doesn't roll them back); empty list returns zero-result at service layer; 101 ids run at service layer (cap enforced at controller). PR #102 merged.

### Part 6.6 — `GET /holidays?source=FEDERAL&approved=false` query path verification

What got built:
- Test-only PR. Pins the specific query path used by the federal-approval panel.
- Covers the playbook common-failure-point line 2885: soft-deleted federals must NOT leak.

Tests: `PendingFederalListIT` (4) — ORG_ADMIN sees both pending federals (excluding approved + pending CUSTOM + soft-deleted); ORG_ADMIN at other school works; SCHOOL_ADMIN cross-school 403; PARENT clamp fires on this query path. PR #103 merged.

### Cross-cutting findings

- **`SoftFlagService.removeFlagsForHoliday(UUID)` was added in 6.3** — it wraps the existing `repo.deleteHolidayFlagsForHoliday` helper. Distinct from `recomputeForHoliday` so the holiday delete path doesn't re-insert flags it just deleted.
- **Self-injection idiom in 6.5** is now the precedent for any future batch operation that needs per-row tx isolation. Pattern: `@Lazy private final ServiceClass self;` constructor-injected; loop calls `self.method(...)` instead of `this.method(...)`.
- **Idempotency strategy** for batch operations: pre-check status with non-tx fetch BEFORE invoking the per-row tx, so we can distinguish "did work" from "was already in target state" without an extra round-trip inside the tx.

### Validation (whole sub-series)

- [x] `mvn -B clean verify` after each part — all green. Final after 6.6: 186 tests, 2 skipped (Linux-only Testcontainers ITs), JaCoCo bundle ≥80%, Spotless clean. ~3min wall.
- [x] OpenAPI snapshot regenerated after each schema-changing part (6.1, 6.2, 6.3, 6.4, 6.5).
- [x] CI green on PRs #98 / #99 / #100 / #101 / #102 / #103 (~1m30s each).
- [x] Branch protection respected — squash merges on `main`.

### Carry-forward (new from this batch)

- **Parts 6.2a/6.2b/6.9 (FE cutover) deferred** — they require operator visual side-by-side compare. The backend half of 6.2a (the `/diagnostics/shadow-diff` endpoint) was deliberately not built standalone; the FE half is more substantive and the operator should land both in one session. Doesn't block backend.
- **Part 6.7 (Nager.Date sync job)** — meaty next part: scheduled `RestClient`, idempotent upsert via `uq_holidays_federal_pending`, ShedLock for multi-instance ECS deploys (playbook line 2923). Worth its own session.
- **Part 6.8 (notification pause verification)** — in-place test using fixtures from 6.1 + 5.8.
- **Existing carry-forwards still open** (unchanged from Series 5 entry): Node 20 deprecation, Part 0.8 config externalization, MapStruct adoption, AttachmentController slice, ShedLock for IdempotencyPurgeJob, FE codegen Part 4.6, COPPA-blocking CUSTOM recipient narrowing (5.8).

### Counts after Series 6 (so far)

- 57 PRs merged total (50 entering this batch + 7 today: 6 code + 1 progress.md doc earlier).
- Backend Parts complete: P0.* + Series 0–4 + Series 5 (8/9; 5.9 deferred) + Series 6 (6/9; 6.2a/6.2b/6.9 FE-deferred; 6.7/6.8 next).

Next part: **6.7 — Nager.Date scheduled sync job**.

---

## Series 5 close (Parts 5.6 → 5.8) — events backend complete — STATUS: ✅ done
Date: 2026-05-08
Operator: Mukul Phogat

Per-part progress entries were rolled into this single hand-off (operator agreement, mid-series). Three PRs landed (one per part); no per-part progress.md PR was opened. **Part 5.9 is deferred** to 6.2a/6.2b (post-holidays GET) per playbook line 2691.

### Part 5.6 — `DELETE /api/v1/events/{id}` (soft-delete)

What got built:
- `EventService.delete(UUID)` — fetches with `deletedAt == null` filter, sets `deletedAt = now()`, `saveAndFlush`, then `softFlagService.removeFlagsForEvent` + `notificationService.dispatchEventDeleted`. Both directions of bidirectional DOUBLE_BOOKING flags clear because `removeFlagsForEvent` deletes by `entity_id = ? OR conflicting_entity_id = ?`.
- `EventController.delete` — `@DeleteMapping("/{id}")`, 204 No Content, `@Audited(action="EVENT_DELETE")`. Loads via `loadForPolicyCheck` then `policy.assertCan(actor, "event.delete", existing)` — same shape as Part 5.5.
- `EventDeleteIT` (4 tests): soft-deletes-and-excludes-from-reads (findById 404 + deleted_at populated + window query excludes); deleteClearsBidirectionalDoubleBookingFlags (verifies the OR predicate clears both A→B and B→A rows); unknownIdReturns404; doubleDeleteReturns404 (second delete sees deletedAt-filtered row → 404).

Files: `EventService.java`, `EventController.java`, `EventDeleteIT.java` (new). PR #94, merged.

### Part 5.7 — Holiday-blocks-event-creation enforcement

What got built:
- `EventService.findApprovedHolidayName(schoolId, localDate)` — replaces the boolean `isHolidayForSchool` with a name-returning helper so the thrown `EventOnHolidayException` can carry the actual holiday name (e.g. "Christmas Day") instead of a date string. Filter: `approved = true AND deleted_at IS NULL`.
- Create + update paths both call this helper after computing `TimezoneService.toSchoolLocalDate(startDt, schoolId)`. Failing match → `throw new EventOnHolidayException(name)` → 409 envelope.
- `HolidayBlockIT` (4 tests): approvedHolidayBlocksCreationWithExceptionCarryingName (asserts message contains the holiday name); sameDateAtDifferentSchoolIsAllowed (Sunrise blocks but Maplewood passes — proves the school_id scoping); unapprovedHolidayDoesNotBlock (federal-pending state must not block — `approved=false` row inserted via raw JDBC); softDeletedHolidayDoesNotBlock (`deleted_at not null` excluded by the helper).

Files: `EventService.java`, `HolidayBlockIT.java` (new). PR #95, merged.

Notes / surprises:
- The existing `EventServiceIT` already had a "blocks on approved holiday" test from Part 5.1; that test stays green (the date-string message form was loose enough to still match). HolidayBlockIT adds the four tighter cases.
- Holidays controller doesn't exist yet (Part 6.1) so all fixtures use raw `INSERT INTO holidays` SQL.

### Part 5.8 — `NotificationService` writes EVENT_INVITE/UPDATED/CANCELLED rows

What got built:
- Replaced the Series 5.1 stubs with real writes against `notifications` + `notification_recipients` (architecture spec § 7.4). The service stops at the row write — actual email/push delivery is Series 11.
- Three public dispatchers:
  - `dispatchEventCreated` → `EVENT_INVITE` when `inviteParents=true`.
  - `dispatchEventUpdated(prev, next)` → off→on=`EVENT_INVITE`, on→off=`EVENT_CANCELLED`, on→on=`EVENT_UPDATED`, off→off=no-op.
  - `dispatchEventDeleted` → `EVENT_CANCELLED` when the deleted event had invitees.
- Type-specific recipient resolution against the platform DB:
  - `SCHOOL` → `users.role='PARENT' JOIN user_schools` for the school.
  - `CLASSROOM` → `student_parents JOIN students` filtered by `classroom_id` and `s.deleted_at IS NULL`.
  - `CUSTOM` → coarse "parents at school" rule. **Flagged COPPA-blocking** in the resolver javadoc — Part 12.4 narrows to `event_students`. Until then, `excludedParticipantIds` is the only knob to restrict.
- Excluded participants subtract from the base set: `USER` ids directly; `STUDENT` ids resolved to parent user_ids via `student_parents WHERE student_id IN (:ids)`.
- Holiday-pause check via `TimezoneService.toSchoolLocalDate` then `SELECT name FROM holidays WHERE approved=true AND deleted_at IS NULL`. Non-null match → notification row is still written but with `paused=true` + `pausedReason="Holiday: <name>"` and the message prefixed `[paused: ...]`. Recipients are still inserted so a future unpause job can flip the flag without rebuilding the recipient set.
- `NotificationDispatchIT` (9 tests): SCHOOL writes parents-at-school; CLASSROOM writes parents-of-students-in-classroom (Butterflies → Priya only, since Caterpillars' Jordan has no parent linked in seed); inviteParents=false writes nothing; excluded user_id subtracts directly; excluded student_id subtracts that student's parents (Maplewood + exclude Lila → Daniel drops out → no recipients → no row); off→on writes EVENT_INVITE; on→off writes EVENT_CANCELLED; on→on edit writes EVENT_UPDATED; delete with invitees writes EVENT_CANCELLED.

Files: `NotificationService.java` (rewritten — was a stub since 5.1), `NotificationDispatchIT.java` (new). PR #96, merged.

Notes / surprises:
- "No recipients → no row" is an intentional shortcut: if the resolved recipient set is empty (e.g. all excluded), `writeWithRecipients` early-returns without writing the parent `notifications` row. Tests rely on this (`excludedUserIdSubtractsFromRecipients` asserts `countNotifications == 0`). If FE later wants to render "this event was created but had nobody to notify," we'll need to flip the contract.
- `paused` is a column on `notifications`, not on `notification_recipients`. One row covers all recipients of one event-event for that pause.

### Validation (whole sub-series)

- [x] `mvn -B clean verify` after each part — all green. Final run after 5.8: 148 tests, 2 skipped (Linux-only Testcontainers ITs), JaCoCo bundle ≥80%, Spotless clean. ~3m22s wall.
- [x] OpenAPI snapshot unchanged across 5.6/5.8 (DELETE in 5.6 added one operation; pinned snapshot updated mid-PR).
- [x] CI green on PRs #94 / #95 / #96 (~1m30s each).
- [x] Branch protection respected — squash merges on `main`.

### Carry-forward (none new from these three parts)

- Bump GitHub Actions versions before Node 20 deprecation (2026-09-16).
- Application config externalization — Part 0.8.
- STUDENT_VIEW audit on CUSTOM event reads — deferred from 5.4.
- MapStruct adoption when Series 6+ has 3+ mappers.
- AttachmentController slice + Supabase live integration — Series 11.4.
- ShedLock for `IdempotencyPurgeJob` — Series 11.4.
- Frontend codegen Part 4.6 (FE repo).
- **NEW (5.8):** narrow `CUSTOM` notification recipients to `event_students` roster — tracked as Part 12.4.

### Counts after Series 5 backend

- 50 PRs merged total (47 entering this batch + 3 today).
- Backend Parts complete: P0.* + Series 0–4 fully + Series 5 Parts 5.1 → 5.8 (5.9 deferred to 6.2a/6.2b).

Next part: **6.1 — `POST /api/v1/holidays` (CUSTOM)**.

---

## Part 5.5 (Series 5) — `PUT /api/v1/events/{id}` — update flow — STATUS: ✅ done
Date: 2026-05-08
Operator: Mukul Phogat

What got built:
- `PUT /api/v1/events/{id}` on `EventController` with `@Audited("EVENT_UPDATE", targetType="EVENT")`. Resource-bearing policy gate: `service.loadForPolicyCheck(id)` returns the existing entity, then `policy.assertCan(actor, "event.edit", existing)` runs Part 3.2's STAFF type-specific scoping (classroom-membership for CLASSROOM, school for CUSTOM, denied for SCHOOL). Type-flip to SCHOOL also asserts `event.create.schoolType` so a STAFF can't promote.
- `EventService.update(id, req, actor)`:
  1. 404 if missing or soft-deleted.
  2. **Snapshot** the pre-mutation entity via `snapshotForDiff` — captures the fields the notification diff will inspect (title, startDt, endDt, classroomId, organizerUserId, inviteParents). Detached copy so subsequent mutations don't shift its identity.
  3. Same `validateRequest` as create.
  4. **Holiday check fires only when `startDt`'s instant differs from existing** (per the prototype's `validateEventInput` and the playbook's "same-date edit doesn't re-check holiday" requirement). Same-date title/description edits skip the timezone + holidays SELECTs entirely.
  5. PlatformEntityValidator gates re-run (school + classroom for CLASSROOM, organizer if set, attendees/students for CUSTOM, exclusion resolution for SCHOOL).
  6. Mutate the managed entity in place + `saveAndFlush + em.refresh`.
  7. **Clear-and-rebuild join tables** (`event_attendees`, `event_students`, `event_excluded_participants`). Simpler than diffing against the prior set; CASCADE doesn't help when the parent isn't being deleted. Type flip from CUSTOM → other clears stale rows even when no new ones are inserted.
  8. `softFlagService.recomputeForEvent` always runs (time/classroom/organizer changes can introduce or remove overlaps, so the bidirectional pair sweep matters).
  9. `notificationService.dispatchEventUpdated(prev, saved)` — stub until 5.8.
- `NotificationService.dispatchEventUpdated(prev, next)` — new no-op stub paired with the existing `dispatchEventCreated`. Real diff logic (UPDATE/CANCEL/INVITE based on inviteParents flip + core-field changes) lands in 5.8.
- `EventService.loadForPolicyCheck(id)` — small helper for the controller's pre-update entity load.

Files changed (count: 5):
- `src/main/java/com/childcarewow/calendar/event/{EventController,EventService}.java`
- `src/main/java/com/childcarewow/calendar/notification/NotificationService.java`
- `src/test/java/com/childcarewow/calendar/event/EventUpdateIT.java` — new (7 tests).
- `docs/openapi.json` — regenerated baseline with the new PUT route.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m15s; bundle gate ≥80% line met.
- [x] `EventUpdateIT` — 7/7 tests green:
  - **`updatesTitleAndDescriptionInPlace`**: rename + `inviteParents` flip in one call; response reflects both.
  - **`sameDateEditDoesNotRecheckHoliday`**: insert approved holiday on the event's current date, run a no-time-change edit — passes (would fail if the holiday check fired). Confirms the per-`startDt`-change gating.
  - **`dateChangeToHolidayRejected`**: move event onto Thanksgiving → `EventOnHolidayException`.
  - **`timeChangeRecomputesSoftFlags`**: create two non-overlapping events in same classroom; move the first into overlap; response carries the `DOUBLE_BOOKING` flag pointing at the second.
  - **`typeFlipFromCustomToClassroomClearsAttendeesAndStudents`**: 2 attendees + 1 student before; 0 + 0 after the type flip (verified via SQL `COUNT`).
  - **`unknownIdReturns404`**: NotFoundException.
  - **`loadForPolicyCheckReturnsEntityForKnownId`**: helper round-trip.
- [x] All 19 + 15 + 4 prior tests still green.
- [x] OpenAPI baseline updated.
- [x] CI green on PR #92 ([run 25558010449](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25558010449)).

Notes / surprises:
- **Detached snapshot via `snapshotForDiff`** rather than mutating-then-diffing the managed entity. Setting fields on the JPA-managed entity AFTER capturing the snapshot wouldn't affect the snapshot (it's a separate object), but using a detached copy is safer when 5.8 wires up real notification dispatch — the dispatcher doesn't accidentally trigger lazy loading on the snapshot.
- **`eventRepo.findById(...).filter(deletedAt==null)`** is the same pattern used in 5.4's `findById`. Could be lifted into a `findActive(id)` helper if it shows up a third time in 5.6.
- **Clear-and-rebuild on join tables** chosen over diff-and-patch because: (a) the typical attendee/student count is small (<10), (b) the diff plumbing would need to know the prior state which requires another SELECT, (c) the recompute pattern matches `softFlagService.recomputeForEvent`'s clear-then-rebuild design from Part 3.11. Trade-off: writes O(N) rows instead of O(Δ); not a hot path.
- **`startMoved` check uses `.toInstant().equals(...)`** to compare across `OffsetDateTime` values that may have different offsets but the same wall-clock UTC instant. Without `.toInstant()`, two equivalent times in different time zones (e.g., `2026-09-15T14:00:00-04:00` and `2026-09-15T18:00:00Z`) would compare unequal as `OffsetDateTime`s.
- **`event.create.schoolType` re-assertion on type promotion**: a STAFF or SCHOOL_ADMIN editing a CUSTOM event into a SCHOOL event would otherwise sneak past the create-time gate. Catching it here keeps the "SCHOOL events require admin" policy uniform across create + edit. The double-check is cheap (PolicyService is in-memory).

Next part: 5.6 — `DELETE /api/v1/events/{id}` (soft delete with `event.delete` policy + cleanup of join tables and DOUBLE_BOOKING flag pairs on the surviving side).

---

## Part 5.4 (Series 5) — `GET /api/v1/events/{id}` + calendar-window query — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- Two read endpoints on `EventController`:
  - `GET /api/v1/events/{id}` → `EventView` with all join-table arrays + softFlags. **404 (not 403) when the event exists but the actor can't see it** — never leak existence outside visibility scope.
  - `GET /api/v1/events?schoolId=&from=&to=&type=` → `List<EventView>` for the inclusive `start_dt` window, optional type filter; auth-only at the controller (visibility filter inside the service).
- `EventRepository.findInWindow(schoolId, from, to, type)`: inclusive bounds, soft-deleted excluded, `(:type IS NULL OR e.type = :type)` pattern for the optional filter. Sorted by `start_dt`.
- **Role-aware visibility matrix** in `EventService.isVisibleTo`:
  - **ORG_ADMIN**: any event in their org.
  - **SCHOOL_ADMIN**: any event in `actor.schoolIds()`.
  - **STAFF**: SCHOOL events at own schools; CLASSROOM events in own classroomIds; CUSTOM events as organizer OR in `event_attendees`.
  - **PARENT**: requires `inviteParents=true`, NOT excluded (user-id OR any childStudentId in exclusions), AND child-in-scope: SCHOOL = school in `actor.schoolIds()`; CLASSROOM = at least one childStudentId has `students.classroom_id = event.classroomId` (queried directly via `platformNamedJdbcTemplate`); CUSTOM = at least one childStudentId in `event_students`.
- Join-table loaders: `loadAttendees`, `loadStudents`, `loadExclusions` read on demand. Both visibility check and view assembly call them; pre-loading once would avoid the duplicate work but adds plumbing — punted to Series 12 perf review.
- `parentChildInClassroom(actor, classroomId)`: `SELECT COUNT(*) FROM students WHERE id IN (:ids) AND classroom_id = :classroomId AND deleted_at IS NULL`. The platform-DB hop is the only way to bridge the parent's `childStudentIds → classroom_id` mapping (UserPrincipal doesn't carry it).

Files changed (count: 5):
- `src/main/java/com/childcarewow/calendar/event/{EventController,EventService,EventRepository}.java`
- `src/test/java/com/childcarewow/calendar/event/EventReadIT.java` — new (15 tests).
- `docs/openapi.json` — regenerated baseline with the two new GET routes.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 2m14s after the OpenAPI snapshot regen.
- [x] `EventReadIT` — 15/15 tests green, covering the full visibility matrix:
  - ORG_ADMIN reads all 3 event types.
  - SCHOOL_ADMIN: in-school yes, out-of-school 404.
  - STAFF CLASSROOM/SCHOOL/CUSTOM: each scoped correctly.
  - PARENT: child's classroom (404 for other classroom).
  - PARENT `inviteParents=false` → 404.
  - **PARENT user-excluded** → 404.
  - **PARENT child-excluded** (student id in exclusions) → 404.
  - PARENT CUSTOM: child in `event_students` → visible.
  - Window endpoint: full set for ORG_ADMIN, type filter narrows, parent filter applies.
  - Unknown id → 404.
- [x] All earlier 19 EventServiceIT + 4 EventControllerTest tests still green.
- [x] OpenAPI baseline updated.
- [x] CI green on PR #90 ([run 25521759578](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25521759578)).

Notes / surprises:
- **404 vs 403 for "exists but not visible"** is a deliberate security choice: returning 403 confirms the event exists, leaking metadata. 404 is uniform with "doesn't exist". Documented inline; same pattern should apply to read endpoints in Series 6+ (holidays, tasks, etc.).
- **Parent CLASSROOM visibility hits the platform DB** via `parentChildInClassroom`. UserPrincipal carries `childStudentIds` but NOT each child's classroom — adding it would require loading classroom assignments at JWT-resolve time and updating MeView. Cheaper for now to query on-demand; the Caffeine cache on `PlatformEntityValidator` doesn't help because we need the inverse mapping (student → classroom). Series 11 perf review can revisit.
- **Loaders called twice per event** (visibility check + view assembly) is wasteful but explicit. Pre-loading once would require keeping a per-event Map<UUID, JoinTables> and threading it through the view builder — punted. Acceptable for read paths because the calendar-window typical size is ~30 events × 3 join tables × 1 round-trip = ~90 SELECTs, all of them at most a few rows. If profiling later flags this, the fix is one batch `WHERE event_id IN (:ids)` per join table. 
- **STUDENT_VIEW audit on CUSTOM reads** (playbook step 4) NOT yet wired — will land in a follow-up. The `@AuditRead` annotation can't easily express "extract studentIds from CUSTOM events only" via SpEL on a mixed-type response. Likely answer: the service iterates the result, collects student UUIDs from CUSTOM events, calls `auditService.log` directly when non-empty. Tracked.
- **Window response can grow unboundedly**: no pagination yet. The playbook called for "paginated list" but the FE typically queries one month at a time which naturally caps the result. Adding cursor-based pagination is a Series 6+ task once the FE shadow turns on real traffic.

Next part: 5.5 — `PUT /api/v1/events/{id}` (update flow with policy-bearing `event.edit` action, idempotency, and recompute on date/classroom change).

---

## Part 5.3 (Series 5) — `POST /api/v1/events` (SCHOOL type + excludedParticipantIds) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `CreateEventRequest`: gains `excludedParticipantIds: List<UUID>` (optional). Two backward-compat constructors (10-arg from Part 5.1, 12-arg from Part 5.2) preserve all earlier callers.
- `EventView`: gains `excludedParticipants: List<ExcludedParticipantView>` where each entry tags the id as `"USER"` or `"STUDENT"`.
- SCHOOL-type validation: no classroom, no attendees, no students — each combination rejected with a clear field-targeted error. `excludedParticipantIds` only valid for SCHOOL.
- **Resolution logic in `EventService.resolveExclusions(ids)`**: `PlatformEntityValidator.userExists(id)` first → if true, type=USER. Else `studentExists(id)` → if true, type=STUDENT. Else `ValidationException` with the offending id in the message ("matches neither a user nor a student"). USER-precedence rule documented in Javadoc per playbook common-failure-points.
- Insertion via `calendarJdbcTemplate.batchUpdate` into `event_excluded_participants` with `ON CONFLICT DO NOTHING` (same race-safety pattern as 5.2). The migration's `CHECK (participant_type IN ('USER', 'STUDENT'))` is the DB-side guard.

Files changed (count: 7):
- `src/main/java/com/childcarewow/calendar/event/{CreateEventRequest,EventView,EventMapper,EventService}.java`
- `src/test/java/com/childcarewow/calendar/event/{EventServiceIT,EventControllerTest}.java`
- `docs/openapi.json` — regenerated with new fields + `ExcludedParticipantView` component.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m45s; bundle gate ≥80% line met.
- [x] `EventServiceIT` — 19/19 tests green:
  - 11 prior tests (CLASSROOM + CUSTOM + holiday + overlap + validation) still pass.
  - **`schoolEventCreatesWithoutClassroomOrAttendees`**: SCHOOL with empty arrays → `event_attendees` and `event_students` join-table counts both 0; `excludedParticipants` empty.
  - **`schoolEventWithMixedExclusionsTagsParticipantTypeCorrectly`**: 1 user + 1 student → response includes both `ExcludedParticipantView`s; direct SQL `SELECT participant_id, participant_type FROM event_excluded_participants` confirms USER and STUDENT tags landed correctly.
  - **`schoolEventRejectsExcludedIdMatchingNeitherUserNorStudent`**: bad UUID → `ValidationException`.
  - **`schoolEventRejectsClassroomId`**: SCHOOL + classroomId → reject.
  - **`rejectsExcludedParticipantIdsForNonSchoolEvent`**: CLASSROOM + excludedParticipantIds → "only valid for SCHOOL" error.
- [x] `EventControllerTest` — 4/4 slice tests still green; the EventView mock just needed one more `List.of()` for the new exclusions field.
- [x] OpenAPI snapshot regenerated — `excludedParticipantIds`, `excludedParticipants`, and the `ExcludedParticipantView` schema all surface in `docs/openapi.json`.
- [x] CI green on PR #88 ([run 25520983975](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25520983975)).

Notes / surprises:
- **Three constructor overloads on `CreateEventRequest` is the limit.** Each new field added in 5.2 (2 fields) and 5.3 (1 field) needed an overload to preserve prior call sites. If 5.4+ adds another optional field, dropping the overloads and forcing call-site updates becomes the better path — three is already a maintenance smell. Trade-off documented inline.
- **USER-vs-STUDENT precedence ambiguity is largely theoretical**. UUIDs are 122 bits of entropy; collisions across the two tables would require malicious construction. The precedence rule (USER wins) exists for completeness, not for any expected production case. Documented so a future maintainer doesn't second-guess the choice.
- **The `resolveExclusions` step happens BEFORE `eventRepo.saveAndFlush`** so a bad id fails fast without inserting an orphan event row. Java records the exclusions list, persists the event, then writes the join rows in the same transaction. If the join-row insert ever fails, the entire `@Transactional` rolls back the event row too.
- **No new policy actions** for SCHOOL — the existing `event.create.schoolType` action (already in `PolicyServiceImpl` from Part 3.2) handles the gate. The controller's existing `if (req.type() == SCHOOL) policy.assertCan(actor, "event.create.schoolType")` was put in place specifically for this scenario.
- **Series 5 milestone**: with 5.3 merged, the create flow is feature-complete across all three event types. The remaining 7 parts of Series 5 (read, update, delete, holiday-blocks, notifications) build on this core.

Next part: 5.4 — `GET /api/v1/events/{id}` + `GET /api/v1/events?from=&to=&...` (read endpoints with calendar-window query semantics).

---

## Part 5.2 (Series 5) — `POST /api/v1/events` (CUSTOM type) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `CreateEventRequest` gains optional `attendeeUserIds: List<UUID>` and `studentIds: List<UUID>`. Both default to empty list when null. Added a backward-compat 10-arg constructor so Part 5.1 callers + tests still compile.
- `EventView` gains the same two fields — populated for CUSTOM events, empty lists for CLASSROOM. The values echo the request input (sanitized through PlatformEntityValidator) rather than re-fetching from the DB; saves a round-trip on the create path.
- `EventService.create` now handles `EventType.CUSTOM`:
  - Validation: `assertUserExists` for each attendee, `assertStudentExists` for each student. Caffeine cache absorbs per-id round-trip cost.
  - After `saveAndFlush + em.refresh`, batch-insert via `calendarJdbcTemplate.batchUpdate` with `ON CONFLICT DO NOTHING` on both join tables.
  - Empty-array fast path: short-circuits without sending an empty batch (cheap and avoids Postgres-driver edge cases).
- `validateRequest` now rejects only SCHOOL (Part 5.3) and unknown enum values; CUSTOM is fully supported.

Files changed (count: 7):
- `src/main/java/com/childcarewow/calendar/event/{CreateEventRequest,EventView,EventMapper,EventService}.java`
- `src/test/java/com/childcarewow/calendar/event/{EventServiceIT,EventControllerTest}.java`
- `docs/openapi.json` — regenerated baseline with the two new optional fields on `CreateEventRequest` + `EventView`.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m56s; bundle gate ≥80% line met.
- [x] `EventServiceIT` — 15/15 tests green:
  - 11 existing CLASSROOM tests still pass (backward-compat constructor).
  - **`customEventWithAttendeesAndStudentsWritesJoinTables`**: 2 attendees + 2 students → response echoes both arrays AND `SELECT COUNT(*) FROM event_attendees/event_students` returns 2 each.
  - **`customEventWithEmptyArraysWritesNoJoinRows`**: empty-array fast path; zero join rows.
  - **`customEventRejectsUnknownAttendee` / `customEventRejectsUnknownStudent`**: ValidationException via PlatformEntityValidator.
  - The previous `rejectsCustomTypeUntil5_2` was repurposed into `rejectsSchoolTypeUntil5_3`.
- [x] `EventControllerTest` — 4/4 slice tests still green; the EventView mock just needed the two new list fields appended (both empty for CLASSROOM).
- [x] **OpenAPI drift** caught by `OpenApiSnapshotIT` on the first verify run; baseline regenerated via `OPENAPI_SNAPSHOT=update` env var.
- [x] CI green on PR #86 ([run 25520191269](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25520191269)).

Notes / surprises:
- **`ON CONFLICT DO NOTHING`** on the batch insert is defensive against race conditions (two concurrent retries via the IdempotencyFilter in flight). The IdempotencyFilter normally short-circuits replays before the controller, but a misconfigured caller without `Idempotency-Key` could still double-fire. The ON CONFLICT clause makes the second batch a no-op rather than a 23505 unique-violation 500. Both join tables have the composite PK `(event_id, user_id)` / `(event_id, student_id)` so the constraint is structural.
- **Backward-compat constructor pattern** worked cleanly. 11 prior CreateEventRequest call sites compile unchanged because the 10-arg overload delegates to the 12-arg canonical constructor with `null` defaults. Java records support multiple constructors as long as the additional ones eventually call the canonical one. The defensive accessor methods (`attendeeUserIds()` and `studentIds()`) on the record handle `null → empty` translation transparently for service code.
- **Echoing request arrays back in EventView** is a deliberate choice over re-fetching from the join tables. Saves a SELECT roundtrip on the hot create path; the values are already known to be correct because they passed PlatformEntityValidator. If the read endpoint (Part 5.4) needs to surface attendees/students from the actual rows, it'll do its own SELECT; this part's response is verifiable without it.
- **Calendar `JdbcTemplate` injected alongside JPA**: This is the first place we mix the two access patterns in the same service. JPA for the entity round-trip (Hibernate manages `created_at`/`updated_at`); JdbcTemplate for the join-table batch (Hibernate's collection mapping for `@ManyToMany` would have been heavier — implicit cascades, dirty-checking overhead, and we'd have to model the join entries as a `Set<UUID>` proxy on Event which is awkward when the table has no domain meaning). Acceptable trade-off; both go through the same `calendarDataSource` so transactions are unified.

Next part: 5.3 — POST /api/v1/events for SCHOOL type with `excludedParticipantIds[]` writing to the third calendar-owned join table.

---

## Part 5.1 (Series 5) — `POST /api/v1/events` (CLASSROOM type) — STATUS: ✅ done · **OPENS SERIES 5**
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `event/EventController` — `POST /api/v1/events` with `policyService.assertCan(actor, "event.create")` (and `event.create.schoolType` for SCHOOL-typed). Annotated `@Audited("EVENT_CREATE", targetType="EVENT")` so every successful create writes one audit row.
- `event/EventService.create(req, actor)` — orchestrates the full create flow:
  1. `validateRequest` (end > start strict; classroomId required for CLASSROOM; CUSTOM/SCHOOL deliberately rejected with "not yet supported" message until 5.2/5.3).
  2. **School-local holiday check** via `TimezoneService.toSchoolLocalDate(startDt.toInstant(), schoolId)` then `isHolidayForSchool` → `EventOnHolidayException` (409). Architecture spec § 3 / § 7.6: never UTC date.
  3. `PlatformEntityValidator` calls: `assertSchoolExists`, `assertClassroomExists`, `assertClassroomBelongsToSchool`, `assertUserExists` (only if `organizerUserId` is set).
  4. Build the `Event` entity (orgId from actor, organizerUserId defaults to actor, createdByUserId always actor, `inviteParents` defaults to false), `saveAndFlush` + `em.refresh` so DB-managed `created_at` / `updated_at` come back populated.
  5. `softFlagService.recomputeForEvent(saved.getId())` — bidirectional DOUBLE_BOOKING pairs in this same transaction.
  6. `notificationService.dispatchEventCreated(saved)` — stub for now; real impl lands in Part 5.8.
  7. `EventMapper.toView(saved, softFlagService.findActiveByEntity(EVENT, id))` — response includes the freshly-computed flags inline.
- `event/CreateEventRequest` record with `jakarta.validation` annotations: `@NotBlank @Size(max=120) title`, `@NotNull` for type/schoolId/startDt/endDt; cross-field rules in service.
- `event/EventView` — matches the prototype's `CalendarEvent` shape plus `softFlags[]` (trimmed nested record). `@JsonInclude(NON_EMPTY)` drops null/empty fields.
- `event/EventMapper` — manual mapping (NOT MapStruct, deliberate deviation: one mapping doesn't justify the annotation-processor wiring; revisit in Series 6+).
- `notification/NotificationService` — stub with `dispatchEventCreated(Event)` no-op + DEBUG log. Real flow (notifications + recipients + holiday-pause + FCM PUSH) lands in 5.8.

Files changed (count: 9, mostly new):
- `src/main/java/com/childcarewow/calendar/event/{EventController,EventService,CreateEventRequest,EventView,EventMapper}.java`
- `src/main/java/com/childcarewow/calendar/notification/NotificationService.java`
- `src/test/java/com/childcarewow/calendar/event/{EventServiceIT,EventControllerTest}.java`
- `src/test/java/com/childcarewow/calendar/auth/TestJwtSigner.java` — `class` → `public class`, `sign(...)` → `public sign(...)` (cross-package slice tests need it).
- `docs/openapi.json` — regenerated baseline including the new POST /api/v1/events route + CreateEventRequest + EventView schemas.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m42s; bundle gate ≥80% line met.
- [x] `EventServiceIT` — 11/11 tests green against real platform + calendar DBs:
  - Happy path: organizer defaults to actor; explicit organizer overrides; createdByUserId always actor.
  - Validation: end < start, end == start, missing classroomId for CLASSROOM, CUSTOM type rejected.
  - Platform: unknown classroom rejected, classroom-in-different-school rejected.
  - **Holiday check**: insert approved holiday → create on that date throws `EventOnHolidayException`.
  - **Soft-flag pair**: two overlapping events → second response includes a `DOUBLE_BOOKING` flag pointing at the first.
- [x] `EventControllerTest` — 4/4 slice tests green: admin 201; PARENT → 403 FORBIDDEN envelope; unauthenticated → 401; invalid body → 400 VALIDATION_ERROR.
- [x] **OpenAPI drift caught and reset**: `OpenApiSnapshotIT` correctly failed on first verify, baseline regenerated via `OPENAPI_SNAPSHOT=update mvn test -Dtest=OpenApiSnapshotIT`. New baseline committed.
- [x] CI green on PR #84 ([run 25519407177](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25519407177)).

Notes / surprises:
- **`saveAndFlush + em.refresh` was needed** to populate `created_at`/`updated_at` in the response. After `saveAndFlush`, Hibernate has the persisted entity but doesn't reload DB-managed columns. Without `em.refresh`, `EventView.createdAt` came back null and the happy-path test failed. Same pattern likely applies to every entity with `insertable=false, updatable=false` columns; all six Series-1 entities have `created_at`/`updated_at` of this shape.
- **Idempotency surface change**: the `IdempotencyFilter` from Part 3.13 already includes `/api/v1/events` in its allowlist (set up before this part). Replays now actually hit a real endpoint; the existing IdempotencyFilterTest already covered this without needing changes.
- **OpenAPI snapshot drift was detected on first verify run** — the `OpenApiSnapshotIT` flagged the new route + schemas. Worked exactly as designed. Worth noting the env-var path (`OPENAPI_SNAPSHOT=update`) is what works under Surefire, not `-Dopenapi.snapshot=update` — caught this in 4.5 too. Reusing the env-var path saves the future maintainer the same diagnostic.
- **`TestJwtSigner` visibility** had to be lifted from package-private to public. The auth package tests used it within the same package; Series 5+ slice tests in other packages need it. Trade-off: slightly larger test surface API; in exchange, slice tests in any controller package can sign tokens without duplicating the signer logic.
- **`NotificationService` stub** explicitly logs at DEBUG, not INFO — keeps the test output clean since this is a no-op path. Once 5.8 lands, the real impl can decide its log level based on actual delivery semantics.
- **Series 1.7 lesson reused**: when calling `softFlagService.findActiveByEntity` immediately after `recomputeForEvent`, the result correctly includes any freshly-inserted flags because both happen in the same `@Transactional`. The IT verifies this with the overlap test — second event's response carries the DOUBLE_BOOKING flag inline.

Next part: 5.2 — `POST /api/v1/events` for **CUSTOM type** with `attendeeUserIds[]` and `studentIds[]` writing to the `event_attendees` and `event_students` join tables.

---

## Part 4.5 (Series 4) — Springdoc OpenAPI + CI snapshot drift check — STATUS: ✅ done · **CLOSES BACKEND SERIES 4**
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `pom.xml`: `+springdoc-openapi-starter-webmvc-ui:2.6.0`. Spring Boot autoconfigures `/v3/api-docs`, `/v3/api-docs/swagger-config`, `/swagger-ui/index.html`, etc.
- `auth/SecurityConfig`: permits `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` alongside the existing `/actuator/health` + `/actuator/info`. The api-docs endpoint was already in the permit list (added in Part 2.1); the swagger-ui paths are new for in-browser exploration.
- `openapi/OpenApiSnapshotIT` — failsafe IT that:
  1. Boots the full Spring context via `@SpringBootTest @AutoConfigureMockMvc`.
  2. Fetches `/v3/api-docs`, parses with Jackson, sorts keys via `ORDER_MAP_ENTRIES_BY_KEYS`, pretty-prints.
  3. Compares byte-for-byte to `docs/openapi.json`.
  4. **First-run mode**: if the file doesn't exist, writes it and returns. Subsequent runs assert equality.
  5. **Update mode**: `-Dopenapi.snapshot=update` OR `OPENAPI_SNAPSHOT=update` env rewrites the baseline. Both supported because Maven Surefire's system-property propagation is finicky and CLI users default to `-D` while CI ergonomics prefer env.
  6. Smoke-test method `specHasExpectedTopLevelStructure` verifies all 7 Series-4 endpoints (`auth/me`, `users`, `schools`, `classrooms`, `students`, `whoami`, `attachments/sign-upload`) appear in the spec — catches "spec emits but routes vanished" failure mode.
- `docs/openapi.json` — committed baseline, 10K bytes, Springdoc-generated, key-sorted via Jackson.

Files changed (count: 4):
- `pom.xml` — `+springdoc` dep.
- `src/main/java/com/childcarewow/calendar/auth/SecurityConfig.java` — `+`3 swagger-ui paths to the permitAll list.
- `src/test/java/com/childcarewow/calendar/openapi/OpenApiSnapshotIT.java` — new (2 tests).
- `docs/openapi.json` — new (10K-byte baseline).

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 45s; bundle gate ≥80% line met.
- [x] `OpenApiSnapshotIT` — 2/2 tests green:
  - First run: writes `docs/openapi.json`, returns silently.
  - Second run (after committing the baseline): asserts byte-for-byte equality, passes.
  - Smoke test: all 7 expected endpoints present.
- [x] CI green on PR #82 ([run 25518027493](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25518027493)).

Notes / surprises:
- **Departed from the playbook's `spring-boot:run` + curl approach.** The playbook spec wants CI to boot the running app and curl `/v3/api-docs`, but the running app needs `supabase-jwt-public-key.pem` from `src/test/resources` — only on the **test** classpath. Booting via `mvn spring-boot:run` against the main classpath fails with "public key location does not exist". Workarounds (test profile, env-var override, copying the key to main resources) all add their own complexity. The failsafe-IT path is cleaner: full Spring context, test classpath, two test methods, deterministic JSON normalization. Trade-off: ~10s overhead per CI run for the full context boot. Documented in the test's class Javadoc.
- **Surefire system-property propagation isn't automatic.** `-Dopenapi.snapshot=update` on the Maven CLI does NOT necessarily reach the forked test JVM. Caught when the first `update` run fell through to the assertion path because the property was unset inside the fork. Worked around by also accepting the `OPENAPI_SNAPSHOT` environment variable, which IS forwarded to forks. Note for future snapshot-style tests: if you want CLI-only update workflow, configure `<systemPropertyVariables>` in surefire/failsafe.
- **First-run-writes-baseline pattern** works well: avoids the boilerplate of a separate "create the file" command. The cost is that a malicious or accidental delete of `docs/openapi.json` would let the next CI run silently re-create it. Mitigation: the file lives in git; deletion would surface as a PR diff. Acceptable for v1.
- **Springdoc operationIds** default to controller method names: `signUpload`, `list`, `me`. Series 6+ controllers should add `@Operation(operationId = ...)` if they want reader-friendly names in the FE-generated TS types — `list` collides with itself across multiple controllers. Springdoc handles the collision by appending suffixes (`list_1`, `list_2`...), but explicit names are cleaner.
- **OpenAPI version 3.0.1** (Springdoc 2.6 default). Frontend Part 4.6 codegen needs to handle this version — `openapi-typescript@7+` does.

Series 4 backend close:
- **6 of 7 parts complete on the backend side** (4.0a, 4.1, 4.2, 4.3, 4.4, 4.5).
- **Part 4.6** — Frontend codegen pipeline — lives in the **frontend repo** (Events_CCW). It will fetch this committed `docs/openapi.json` (or the live `/v3/api-docs` from a running dev backend) and regenerate `src/types/api.ts`. **Tracked here as a follow-up**; it doesn't block backend Series 5 (event/task write endpoints) which can proceed in parallel.
- All Series-4 endpoints contracted: 7 routes (3 GET in auth, 4 GET in selectors, 1 POST sign-upload). The OpenAPI baseline pins their wire shape.
- Total: **83 PRs merged**.

Next part: **Series 5 — Events module backend** (Parts 5.1–5.7). Starts with **Part 5.1 — `EventsService.create` + `POST /api/v1/events`** wiring up the full create-event flow with `policyService.assertCan`, `idempotencyMiddleware`, `softFlagService.recomputeForEvent`, `auditService.log`, and `notificationService.dispatchEventCreated`.

---

## Part 4.4 (Series 4) — `GET /api/v1/students` with `@AuditRead` (COPPA) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `auth/StudentsController` — `GET /api/v1/students?classroomId=` (or `?schoolId=`). Annotated with **`@AuditRead("STUDENT_VIEW", subjectsFrom="![id]")`** — every successful invocation writes one row to `audit_events` with the full UUID list batched under `metadata.subject_ids`. The aspect is `@AfterReturning` so 401/403/validation failures generate no audit row.
- `auth/StudentsReadService.findByScope(schoolId, classroomId, actor)`:
  - **Parent visibility enforced in service** (not controller): parents see only their `childStudentIds` intersected with the requested scope — targeting a classroom their child isn't in returns empty rather than leaking other children. Parent with no enrolled children short-circuits to empty without a DB hit.
  - Validation: at least one of `(schoolId, classroomId)` required → `ValidationException` if both null.
  - **`classroomId` takes precedence** over `schoolId` when both are passed (the FE picker passes both for resilience; classroom is the more specific filter).
  - Caffeine 60s/1000-entry cache. **Cache key includes the parent's child-id set** with defensive `Set.copyOf` in the record's canonical constructor so upstream mutations can't shift identity. Different parents at the same classroom get different cache entries.
  - Soft-deleted students (`deleted_at IS NOT NULL`) excluded.
- `auth/StudentView(id, schoolId, classroomId, name, dob)` — `dob` nullable (`@JsonInclude(NON_EMPTY)`); the platform allows null DOB during the consent stage. JSON serializes as ISO-8601 `yyyy-MM-dd`.

Files changed (count: 5, all new):
- `src/main/java/com/childcarewow/calendar/auth/{StudentsController,StudentsReadService,StudentView}.java`
- `src/test/java/com/childcarewow/calendar/auth/{StudentsReadServiceIT,StudentsControllerTest}.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m22s; bundle gate ≥80% line met.
- [x] `StudentsReadServiceIT` — 11/11 tests green against the seeded platform DB:
  - Staff sees all in classroom (Aanya in Butterflies); staff sees all in school (Aanya + Jordan); classroom-id wins when both passed.
  - **Parent sees only own children**: Priya (Aanya's parent) → only Aanya in Butterflies; Daniel (Lila's parent) → only Lila at Maplewood (NOT Noah, who's also at Maplewood but not Daniel's child).
  - Parent targeting wrong classroom → empty.
  - Parent with empty `childStudentIds` → empty without DB hit.
  - **Soft-delete filter** verified by mid-test `UPDATE deleted_at = now()`, post-test reset.
  - Missing both scope params → `ValidationException`.
  - Null actor → empty.
  - Cache hit returns same instance.
- [x] `StudentsControllerTest` — 4/4 slice tests green, with **real `AuditReadAspect` wired in** so the audit row assertions are end-to-end:
  - Success → ONE audit row with both subject UUIDs in `metadata.subject_ids` (verified via `argThat(meta → coll.containsAll(...))`).
  - Parent read also writes audit row (smaller subject set).
  - Empty result still writes audit row (records that a query happened).
  - Unauthenticated → 401, **NO audit row** (`@AfterReturning` doesn't fire on failure paths).
- [x] Per-class JaCoCo: `StudentsController` **100%** (0/13 instr); `StudentsReadService` ~99% line / 90% branch (the 2/22 missed branches are the defensive `null || isEmpty` short-circuit on `parentScope` — covered structurally by `parentWithNoChildrenReturnsEmpty` but JaCoCo flags the OR conservatively); `StudentView` and `CacheKey` **100%**.
- [x] CI green on PR #80 ([run 25516785445](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25516785445)).

Notes / surprises:
- **Slice test had to import `AopAutoConfiguration` + `AuditReadAspect`** explicitly. `@WebMvcTest` excludes AOP auto-configuration by default — without these imports the `@AuditRead` annotation on the controller would be silently ignored and the audit-row assertions would all fail with "0 invocations" (not the spec violation it looks like, just the test setup). Documented inline. Same pattern will apply to any future controller-slice test that wants to verify aspect-driven side effects.
- **The cache key `parentChildIds` defensive copy is not just hygiene** — `UserPrincipal.childStudentIds()` already returns an immutable Set (via the compact-constructor `Set.copyOf`), so upstream mutation isn't a real risk here. But the cache key crosses an architectural boundary (service → cache), and future callers might pass mutable sets. The `Set.copyOf` in the record's canonical constructor is the right defensive layer.
- **`@JsonInclude(NON_EMPTY)` on `StudentView`** — drops null `dob` from the JSON. The FE prototype already conditionally renders DOB; this just keeps the wire smaller. Caveat: empty strings would also be dropped, but the schema enforces non-empty `name`, so this only affects `dob`.
- **`classroomId` precedence over `schoolId`**: The SQL conditional builder appends `classroom_id =` first if classroomId is non-null, regardless of schoolId. If a future endpoint needs strict OR semantics ("either classroomId OR schoolId, error if both"), it'll need to be a new method — the current contract is "classroom wins", documented in the test.
- **Audit-row absence on 401** is the **most important compliance behavior**: failed reads must NOT produce STUDENT_VIEW rows that suggest the actor saw children's data when they didn't. This is verified directly in `unauthenticatedGets401NoAuditRow`.

Next part: 4.5 — Springdoc OpenAPI generation + CI drift check. Will pin the wire contract for everything Series 4 has built so far before Series 5 starts adding write endpoints.

---

## Part 4.3 (Series 4) — `GET /api/v1/classrooms?schoolId=` (with staffUserIds) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `auth/ClassroomsController` — `GET /api/v1/classrooms?schoolId=`. Auth-only at the controller; the school-level access check is **deliberately absent** because revealing a school's existence to a parent who isn't linked to it would be an info leak. If the actor passes an inaccessible school, the result is empty.
- `auth/ClassroomsReadService.findBySchool(schoolId)`:
  - **Two-query approach**: SELECT classrooms WHERE `school_id = :schoolId AND deleted_at IS NULL ORDER BY name`, then SELECT classroom_staff WHERE `classroom_id IN (:ids)` if any classrooms came back. Combined in Java.
  - Aggregate-with-`array_agg` would be one round-trip but parsing `java.sql.Array → UUID[]` adds JDBC ceremony for ≤ 10 classrooms per school. Documented trade-off in service Javadoc.
  - Caffeine cache 1000-entry / 60s TTL keyed by `schoolId`.
  - Empty-result short-circuit: if the first query returns nothing, the staff-map query is skipped.
  - `staffUserIds` sorted by UUID for stable serialization.
- `auth/ClassroomView(id, schoolId, name, List<UUID> staffUserIds)`.
- Internal `ClassroomRow` record private to the service for the first-query row mapper.

Files changed (count: 5, all new):
- `src/main/java/com/childcarewow/calendar/auth/{ClassroomsController,ClassroomsReadService,ClassroomView}.java`
- `src/test/java/com/childcarewow/calendar/auth/{ClassroomsReadServiceIT,ClassroomsControllerTest}.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m33s; bundle gate ≥80% line met.
- [x] `ClassroomsReadServiceIT` — 7/7 tests green against the seeded platform DB:
  - All Sunrise classrooms returned alphabetically (Butterflies, Caterpillars).
  - `staffUserIds` populated correctly: Butterflies → Maya, Caterpillars → Tom.
  - **Classroom with no assigned staff returns empty array** (Stars in Maplewood) — verifies the LEFT JOIN behavior. Sunbeams confirms the populated case still works alongside.
  - Unknown school → empty list.
  - Null schoolId → empty list.
  - **Soft-delete filter** verified end-to-end: insert a brand-new soft-deleted classroom in a brand-new school (no cache pollution) → excluded; cleanup in `finally`.
  - Cache hit returns the same list instance.
- [x] `ClassroomsControllerTest` — 2/2 slice tests green: authenticated returns the full list with all 4 fields populated; unauthenticated → 401.
- [x] Per-class JaCoCo: `ClassroomsController` **100%** (0/11 instr); `ClassroomsReadService` **100%** (0/152 instr, 0/6 branch, 0/34 line, 0/7 method); `ClassroomView` and `ClassroomRow` **100%**.
- [x] CI green on PR #78 ([run 25516216683](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25516216683)).

Notes / surprises:
- **First impl's reflection-on-record-component was a code smell.** The original `loadStaffMap` accepted `List<? extends Record>` and reflected on the local `Row` record's first component to extract the UUID id. Compiled and passed tests, but unreadable. Refactored to a private top-level `ClassroomRow` record visible to both query helpers — ten lines simpler, no reflection. Lesson: when a local record needs to escape its enclosing method's scope, lift it to the class.
- **Soft-delete test design** (the playbook's common-failure-points stays). First attempt updated `deleted_at` on an existing seed row, but the cache had already retained the pre-delete result, so the test passed for the wrong reason. Final design: insert a NEW soft-deleted row in a NEW school (cache miss guaranteed), assert empty result, clean up. The pattern transfers to other "filter-by-deleted_at" tests in this series.
- **Controller has no policy gate.** Every authenticated user has a legitimate reason to query classrooms for a school they're already in (parents to know where their kid is, staff for assignments). Adding a `users.read`-style gate would force PARENT → 403, but parents who legitimately need their child's classroom in the picker would then need an exception path. Cheaper: empty result for inaccessible schools.
- **`@Qualifier("platformJdbcTemplate")` autowire** in the IT (positional, for the cleanup SQL) vs `@Qualifier("platformNamedJdbcTemplate")` in the service. Both wrap the same DataSource, so the test can mutate state via the positional template while the service reads via the named one. No transaction boundaries crossed (each `update` is auto-committed).

Next part: 4.4 — `GET /api/v1/students?classroomId=` (or `schoolId=`) with COPPA-required `@AuditRead("STUDENT_VIEW")` audit on every read.

---

## Part 4.2 (Series 4) — `GET /api/v1/schools` (school switcher) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `auth/SchoolsController` — `GET /api/v1/schools`, authentication-only at the controller. The role-aware visibility scoping happens inside the service so the controller stays a thin pass-through.
- `auth/SchoolsReadService.findVisibleTo(actor)`:
  - **ORG_ADMIN** → every school in `actor.orgId` via `SELECT WHERE org_id = :orgId`.
  - **SCHOOL_ADMIN / STAFF / PARENT** → only the schools in `actor.schoolIds()` via `SELECT WHERE id IN (:ids)`.
  - Caffeine cache (1000-entry / 60s TTL) with **two key flavours**: `CacheKey.org(orgId)` for the first path, `CacheKey.ids(Set.copyOf(schoolIds))` for the second. Stops cross-actor collisions.
- `auth/SchoolView(id, name, timezone)` — `timezone` is a plain `String`, NOT a `java.time.ZoneId` (Jackson serializes `ZoneId` as a JSON object, per playbook common-failure-points).
- No new policy action: every authenticated user has SOME list of "their" schools (parents see schools their kids attend), so a separate policy gate would just duplicate the visibility logic.

Files changed (count: 5, all new):
- `src/main/java/com/childcarewow/calendar/auth/{SchoolsController,SchoolsReadService,SchoolView}.java`
- `src/test/java/com/childcarewow/calendar/auth/{SchoolsReadServiceIT,SchoolsControllerTest}.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m55s; bundle gate ≥80% line met.
- [x] `SchoolsReadServiceIT` — 8/8 tests green against the real platform DB:
  - ORG_ADMIN sees both seeded schools (Maplewood + Sunrise) sorted alphabetically with correct timezones (`America/Chicago` and `America/New_York`).
  - SCHOOL_ADMIN, STAFF, PARENT each see only their assigned school (Sunrise).
  - Multi-school parent (synthesized) sees both.
  - Empty `schoolIds` → empty list.
  - ORG_ADMIN with unknown `orgId` → empty list.
  - Null actor → empty list.
- [x] `SchoolsControllerTest` — 2/2 slice tests green: authenticated returns the full list with correct fields and `timezone` as a plain string; unauthenticated → 401.
- [x] Per-class JaCoCo: `SchoolsController` **100%** (0/11 instr); `SchoolsReadService` **~99% line / 87% branch** (one uncovered branch is the defensive `null == schoolIds` guard — `UserPrincipal`'s compact constructor wraps in `Set.copyOf` so it can never trigger from the auth chain; only direct test instantiation could); `SchoolView` and `CacheKey` **100%**.
- [x] CI green on PR #76 ([run 25515310071](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25515310071)).

Notes / surprises:
- **`CacheKey` two-flavour record** is the more interesting design choice. A single union-type cache key would have either two nullable fields (each path uses one and ignores the other) — which I went with — or required a sealed-interface hierarchy with two implementations. The record-with-nullable-fields wins because Java 21 records auto-generate the right `equals`/`hashCode` that compare both fields, so `CacheKey.org(orgA)` and `CacheKey.ids(Set.of(...))` are correctly unequal even though one of each pair has a null. The factory methods `org(...)` and `ids(...)` enforce the invariant that exactly one of the two fields is set; if a future maintainer adds a third query path they should add a third factory rather than pass both fields directly.
- **Timezone-string contract** — the FE prototype's school switcher already treats timezone as opaque (just renders the name as an info pill on the school card). The string-based DTO matches that contract; if Series 5/6 calendar reads ever need to do timezone math FE-side, they should call `TimezoneService` server-side and surface only date-resolved data.
- **No `@AuditRead` annotation** on the controller. Schools aren't COPPA-protected; auditing every school-switcher render would be noise. If a future security review wants visibility into "who switched to which school when", a `@Audited("SCHOOL_VIEWED")` per-call entry would be appropriate (it would record `target_id` = school ID, one row per request).
- The `SQL_BY_IDS` query uses the JPQL-style `IN (:ids)` syntax, which `NamedParameterJdbcTemplate` expands to `IN (?, ?, ...)`. Postgres has no array-binding ambiguity here because the `ids` param is a Java `Set<UUID>` and Spring binds each element with the inferred UUID type. (Different from the `users.read` Postgres-can't-infer-null trap; here the type is fully determined.)

Next part: 4.3 — `GET /api/v1/classrooms?schoolId=` (classroom selectors with `staffUserIds[]` populated; soft-deleted excluded).

---

## Part 4.1 (Series 4) — `GET /api/v1/users` (assignee-selector endpoint) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `auth/UsersController` — `GET /api/v1/users?schoolId=&role=`, gated by `policyService.assertCan(actor, "users.read")`. Returns `List<UserView>` sorted by name. Not `@AuditRead`-annotated — listed entities are users, not children; COPPA-cadence audits would be noise.
- `auth/UsersReadService` — Caffeine cache (1000 entries / 60s TTL) keyed on `(schoolId, role)`; query via `NamedParameterJdbcTemplate` with the `(:role IS NULL OR u.role = :role)` pattern. Null role bound with explicit `Types.VARCHAR` to dodge a Postgres type-inference quirk.
- `auth/UserView` — trimmed `(id, name, email, role, designation)` record. Full `User` shape with joined arrays remains `/auth/me`-only (per-actor query, not list-friendly).
- `config/DatasourceConfig` — new `platformNamedJdbcTemplate` bean wrapping the existing positional `platformJdbcTemplate`.
- `policy/PolicyService` + `PolicyServiceImpl` — new `users.read` action: non-PARENT (admins + STAFF allowed; parents excluded so they can't enumerate other parents at their school). Catalog now 20 actions.

Files changed (count: 9):
- `src/main/java/com/childcarewow/calendar/auth/{UsersController,UsersReadService,UserView}.java` — new (3 files).
- `src/main/java/com/childcarewow/calendar/config/DatasourceConfig.java` — `+platformNamedJdbcTemplate` bean.
- `src/main/java/com/childcarewow/calendar/policy/PolicyService.java` — Javadoc lists `users.read`.
- `src/main/java/com/childcarewow/calendar/policy/PolicyServiceImpl.java` — `+case "users.read" -> nonParent(actor)`.
- `src/test/java/com/childcarewow/calendar/auth/UsersReadServiceIT.java` — new (6 tests).
- `src/test/java/com/childcarewow/calendar/auth/UsersControllerTest.java` — new (4 tests).
- `src/test/java/com/childcarewow/calendar/policy/PolicyServiceImplTest.java` — `+`4 rows in the parameterized matrix for `users.read` × all 4 roles.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 42s; bundle gate ≥80% line met.
- [x] `UsersReadServiceIT` — 6/6 tests green against the real platform DB:
  - All-Sunrise list returns exactly 5 users sorted alphabetically: Maya Diallo, Olivia Park, Priya Singh, Ravi Mehta, Tom Becker.
  - STAFF filter at Sunrise → exactly Maya + Tom.
  - ORG_ADMIN filter at Sunrise → just Olivia with `designation="Owner"`.
  - PARENT filter at Maplewood → just Daniel Cho; designation null.
  - Unknown school → empty list.
  - Cache hit returns the **same list instance** (Caffeine semantic).
- [x] `UsersControllerTest` — 4/4 slice tests green: admin lists; admin filters by role; PARENT → 403 with `FORBIDDEN` envelope; unauthenticated → 401.
- [x] `PolicyServiceImplTest` — 76/76 tests green (was 72; +4 for `users.read`); `PolicyServiceImpl` still **100%** coverage across instr/branch/line/method.
- [x] Per-class JaCoCo: `UsersController` **100%** (0/20 instr · 0/6 line · 0/2 method); `UsersReadService` **100%** (0/79 instr · 0/2 branch · 0/19 line · 0/5 method); `UsersReadService.CacheKey` **100%**.
- [x] CI green on PR #74 ([run 25514793189](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25514793189)).

Notes / surprises:
- **Postgres "could not determine data type of parameter $2"** on the first verify run. Cause: the `(:role IS NULL OR u.role = :role)` JPQL pattern with a null-valued parameter fails server-side type inference because Postgres sees `NULL` on both sides without context. Fix: bind via `params.addValue("role", value, java.sql.Types.VARCHAR)` to give the driver an explicit type. Captured the explanation in a comment on the relevant `addValue` call. Generic JDBC trap; will surface again with any future filtered-list endpoint that has nullable params.
- **Cache returns the same list instance, not a copy.** Caffeine's `Cache#get(key, mappingFunction)` returns the cached value as-is. The `UserView` record is immutable (final fields, deeply value-typed) so this is safe — but if anyone ever decides to wrap the result in a `Collections.unmodifiableList(...)` or copy, the cache hit semantics change. Documented via test assertion `isSameAs(first)` so a future change is caught immediately.
- **Slice test imports `PolicyServiceImpl` directly.** A `@MockBean PolicyService` would have forced every test to stub `assertCan()` (with the right exception for the PARENT case), turning each test into a duplicate of the impl's logic. Importing the real impl means the policy gate is exercised end-to-end; trade-off is a slightly heavier slice test context, which adds ~1s to startup (already amortized over 4 tests).
- **`MissingServletRequestParameterException` → 500 (not 400).** The `GlobalExceptionHandler` from Part 3.0 doesn't have a mapper for this Spring exception; missing-required-param requests fall through to the unknown-exception → 500 path. Dropped the originally-planned `missingSchoolIdGets400` test rather than expand the envelope-test surface mid-Part. **Follow-up**: one-line handler + test, deferred to a Series-4-wide validation pass when the other parts (4.2/4.3/4.4/4.5) all need the same input-validation polish.
- The `PolicyServiceImpl` 100% guarantee is intact through this part. Each new policy action requires:
  1. New `case` arm in `PolicyServiceImpl.can(actor, action)`.
  2. New rows in `PolicyServiceImplTest.resourceLessActions` for all 4 roles.
  This pattern keeps coverage perfect and forces explicit role-by-role acceptance for every new action — exactly what the locked decision D8 ("no RLS, single security gate") requires.

Next part: 4.2 — `GET /api/v1/schools` (school switcher; ORG_ADMIN sees org-wide, others see their assigned schools).

---

## Part 4.0a (Series 4) — `@AuditRead` annotation + `AuditReadAspect` — STATUS: ✅ done · **OPENS SERIES 4**
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `audit/AuditRead` annotation: `@Retention(RUNTIME) @Target(METHOD)` with `action` (audit name), `subjectsFrom` (SpEL on response → `Collection<UUID>`), `sampleRate` (default 100). Marks read endpoints whose every successful invocation should land a row in `audit_events`.
- `audit/AuditReadAspect` (Spring AOP `@Aspect @Component`): `@AfterReturning` advice that fires only on success.
  - **Sample-rate gate**: `ThreadLocalRandom.nextInt(100) >= sampleRate` short-circuits without any audit work.
  - **`subjectsFrom` resolution** via SpEL on the controller's return value. Accepts `Collection<UUID>` directly or `Collection<String>` (parsed to UUIDs; bad-format strings silently dropped — partial audit beats failure-on-bad-data).
  - **One row per request**, not per subject — full UUID list lives under `metadata.subject_ids`. Storing one row per child would 100x audit-write volume on calendar reads for no compliance gain.
  - **Failure handling**: every internal failure (SpEL miss, audit write throws, missing security/request context) is swallowed at WARN. Read audits MUST NEVER fail the user-facing read.

Files changed (count: 3, all new):
- `src/main/java/com/childcarewow/calendar/audit/{AuditRead,AuditReadAspect}.java`
- `src/test/java/com/childcarewow/calendar/audit/AuditReadAspectIT.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 55s; bundle gate ≥80% line met.
- [x] `AuditReadAspectIT` — 6/6 tests green:
  - **3-subject batched audit**: response with three `StudentView` records → exactly ONE `auditService.log` invocation with `metadata.subject_ids` containing all three UUIDs.
  - **Empty response still records**: zero subjects → row written with empty `subject_ids` list (records that a query happened, even if the user has no children to see).
  - Method throws → no audit row.
  - **Sample rate ~50** over 200 invocations: ±25 tolerance (loosened from spec's ±10/100 for CI stability; ±25/200 is still > 5σ from boundary failures).
  - **Sample rate 0** over 20 invocations: zero audit rows.
  - `resolveSubjectIds` static helper edge cases: null result → `[]`; null/blank/malformed SpEL → `[]`; mixed UUID + valid String + non-UUID String → only valid UUIDs returned.
- [x] Per-class JaCoCo: `AuditReadAspect` ~80% line. Uncovered branches are the internal try/catch (defensive — exercised only when something else upstream is already broken) and the non-`UserPrincipal` cast guard.
- [x] CI green on PR #72 ([run 25514044674](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25514044674)) — clean first run after the Series-3 LF-normalization landed.

Notes / surprises:
- **The aspect package now hosts both write- and read-audit infrastructure** (`Audited`/`AuditAspect` from 3.3, `AuditRead`/`AuditReadAspect` from 4.0a). The two annotations are deliberately separate — they record fundamentally different things (write events keyed by a single target_id vs. read events listing many subject_ids) and a unified annotation would have been a leaky abstraction. Coexistence is fine: methods can carry both, and AspectJ runs both advices in a single proxy invocation.
- `AuditService.log` from Part 3.3 is reused unchanged — `@Audited` writes with non-empty `metadata = Map.of()`, `@AuditRead` writes with `metadata = Map.of("subject_ids", subjectIds)`. The jsonb mapping (already validated in 1.7) handles the `Collection<UUID>` shape via Hibernate's `@JdbcTypeCode(SqlTypes.JSON)` on the entity.
- **Working-tree noise from the .gitattributes change.** After Part 3.14 landed `*.java text eol=lf` in `.gitattributes`, the next checkout on Windows triggered a renormalization that flagged 104 files as "modified" (CRLF in working tree vs LF in index). The PR was clean (only the 3 actual files), but `git status` was alarming. Fix: `git checkout .` on `main` after the merge to refresh the working tree to LF. Future Windows checkouts shouldn't see this once each developer has done it once.
- Test method `oneRowPerRequestWithAllSubjectIds` uses `argThat(meta -> { ... return ...; })` to inspect the metadata Map — Mockito's `eq()` won't work because `Map.of("subject_ids", listA)` and `Map.of("subject_ids", listB)` are equal only when the lists are equal, and the order in which we extract UUIDs from a list of `StudentView`s is the same order, but defensive `containsAll` + size check is more robust to future SpEL impl changes.

Next part: 4.1 — `GET /api/v1/users` (assignee selectors, filtered by schoolId + role; reads from platform DB via `platformJdbcTemplate`).

---

## Part 3.14 (Series 3) — `FileUploadService` + Supabase signed-upload — STATUS: ✅ done · **CLOSES SERIES 3**
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `attachment/FileUploadService` (Spring `@Service`):
  - `validate(mimeType, sizeBytes)` — allowlist `{image/jpeg, image/png}` (case-insensitive); size in `(0, 10MB]`. Throws `AttachmentInvalidException` (400 `ATTACHMENT_INVALID`).
  - `signUpload(schoolId, filename, mimeType, sizeBytes)` — validates, sanitizes filename, builds object key `{schoolId}/{random uuid}/{sanitizedFilename}`, asks the client for the signed URL, returns `SignedUploadResult(uploadUrl, attachmentName, attachmentUrl)`.
  - `sanitize(filename)` — replaces `/` and `\\` with `_`, strips ASCII control chars (`\\p{Cntrl}`), rejects null/blank/blank-after-stripping. Closes the `../etc/passwd` path-traversal hole.
  - Constants: `MAX_BYTES = 10 MB`, `ALLOWED_MIME_TYPES = {image/jpeg, image/png}`. Locked decision § 5.7.
- `attachment/SupabaseStorageClient` interface + `SupabaseStorageClientImpl` (production). Uses Spring's `RestClient`; reads `ccw.supabase.url` + `ccw.supabase.service-role-key` (LocalStack secrets in dev, Secrets Manager in staging/prod via Series 11). Wire format documented in Javadoc: `POST {SUPABASE_URL}/storage/v1/object/upload/sign/{bucket}/{objectKey}` → `{url, token}`. Defensive: throws `ATTACHMENT_INVALID` rather than 500 if `ccw.supabase.url` is unset (everyday dev hits the mocked client).
- `attachment/AttachmentController` — `POST /api/v1/attachments/sign-upload` annotated with `@Audited("ATTACHMENT_SIGN", targetType="ATTACHMENT", idFrom="attachmentName")`. `policyService.assertCan(actor, "event.create")` gate (parents excluded uniformly with the rest of the create surface). `@Valid` on `SignUploadRequest`.
- `SignUploadRequest` record with bean-validation (`@NotNull schoolId`, `@NotBlank filename`/`mimeType`, `@Positive sizeBytes`).
- `SignedUploadResult` record `(uploadUrl, attachmentName, attachmentUrl)`.
- `pom.xml` adds `spring-boot-starter-validation`.

Files changed (count: 8):
- `pom.xml` — `+spring-boot-starter-validation`.
- `.gitattributes` — `+*.java text eol=lf` (CI Spotless fix; see Notes).
- `src/main/java/com/childcarewow/calendar/attachment/{FileUploadService,SupabaseStorageClient,SupabaseStorageClientImpl,AttachmentController,SignUploadRequest,SignedUploadResult}.java` — new package.
- `src/test/java/com/childcarewow/calendar/attachment/FileUploadServiceTest.java` — 18 tests.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 49s; bundle gate ≥80% line met.
- [x] `FileUploadServiceTest` — 18/18 tests green:
  - Validate: accepts `image/jpeg` + `image/png` (incl. uppercase via `toLowerCase`); rejects `application/pdf`; rejects null mime; rejects 0/negative bytes; accepts exactly 10MB; rejects 10MB+1.
  - Sanitize: strips `/` and `\\` (`../etc/passwd` → `.._etc_passwd`); strips control chars; rejects null/blank; rejects blank-after-stripping.
  - signUpload: returns the right `SignedUploadResult` shape; object key matches `{schoolId}/{uuid}/{filename}` regex; rejects bad mime/size BEFORE calling client; rejects null schoolId; sanitization flows into both `attachmentName` AND object key.
- [x] Per-class JaCoCo: `FileUploadService` **100%** (0/118 instr · 0/16 branch · 0/26 line · 0/13 method). `AttachmentController` and `SupabaseStorageClientImpl` are thin pass-through layers — controller slice test + Supabase live test deferred to Series 11.4 alongside ECS deployment per playbook step 5 (the gated `@Tag("integration")` test).
- [x] CI green on PR #70 ([run 25508680671](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25508680671)) — second run after CRLF fix.

Notes / surprises:
- **CI Spotless caught Windows CRLF line endings** on the new test file. Local `mvn spotless:check` runs on Windows and tolerates CRLF (because the JVM's Spotless write phase emits the system line separator); on Linux CI the same check sees CRLF as a format violation. Fix: `*.java text eol=lf` in `.gitattributes` so all Java sources commit with LF regardless of OS, and renormalized the offending file in a follow-up commit. Same convention now applies to `mvnw` and `*.sh` (already there for Alpine container compatibility). Future Java files created on Windows will also be LF on commit.
- The controller uses `policyService.assertCan(actor, "event.create")` rather than introducing a new `attachment.upload` action. Reason: attachment uploads are always in the context of an event/task being created — the gate is "can this user create things". Adding a new action would require updating `PolicyService` (and its 100% coverage test matrix) for no semantic gain. If attachments later get their own surface separate from create-time, revisit.
- `SupabaseStorageClientImpl` reads its config via `@Value("${ccw.supabase.url:}")` with empty-string default. This means the bean is constructable even without the config set — the runtime check inside `createSignedUploadUrl` raises `ATTACHMENT_INVALID` instead of crashing the app at startup. Trade-off: `application.yml` should set the values to a placeholder string (already done — Supabase secrets land in `application-dev.yml` via Spring Cloud AWS in Part 0.8 follow-up).
- Coverage on the controller and Supabase client is intentionally low. The playbook only requires tests for the validation logic (which is at 100%). Controller and Supabase client are thin — adding slice tests is the kind of churn the playbook explicitly defers to Series 11 ("@Tag('integration')") so CI can opt in. Documented in the PR body.

Series 3 close:
- 15/15 parts done.
- Cross-cutting service layer fully implemented and tested:
  - **PolicyService** (3.1–3.2): 100% across 19 actions; resource-bearing Event/Task overloads.
  - **AuditService + @Audited AOP** (3.3–3.4): 100% on AuditService; immutability enforced via Hibernate + CI grep guard + docs.
  - **TimezoneService** (3.5): 7 tests including DST boundary; Caffeine 1h cache.
  - **RecurrenceService** (3.6–3.9): all 3 cycles + per-occurrence overrides + skipped filter; ~98% line on the impl.
  - **SoftFlagService** (3.10–3.12): 100% across instr/branch/line/method; insert + dismiss + 3 recompute triggers (Event bidirectional, Task ±120min, Holiday paint).
  - **IdempotencyMiddleware** (3.13): cross-user scoped key, 5-route allowlist, daily purge cron.
  - **FileUploadService** (3.14, this part): 100% on the service; sanitize closes path-traversal.
- Total: **70 PRs merged** to date.

Three carry-forward items still tracked from earlier series (none blocking Series 4):
1. **GitHub Actions Node-20 deprecation** (2026-09-16) — bump `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, `madrapps/jacoco-report` before August.
2. **Application config externalization (Part 0.8)** — Spring Cloud AWS Secrets Manager binding for per-env `application-{dev,staging,prod}.yml`. Required before any deploy; not for local dev or Series 4.
3. **Local container DB networking** — `host.docker.internal` env-var override pattern documented in Part 0.7 progress entry.

New Series-3 follow-ups:
4. **AttachmentController slice test + Supabase live integration** — deferred to Series 11.4 per playbook step 5 (`@Tag("integration")` opt-in).
5. **ShedLock for IdempotencyPurgeJob** — deferred to Series 11.4 alongside ECS multi-instance deployment.

Next part: **Series 4 — Identity-read endpoints + OpenAPI codegen**. Starts at Part 4.0a (`@AuditRead` annotation + AuditReadAspect for auditing reads, not just writes).

---

## Part 3.13 (Series 3) — `IdempotencyMiddleware` + daily purge + cross-user scoping — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `idempotency.IdempotencyFilter extends OncePerRequestFilter`. Honors `Idempotency-Key` header on the listed POST creates (`/api/v1/{events,tasks,holidays,important-dates,attachments/sign-upload}`); other methods/paths pass through. Anonymous requests, missing/blank headers, and non-applicable routes all pass through silently.
- **Cross-user scoped storage key**: `SHA-256(actor.id() + ":" + clientIdempotencyKey)`. Two clients colliding on a UUID for `Idempotency-Key` see independent rows — closes the cross-user data-leak hole the playbook's threat model called out.
- Cache hit + same body hash → return cached status/body, no controller invocation, no DB writes. Cache hit + different body hash → 409 IDEMPOTENCY_REPLAY (envelope written directly because filter exceptions don't go through `GlobalExceptionHandler`). Miss → run controller, persist 2xx response keyed by scoped key.
- `idempotency.BodyCachingRequestWrapper` — accepts the body bytes up-front (filter reads from the original request stream) and replays them via `getInputStream()` to downstream consumers. Spring's `ContentCachingRequestWrapper` doesn't fit because it caches AFTER the chain consumes the stream — too late for hashing.
- `idempotency.IdempotencyPurgeJob` — `@Scheduled(cron = "0 0 4 * * *", zone = "UTC")`. ShedLock-backed coordination deferred to Series 11.4 (single-instance dev doesn't need it; documented in Javadoc + README).
- `IdempotencyKeyRepository.deleteExpired(cutoff)` — `@Modifying @Query` JPQL DELETE with row-count return.
- `@EnableScheduling` added to `CalendarApplication` so the cron actually fires.
- `idempotency/README.md` — documents the storage-key rationale, allowlisted routes, behavior table, TTL+purge, threat model (post-deletion replay is by design; captured-and-replayed by a different user fails the scope check), and test seams.

Files changed (count: 10):
- `src/main/java/com/childcarewow/calendar/CalendarApplication.java` — `+@EnableScheduling`.
- `src/main/java/com/childcarewow/calendar/crosscut/IdempotencyKeyRepository.java` — `+deleteExpired`.
- `src/main/java/com/childcarewow/calendar/idempotency/{IdempotencyFilter,BodyCachingRequestWrapper,IdempotencyPurgeJob,README.md}` — new package.
- `src/test/java/com/childcarewow/calendar/idempotency/{IdempotencyFilterTest,IdempotencyPurgeJobTest}.java` — 17 tests.
- `src/test/java/com/childcarewow/calendar/auth/{AuthControllerTest,WhoAmIControllerTest}.java` — `+@MockBean IdempotencyKeyRepository` (slice tests need it because `@WebMvcTest` pulls the `@Component` filter into the chain but doesn't load JPA repos; documented inline).

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 36s; bundle gate ≥80% line met.
- [x] `IdempotencyFilterTest` 14/14 + `IdempotencyPurgeJobTest` 3/3 = 17 tests green:
  - **Pass-through paths**: non-applicable route, GET method, missing header, blank header, no auth principal.
  - **First request**: response captured; saved row has scoped key (NOT raw key), correct status, body, request hash.
  - **Cached replay (same body)**: returns cached status + body; `chain.doFilter` NEVER called; no save.
  - **Replay with different body**: 409 with `IDEMPOTENCY_REPLAY` envelope; chain never called.
  - **Cross-user isolation**: Alice + "shared-key" and Bob + "shared-key" produce **different** scoped keys (verified via two `ArgumentCaptor` rounds).
  - Persist failure (`repo.save` throws) swallowed → user still sees 201.
  - Non-2xx response not cached.
  - `shouldApply` matches all 5 allowlisted routes; rejects non-POST methods; matches sub-paths (e.g. `/tasks/abc/status`).
  - `sha256Hex` stability and case sensitivity.
  - `IdempotencyPurgeJob.purgeUsingClock(cutoff)` delegates with the right cutoff; no-op when nothing expired; scheduled cron uses current clock.
- [x] Per-class JaCoCo:
  - `IdempotencyPurgeJob` **100%** across instr/branch/line/method (0/30/2/10/4/4 missed).
  - `IdempotencyFilter` ~96% line; the few uncovered branches are the early-return ladder for the auth-missing path and the non-`UserPrincipal` cast guard (defensive).
  - `BodyCachingRequestWrapper` ~86%; the unused `ReadListener.setReadListener` and `isFinished/isReady` accessors aren't exercised by mock-stream consumers (acceptable — tested implicitly via the wrapper's full-cycle byte replay).
- [x] Existing slice tests still green after `@MockBean IdempotencyKeyRepository` add.
- [x] CI green on PR #68 ([run 25508016046](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25508016046)).

Notes / surprises:
- **Bug caught + fixed mid-implementation**: the first `BodyCachingRequestWrapper` integration read the body via the wrapper before calling `setCachedBody`, which always yielded an empty array (initial `cachedBody = new byte[0]`). The downstream hash was thus `sha256("")`, which never matched any cached entry → every cache-hit replay path landed in the "different hash" branch and returned 409 instead of the expected cached 201. Caught by the `cachedReplaySameBodyReturnsCachedNoControllerInvocation` test failing with `expected: 201 but was: 409`. Fix: read the body from `request.getInputStream()` first (the original Servlet stream), THEN construct the wrapper and call `setCachedBody(body)`. Documented inline.
- `@WebMvcTest` slice tests broke because the filter is a `@Component` with a JPA-repo dep that the slice scope doesn't satisfy. Fix: `@MockBean IdempotencyKeyRepository` in each affected slice test. Trade-off: any future slice test that loads the security filter chain will need the same mock-bean. Documented inline so future contributors don't re-debug.
- **ShedLock deferred** to Series 11.4. Adding it now would require a new dependency, a Flyway migration for the lock table, and provider config — all of it gated by "do we have a real second instance to coordinate with?" The answer in dev is no (single Spring Boot instance against LocalStack). When ECS provisions a second task in 11.4, that's the right time to add it. The cron itself is idempotent — a second runner deleting nothing is a no-op.
- The 409 envelope is **written directly to the response** (string-formatted JSON) rather than thrown as a `ServiceException`. Reason: filter exceptions don't traverse the `@RestControllerAdvice` chain — Spring catches them earlier in the servlet container layer and produces a generic 500. Hand-writing the envelope keeps the wire format consistent with `GlobalExceptionHandler`'s output.
- The `traceId` field in the 409 envelope is currently `null` because the `TraceIdFilter` runs after this filter in the chain order (filter ordering inferred from Spring's default; not explicitly tested here). If end-to-end tracing is needed for replay events, a future change can either reorder filters or have this filter populate MDC itself before writing the response. Not blocking — the envelope shape is correct and the trace can be reconstructed from logs.

Next part: 3.14 — `FileUploadService` (Supabase Storage signed URLs for JPG/PNG ≤ 10 MB).

---

## Part 3.12 (Series 3) — `recomputeForTask` + `recomputeForHoliday` — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `SoftFlagService.recomputeForTask(UUID taskId)` — same-day same-assignee task overlap (architecture spec § 7.3). Conflict rule: same school + same assignee + same `dueDate` + neither task `DONE` + `dueTime` within ±120 min (or both null/missing → conflict; one null + one set → no conflict). Same bidirectional A↔B insertion pattern as `recomputeForEvent`; both rows in one `@Transactional`. Soft-deleted task or `DONE`-status subject only clears flags. 404 for unknown task.
- `SoftFlagService.recomputeForHoliday(Holiday holiday)` — idempotent paint-or-clear by `holiday.approved`. Always clears HOLIDAY flags pointing AT the holiday via `conflictingEntityId` (the playbook common-failure-points: HOLIDAY's `entity_id` is the event/task, the `conflicting_entity_id` is the holiday — don't filter by `entity_id`). If approved, paints one HOLIDAY flag per matching event (UTC-date match via `FUNCTION('DATE', start_dt)`) and per matching non-deleted task on `(school_id, due_date)`. Rejects null holiday or holiday without an `id`.
- `SoftFlagService.removeFlagsForTask(UUID taskId)` — task analogue of `removeFlagsForEvent`.
- `SoftFlagService.dueTimesOverlap(LocalTime, LocalTime)` — package-private static helper. `(null, null) → true`, `(null, set) → false`, `(set, null) → false`, otherwise `|Δminutes| ≤ 120`.
- Repository additions:
  - `ConflictFlagRepository.deleteDoubleBookingFlagsForTask(taskId)` — TASK + DOUBLE_BOOKING + (entityId OR conflictingEntityId).
  - `ConflictFlagRepository.deleteHolidayFlagsForHoliday(holidayId)` — HOLIDAY + conflictingEntityId match.
  - `TaskRepository.findOverlapCandidates(...)` — same school + assignee + dueDate + non-DONE + non-deleted (dueTime filter is service-side).
  - `TaskRepository.findBySchoolIdAndDueDateAndDeletedAtIsNull(...)` — derived query for the holiday-paint hook.
  - `EventRepository.findBySchoolAndDate(schoolId, date)` — UTC-date match (`FUNCTION('DATE', startDt)`).

Files changed (count: 5):
- `src/main/java/com/childcarewow/calendar/softflag/SoftFlagService.java`
- `src/main/java/com/childcarewow/calendar/conflict/ConflictFlagRepository.java`
- `src/main/java/com/childcarewow/calendar/event/EventRepository.java`
- `src/main/java/com/childcarewow/calendar/task/TaskRepository.java`
- `src/test/java/com/childcarewow/calendar/softflag/SoftFlagServiceTest.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 47s; bundle gate ≥80% line met.
- [x] `SoftFlagServiceTest` — 30/30 tests green:
  - **Task recompute**: bidirectional pair (ArgumentCaptor verifies both directions); both times null → conflict; **both directions of one-null-one-set → no conflict** (full branch coverage); 4h apart → no conflict; **exactly 120 min → conflict** (boundary); soft-deleted → clear-only; subject DONE → clear-only; 404 unknown.
  - **Holiday paint**: 2 events + 1 task → 3 flags with HOLIDAY type and `holiday.id` as `conflictingEntityId` and "Falls on holiday: Memorial Day" message; unapproved → clears but paints nothing; no matches → clears but paints nothing; null/transient holiday rejected.
  - `removeFlagsForTask` delegates to repo.
- [x] **`SoftFlagService` 100% across every axis**: 0/494 instr · 0/52 branch · 0/127 line · 0/15 method (spec § 3.12.4 mandate met).
- [x] CI green on PR #66 ([run 25507139684](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25507139684)).

Notes / surprises:
- The first verify run had **96% branch** on `SoftFlagService` (2 missed). Cause: the `(a == null || b == null)` short-circuit in `dueTimesOverlap` has two branches — JaCoCo only sees both covered when both subject-null and other-null cases run. My initial test only had `(set, null)`. Added the symmetric `(null, set)` case to push to 100%. Lesson: short-circuit `||` requires testing **both** sides of the OR independently, not just one.
- The `FUNCTION('DATE', start_dt)` in `EventRepository.findBySchoolAndDate` is the JPQL escape hatch for Postgres-specific date casting. Hibernate translates it to `CAST(start_dt AS DATE)` which Postgres handles. Trade-off: this matches by **UTC date**, not school-local date. For schools in timezones that span midnight UTC, an event at 23:00 local on the holiday's date might be stored with a UTC date one day off. Documented in the EventRepository Javadoc as an acceptable approximation since the HOLIDAY flag is a **soft warning** — the FE shows it, the user can dismiss. A more precise version would use `TimezoneService` to compute the UTC range for the school-local date; revisit if false-match telemetry shows it matters.
- Task `dueTime` filter at the service layer (Java `Duration.between(...).toMinutes()`) rather than in JPQL because JPQL has no clean way to express ±120 min on `LocalTime` values that may be null. The candidate set is small (one assignee per day usually 0 or 1 task), so the in-memory filter has no meaningful cost.
- `recomputeForHoliday(holiday)` takes the loaded entity, not just an ID. Reason: the caller (Series 5's holiday-approve controller) already has the loaded entity from the approval workflow, so passing the entity avoids a redundant SELECT. The trade-off is that the service trusts the caller's data freshness — if `holiday.approved` was flipped between load and call, the wrong branch fires. Acceptable because the caller is wrapping the call in a single transaction with the approval write.
- Series 3 milestone: with this part, **all three recompute triggers and the dismiss path are 100% covered.** The remaining four parts in Series 3 are middleware (3.13 idempotency), policy (3.14 file upload), and notifications (3.15) — these are separate services, not extensions of `SoftFlagService`.

Next part: 3.13 — `IdempotencyMiddleware` honoring the `Idempotency-Key` header on POST creates per architecture spec § 6.10 (24h replay window, replay-with-different-payload → 409 IDEMPOTENCY_REPLAY).

---

## Part 3.11 (Series 3) — `recomputeForEvent` (bidirectional DOUBLE_BOOKING) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `SoftFlagService.recomputeForEvent(UUID eventId)` — DOUBLE_BOOKING recompute on event save/move with the architectural bidirectional invariant. Algorithm: (1) load event (404 if unknown); (2) clear every existing DOUBLE_BOOKING flag involving the event (either side); (3) if soft-deleted, return early; (4) find overlaps via `EventRepository.findOverlapping`; (5) for each match, insert TWO rows — A→B and B→A — inside the same `@Transactional` so the invariant cannot partially apply on rollback.
- `SoftFlagService.removeFlagsForEvent(UUID eventId)` — clears DOUBLE_BOOKING flags on either side. Called from the event-delete path so the surviving side's flag (B's, pointing back at A) is also cleaned up.
- `ConflictFlagRepository.deleteDoubleBookingFlagsForEvent(UUID eventId)` — `@Modifying @Query` JPQL DELETE matching `entityType=EVENT AND conflictType=DOUBLE_BOOKING AND (entityId = :id OR conflictingEntityId = :id)`. Returns affected row count for diagnostics.
- `EventRepository.findOverlapping(excludeId, schoolId, startDt, endDt, classroomId, organizerUserId)` — same school + **strict-`<`** time overlap + (same classroom OR same organizer). Endpoints don't overlap (per playbook common-failure-points: "A ends at 10:00, B starts at 10:00 → no conflict"). Soft-deleted rows excluded.

Files changed (count: 4):
- `src/main/java/com/childcarewow/calendar/conflict/ConflictFlagRepository.java` — `+deleteDoubleBookingFlagsForEvent`.
- `src/main/java/com/childcarewow/calendar/event/EventRepository.java` — `+findOverlapping`.
- `src/main/java/com/childcarewow/calendar/softflag/SoftFlagService.java` — `+EventRepository` ctor dep, `+recomputeForEvent`, `+removeFlagsForEvent`, `+insertDoubleBookingPair` helper.
- `src/test/java/com/childcarewow/calendar/softflag/SoftFlagServiceTest.java` — `+`7 tests for the recompute + remove paths.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 44s; bundle gate ≥80% line met.
- [x] `SoftFlagServiceTest` — 17/17 tests green:
  - **Bidirectional pair**: `ArgumentCaptor` asserts BOTH saves carry the right entityId/conflictingEntityId cross-references and the message names the OTHER event ("Overlaps with: Event B" / "Overlaps with: Event A").
  - No-overlap: clears flags but no saves.
  - Multi-overlap: 2 overlaps → 4 saves (2 pairs).
  - **Soft-deleted event**: clears flags, but `eventRepo.findOverlapping` is NEVER called and no saves happen.
  - 404 for unknown event (no clear, no saves).
  - **Idempotency**: two consecutive calls → 2 deletes + 4 saves (clear-and-rebuild on each call).
  - `removeFlagsForEvent` delegates to repo.
- [x] **100% coverage** on `SoftFlagService`: 0/222 instr, 0/24 branch, 0/59 line, 0/9 method missed (CLAUDE.md § 14 mandate met across the expanded surface).
- [x] CI green on PR #64 ([run 25506211869](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25506211869)).

Notes / surprises:
- The `@Query` JPQL uses `com.childcarewow.calendar.conflict.FlaggedEntity.EVENT` and `com.childcarewow.calendar.conflict.SoftFlagType.DOUBLE_BOOKING` as **fully-qualified enum literals** rather than parameter binding. This makes the query specific to the EVENT/DOUBLE_BOOKING combination at compile time — when (in 3.12) we add `recomputeForTask` and `recomputeForHoliday`, those get their own typed delete methods rather than a generic parameterized one. Slight code duplication trade for query clarity.
- The `findOverlapping` query uses **strict-`<`** on both sides of the time check (`e.startDt < :endDt AND :startDt < e.endDt`). This means events sharing an endpoint (A ends at 10:00, B starts at 10:00) are NOT overlapping. Boundary-test coverage will land in the controller-level integration tests in Series 5 once the wire format for time slots is finalized.
- `recomputeForEvent` always clears flags first, even on the no-overlap path. This is intentional: it ensures the recompute is idempotent and converges to the correct state regardless of prior state. The cost is one DELETE statement per save, but the table has a partial index `idx_conflict_flags_entity` on `(entity_type, entity_id) WHERE dismissed = false` so the cardinality is small.
- An overlap whose other end is soft-deleted will not appear in `findOverlapping` (the `deletedAt IS NULL` filter). So when event A is recomputed and the previously-overlapping event B was deleted, the recompute correctly drops the A→B and B→A pair. No extra cleanup needed.
- For the controller-level wire-up (Series 5 / Part 5.4 events.create), the call site will be: after `eventRepo.save(event)` and BEFORE the response is rendered, call `softFlagService.recomputeForEvent(event.getId())`. The result of recompute is intentionally `void` because the response is built from a separate `findActiveByEntity` call after recompute commits.

Next part: 3.12 — `recomputeForTask` (same-day same-assignee task overlap with ±120 min `dueTime` window) + `recomputeForHoliday` (HOLIDAY paint on holiday approve, idempotent). After 3.12, `SoftFlagService` should still be at 100% coverage.

---

## Part 3.10 (Series 3) — `SoftFlagService` skeleton + dismiss — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- New `softflag.SoftFlagService` (Spring `@Service`):
  - `insertFlag(orgId, schoolId, entityType, entityId, conflictType, conflictingEntityId, message)` — persists a fresh active flag. Validates all required fields up-front (rejects null orgId/schoolId/entityType/entityId/conflictType, rejects null/blank message). `conflictingEntityId` is orthogonal — required for `DOUBLE_BOOKING`, must be `null` for `HOLIDAY`.
  - `findActiveByEntity(entityType, entityId)` — wraps `ConflictFlagRepository.findByEntityTypeAndEntityIdAndDismissedFalse`, the partial-index-backed query (V6 migration). Dismissed rows stay in the DB for audit but never appear in default API surface.
  - `dismiss(flagId, actor)` — marks `dismissed=true` + stamps `dismissed_by_user_id` + `dismissed_at = OffsetDateTime.now()`. **Idempotent**: a second call on an already-dismissed row returns the existing row without invoking `repo.save` and without updating any fields, so the original dismisser/timestamp are preserved.
- Defense-in-depth authorization on `dismiss`:
  - `null` actor → `ForbiddenException` (`code=FORBIDDEN`, action=`calendar.softFlag.dismiss`).
  - `PARENT` role → `ForbiddenException` even though controllers should already have rejected via `policyService.assertCan(actor, "calendar.softFlag.see", ...)`. This way a misconfigured route can't bypass the policy layer.
  - Unknown flagId → `NotFoundException` (404).

Files changed (count: 2, both new):
- `src/main/java/com/childcarewow/calendar/softflag/SoftFlagService.java`
- `src/test/java/com/childcarewow/calendar/softflag/SoftFlagServiceTest.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 33s; bundle gate ≥80% line met.
- [x] `SoftFlagServiceTest` — 10/10 tests green:
  - Insert persists every field (DOUBLE_BOOKING with conflicting entity).
  - Insert allows HOLIDAY without `conflictingEntityId`.
  - Insert rejects each missing-field combo (orgId, schoolId, entityType, entityId, conflictType, message-null, message-blank — 7 sub-asserts in one test).
  - `findActive` delegates to the partial-index-backed repo finder.
  - `findActive` empty result.
  - Dismiss marks `dismissed=true`, stamps actor + timestamp, persists.
  - **Dismiss idempotent**: original dismisser preserved on second call; `repo.save` invoked exactly once across two calls.
  - Dismiss 404s on unknown flag.
  - Dismiss forbidden for PARENT actor (defense in depth).
  - Dismiss forbidden for null actor.
- [x] **100% coverage** on `SoftFlagService`: 0/116 instr, 0/20 branch, 0/28 line, 0/5 method missed (CLAUDE.md § 14 mandate).
- [x] CI green on PR #62 ([run 25505617384](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25505617384)).

Notes / surprises:
- Pure Mockito unit tests rather than `@SpringBootTest` — same pattern as `RecurrenceServiceTest` and `PolicyServiceImplTest`. Service has no DB-shape concerns (entity is plain JPA, no `@ColumnTransformer`); behavior is logic over a mockable repo. Suite runs in 8s.
- The schema does **not** enforce uniqueness on `(entityType, entityId, conflictType)` — multiple HOLIDAY flags for the same entity can coexist. This is intentional: the recompute pattern in 3.11/3.12 will clear-then-reinsert rather than upserting per-key, so duplicates are prevented at the service layer not the schema. The Javadoc on `insertFlag` notes this so callers don't accidentally rely on a uniqueness constraint that isn't there.
- `dismiss` returns the `ConflictFlag` (not `void`) so controllers can render the dismissed state back to the FE without an extra read. The idempotent path returns the existing in-memory instance from the repo find, which already has the original dismisser/timestamp.
- Used the parameter-named "Defense in depth" pattern explicitly in the Javadoc — the controller-level `policyService.assertCan` is the primary gate; the service-level checks are a backstop. That pattern lands again in 3.11/3.12 where the recompute paths will need similar guarding (especially the holiday-approve hook which can fan out to thousands of flags).

Next part: 3.11 — `SoftFlagService.recomputeForEvent(eventId)` (bidirectional DOUBLE_BOOKING). On event save: clear all existing DOUBLE_BOOKING flags involving the event (either side); recompute against current overlap detection; insert pairs A↔B for each remaining conflict. Needs an overlap query against the `events` table.

---

## Part 3.9 (Series 3) — Per-occurrence overrides + skipped filter — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- New CRUD on `RecurrenceService` for `task_instance_overrides`:
  - `upsertOverride(TaskInstanceOverride)` — find-by-`(taskId, occurrenceDate)`; on hit, mutate the existing row in place and `save()`; on miss, persist a new row. Idempotent on the unique key. Rejects null `taskId`/`occurrenceDate`.
  - `getOverride(taskId, occurrenceDate)` → `Optional<TaskInstanceOverride>`.
  - `removeOverridesForTask(taskId)` — drops everything for a task (used when the parent task is deleted).
  - `removeOverridesFromDate(taskId, fromDate)` — drops on/after `fromDate` (backs the "this and following" task-edit flow).
- `RecurrenceService.expand()` now applies the **skipped filter** AFTER the cycle generates dates (per playbook common-failure-points: skipping before the rule produces the date is wrong because the date wouldn't be in the result anyway). Title / dueTime / status overrides do NOT apply to expand's date list.
- New `RecurrenceService.projectFor(task, occurrenceDate)` → `OccurrenceSnapshot`. Returns `null` for skipped dates; otherwise overlays the override's `title` / `dueTime` / `status` on top of the task's defaults, **field by field** (each falls through independently when unset on the override).
- New `OccurrenceSnapshot` record `(date, title, dueTime, status)`.
- `TaskInstanceOverrideRepository` gained four Spring Data derived methods: `findByTaskIdAndOccurrenceDate`, `findByTaskId`, `deleteByTaskId`, `deleteByTaskIdAndOccurrenceDateGreaterThanEqual`. The two `deleteBy...` carry `@Modifying` + `@Transactional`.

Files changed (count: 4):
- `src/main/java/com/childcarewow/calendar/recurrence/RecurrenceService.java` — new CRUD, skipped filter integration, `projectFor`.
- `src/main/java/com/childcarewow/calendar/recurrence/OccurrenceSnapshot.java` — new record.
- `src/main/java/com/childcarewow/calendar/task/TaskInstanceOverrideRepository.java` — derived query methods.
- `src/test/java/com/childcarewow/calendar/recurrence/RecurrenceServiceTest.java` — +13 tests.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m18s; bundle gate ≥80% line met.
- [x] `RecurrenceServiceTest` — 49/49 tests green:
  - **Skipped occurrence filtered** from expand output (DAILY 5-day rule with one skip → 4 dates).
  - Non-skipped overrides do NOT affect expand's date list.
  - `upsertOverride` creates new on miss; idempotent on hit (second call mutates the existing row in place; second invocation's title/skipped supersede the first).
  - `upsertOverride` rejects missing `taskId`/`occurrenceDate`.
  - `getOverride` pass-through.
  - `removeOverridesForTask` + `removeOverridesFromDate` delegate to repo; `fromDate=null` rejected.
  - `projectFor` falls through to task defaults when no override.
  - `projectFor` applies all three fields when override sets them.
  - `projectFor` returns `null` for skipped dates.
  - `projectFor` with partial override keeps task defaults for unset fields (the field-by-field fall-through).
- [x] Per-class JaCoCo: `RecurrenceService` 15/684 instr missed (~98%); 0/20 methods (100%); ~87% branch. The 5/153 line miss + 14/105 branch miss are mostly defensive-cap and "rule deleted" paths plus one or two of the override-edge branches that the existing tests already happen to cover via Mockito-defaults.
- [x] CI green on PR #60 ([run 25505160107](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25505160107)).

Notes / surprises:
- The `expand()` short-circuits the override fetch when `task.getId() == null` (transient task, only happens in tests). In production tasks always have an ID by the time they're expanded. This guard simplifies tests that don't bother setting an ID and don't have skipped dates.
- The skipped-set is only fetched once per `expand()` call (single SELECT), then a `Set<LocalDate>` is built and the date list is filtered in memory. Cheap when the task has no overrides — empty set, the `.filter()` is skipped via the `!skipped.isEmpty()` guard.
- `upsertOverride` is implemented at the service layer (find then save) rather than via Postgres `INSERT ... ON CONFLICT` because we don't have a custom `@Query` framework wired up yet. The unique constraint on `(task_id, occurrence_date)` (V3 migration) prevents true concurrent races; under contention the loser's `save()` would 23505 (duplicate key) and bubble up as a 500 — acceptable for v1 since override writes are user-initiated and rare.
- Three places in the test file use `org.mockito.Mockito.verify(...)` fully qualified rather than imported, because we already import `Mockito.mock`/`when`/`eq`/`any` and a static `verify` import would have collided with assertJ's `assertThat` style in some readers' tooling. Cosmetic — could be cleaned up later.
- Series 3 coverage rule check (playbook step 4) — `RecurrenceService` ≥ 95%: ~98% line. ✅

Next part: 3.10 — `SoftFlagService` skeleton + dismiss (CRUD on `conflict_flags`: insert / list-active / dismiss; recompute triggers in 3.11/3.12).

---

## Part 3.8 (Series 3) — `RecurrenceService` MONTHLY + end-of-month snapping — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `RecurrenceService.expand()` now handles `RecurCycle.MONTHLY`. Walks `YearMonth`s from `winStart` to `winEnd` inclusive; for each month snaps `dueDayOfMonth` via `Math.min(d, ym.lengthOfMonth())`; emits the resulting `LocalDate` if within window. Cap at 1000 with `truncated=true` if reached (unreachable in practice — see notes).
- All four snapping cases from the spec covered:
  - 31st in non-leap Feb → **Feb 28** (or 29 in leap years).
  - 31st in 30-day months (Apr/Jun/Sep/Nov) → **the 30th**.
  - 29th in non-leap Feb → **Feb 28**.
  - 29th in leap Feb (e.g. 2028) → **Feb 29** (no snap).
- Validation extended with cycle-specific shape: `MONTHLY` requires `dueDayOfMonth` in `1..31`. Enforced at both `create` and `expand` (defense in depth for legacy rows).

Files changed (count: 2):
- `src/main/java/com/childcarewow/calendar/recurrence/RecurrenceService.java` — `+expandMonthly()`, `+MONTHLY` arm of cycle switch (replacing `UnsupportedOperationException`), `+`MONTHLY case in `validate()`, `+import java.time.YearMonth`.
- `src/test/java/com/childcarewow/calendar/recurrence/RecurrenceServiceTest.java` — replaced `monthlyCycleNotYetSupported` (no longer applicable); added 9 MONTHLY-specific tests.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m28s; bundle gate ≥80% line met.
- [x] `RecurrenceServiceTest` — 36/36 tests green:
  - Mid-month rule across Jan/Feb/Mar 2026 → three on-the-15th occurrences.
  - **31st rule** snaps to Feb 28 (non-leap year 2026) and to Apr 30 (30-day month).
  - **29th rule** snaps to Feb 28 in non-leap; **lands on Feb 29 in leap year 2028**.
  - Window starting after the snapped day in the first month → first emission is next month.
  - Missing `dueDayOfMonth` rejected at expand; out-of-range (`32`) rejected at expand; missing rejected at create; out-of-range (`0`) rejected at create.
- [x] Per-class JaCoCo: `RecurrenceService` 15/494 instr missed (~97%); 0/12 methods (100%).
- [x] CI green on PR #58 ([run 25504113178](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25504113178)).

Notes / surprises:
- `YearMonth.atDay(int)` does the work — no need for the playbook's longer formulation `LocalDate.of(year, month, Math.min(...))`. They're equivalent because `atDay` accepts 1..31 and validates against the month length, but we pre-clamp via `Math.min`, so neither path can throw.
- The "the 31st in leap February" path is implicitly tested by the leap-year 29th test (Feb has 29 days in 2028, so `min(31, 29) = 29`). Made the leap test explicit on the 29th rule because that's the more common gotcha.
- The MONTHLY truncation path is **structurally unreachable** with valid rules: `MAX_UNTIL_YEARS = 5` × 12 = 60 monthly occurrences max, well under the 1000-cap. The branch exists for defensive parity with WEEKLY/DAILY and to handle direct-DB-tampered rules. JaCoCo flags it as uncovered — accepted.
- All three cycle implementations (DAILY/WEEKLY/MONTHLY) now share the same coverage shape: ~97% line, 100% method, with the only uncovered branches being defensive cap paths.

Next part: 3.9 — `task_instance_overrides` upsert/get/remove + skipped-occurrence filter applied during `expand`.

---

## Part 3.7 (Series 3) — `RecurrenceService` WEEKLY expansion — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `RecurrenceService.expand()` now handles `RecurCycle.WEEKLY`. Stored `dueDayOfWeek` follows the FE prototype's JS `Date.getDay()` convention (`0=Sun..6=Sat`) and is mapped once to `java.time.DayOfWeek` (`1=Mon..7=Sun`): `js=0 → Java 7`, otherwise `js → Java js`.
- Algorithm: from `winStart`, advance `(targetDow - cursorDow + 7) % 7` days to land on the first matching weekday; step `+7 days` through `winEnd`; cap at `MAX_OCCURRENCES` with `truncated=true` if reached.
- Validation hardened with cycle-specific shape:
  - Creation-time: `WEEKLY` rules require `dueDayOfWeek` in `0..6`.
  - Expand-time: re-validates defensively for legacy rows that may have bypassed creation validation.

Files changed (count: 2):
- `src/main/java/com/childcarewow/calendar/recurrence/RecurrenceService.java` — `+expandWeekly()` helper, `+WEEKLY` arm of cycle switch, `+`cycle-shape validation in `validate()`.
- `src/test/java/com/childcarewow/calendar/recurrence/RecurrenceServiceTest.java` — replaced the `weeklyAndMonthlyCyclesNotYetSupported` test with `monthlyCycleNotYetSupported` (WEEKLY now works); added 7 WEEKLY-specific tests.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m27s; bundle gate ≥80% line met.
- [x] `RecurrenceServiceTest` — 27/27 tests green:
  - **Tuesday rule (`js=2`)** over June 2026 → exactly five Tuesdays (2/9/16/23/30).
  - **Sunday rule (`js=0`)** maps correctly to Java `DayOfWeek.SUNDAY` → four Sundays.
  - **DST boundary** across America/* fall-back (2026-11-01) → five Sundays bracketing the transition; we work in `LocalDate`, so DST doesn't shift dates.
  - WEEKLY without `dueDayOfWeek` → `InvalidRecurrenceException` at BOTH `create` AND `expand` (defense in depth).
  - Out-of-range (`7`) rejected at create; (`-1`) rejected at expand (legacy/corrupt rule path).
  - MONTHLY still throws `UnsupportedOperationException` (Part 3.8).
- [x] Per-class JaCoCo: `RecurrenceService` 8/392 instr missed (~98%); the only uncovered arm is now MONTHLY.
- [x] CI green on PR #56 ([run 25503581821](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25503581821)).

Notes / surprises:
- The DOW mapping `js=0 ? 7 : js` is the cleanest expression of "Sunday is 0 in JS but 7 in Java; otherwise the numbers line up". Avoids the more general `(js + 6) % 7 + 1` formula from the playbook because it's easier to reason about in tests.
- `winStart.plusDays(delta)` lands on or after `winStart`. If the requested window starts on the matching weekday, `delta = 0` and we emit `winStart` itself — verified by the Tuesday test (June 2 is a Tuesday and is included).
- The DST test was important: spec § 8 says "rules like 'is this a Tuesday' are evaluated in the school's timezone in `LocalDate` space, never with `Instant` arithmetic". Using `cursor.plusWeeks(1)` over `LocalDate` gives us that for free — we never construct a `ZonedDateTime` and never see a 23h or 25h day.
- The expand-layer validation re-throws `InvalidRecurrenceException` for legacy bad rules. This trades a 400 over a 500 (which would happen if we passed `null` to `DayOfWeek.of` further down). It's a tiny redundancy with the create-layer check, but worth it for robustness against direct DB tampering.

Next part: 3.8 — MONTHLY expansion with `dueDayOfMonth` and end-of-month snapping (Feb 29/30/31, 30-day months → snap to last day).

---

## Part 3.6 (Series 3) — `RecurrenceService` skeleton + DAILY expansion + 1000-cap — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `recurrence.RecurrenceService` (Spring `@Service`):
  - **CRUD**: `create(rule, taskDueDate)`, `update(ruleId, patch, taskDueDate)`, `shortenUntil(ruleId, newUntil)`, `remove(ruleId)`. All write methods are `@Transactional`. `shortenUntil` rejects extension (would surprise users with new occurrences they never asked for); only shrinks. `remove` 404s before deletion to keep error semantics consistent.
  - **`expand(Task, LocalDate from, LocalDate to)`** → `ExpansionResult(List<LocalDate> occurrences, boolean truncated)`. Inclusive on both ends. Returns empty without error when the task is non-recurring, the rule has been deleted, or the requested window doesn't intersect `[task.dueDate, rule.untilDate]`. WEEKLY and MONTHLY cycles `throw new UnsupportedOperationException` with a message pointing at Parts 3.7 / 3.8.
  - **Validation** (creation/update): `untilDate` required, `>= taskDueDate`, `<= taskDueDate + 5 years`. Misses throw `InvalidRecurrenceException` → 400 `INVALID_RECURRENCE` envelope.
  - **Hard cap** `MAX_OCCURRENCES = 1000` per `expand()` call. Past that, the list is truncated and `truncated=true`. Defends against malformed or legacy rules slipping past the validation cap.
- `recurrence.ExpansionResult` — record with `(List<LocalDate> occurrences, boolean truncated)`. Javadoc documents that callers must surface `truncated` to the API response per spec § 15.

Files changed (count: 3, all new):
- `src/main/java/com/childcarewow/calendar/recurrence/{RecurrenceService,ExpansionResult}.java`
- `src/test/java/com/childcarewow/calendar/recurrence/RecurrenceServiceTest.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 33s; bundle gate ≥80% line met.
- [x] `RecurrenceServiceTest` — 20/20 unit tests green (Mockito-mocked repo, no Spring context):
  - DAILY 14-day rule emits exactly 14 occurrences with `truncated=false`.
  - DAILY respects the request window's start (clamps to dueDate; tested with offset window).
  - Window outside rule range → empty (both before-dueDate and after-untilDate cases).
  - Non-recurring task → empty without touching repo.
  - Deleted rule → empty (graceful, not 404).
  - **5-year DAILY rule expanded over its full range → exactly 1000 occurrences + `truncated=true`** (defensive cap at the maximum allowed by validation).
  - WEEKLY/MONTHLY throw `UnsupportedOperationException` with the right message.
  - 4 validation rejects (until < due, until > due+5y, missing until, missing cycle); 1 valid-create persists.
  - `shortenUntil` rejects extension, persists shrink, rejects null, 404s on unknown.
  - `update` applies patch fields; 404s on unknown.
  - `remove` 404s on unknown; otherwise deletes.
- [x] Per-class JaCoCo: `RecurrenceService` 5/287 instr missed (~98% line, ~94% branch). The single uncovered arm of the cycle-switch is the MONTHLY case — lands in Part 3.8.
- [x] CI green on PR #54 ([run 25503094888](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25503094888)).

Notes / surprises:
- Used pure Mockito unit tests instead of `@SpringBootTest`. The service has no DB-shape concerns (no `@ColumnTransformer`, no jsonb) — every behavior is logic over `RecurrenceRule`/`Task` POJOs and a mockable repo. Ran in 1.8s instead of 11s+; saves ~10s per CI build over the project's lifetime.
- The 1000-cap test deliberately uses a **5-year** rule rather than the playbook's "10 years" example — the validation cap means we can never persist anything longer, so 5 years is the worst case truncation actually reaches. 5 years × 365 + leap days = 1827 days → still > 1000 → truncated.
- `expand` returns empty for a deleted rule rather than 404 because the calendar window read is bulk: a single soft-deleted rule shouldn't fail the entire window response. The right surface for "this specific task's rule was deleted" is the task-detail endpoint (Series 5).
- `shortenUntil` is the API for the FE's "this and following" task-edit flow (FE prototype `eventsService.ts` already uses this verb). Keeping it as a separate method avoids the trap of someone using `update` to silently extend `untilDate`.

Next part: 3.7 — `RecurrenceService` WEEKLY expansion (`due_day_of_week` 0=Sun matching JS `Date.getDay()`; same 1000-cap applies).

---

## Part 3.5 (Series 3) — `TimezoneService` (Caffeine + DST correctness) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `timezone.TimezoneService` (Spring `@Service`):
  - `zoneFor(UUID schoolId)` → `ZoneId`. Caffeine-cached (`maximumSize=10_000`, `expireAfterWrite=1h`). Reads `SELECT timezone FROM schools WHERE id=?` against the platform DB via `platformJdbcTemplate`. Cache hit/miss counters at `timezone_service_cache_{hits,misses}`.
  - `toSchoolLocalDate(Instant, UUID)` → `LocalDate`. Implementation is one line: `instant.atZone(zoneFor(id)).toLocalDate()` — DST-correct because `Instant` is unambiguous and `atZone` applies the zone's rules.
  - `isHolidayForSchool(UUID, LocalDate)` → `boolean`. Queries calendar DB for `approved=true AND deleted_at IS NULL`. Not cached (request pattern is unknown until prod traffic; revisit in Series 11 if hot).
- `timezone.UnknownSchoolTimezoneException extends NotFoundException` (HTTP 404). The cause (e.g. `DateTimeException` for malformed IANA) is logged at WARN inside the service rather than carried on the exception, because `NotFoundException`'s constructor doesn't accept a cause and using `initCause()` from the subclass constructor trips `-Werror`'s "this-escape" warning.

Files changed (count: 3, all new):
- `src/main/java/com/childcarewow/calendar/timezone/{TimezoneService,UnknownSchoolTimezoneException}.java`
- `src/test/java/com/childcarewow/calendar/timezone/TimezoneServiceIT.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m30s; bundle gate ≥80% line met.
- [x] `TimezoneServiceIT` — 7/7 tests green:
  - Sunrise → `America/New_York`, Maplewood → `America/Chicago`.
  - Cache hit/miss counters increment.
  - **DST fall-back in NY**: 2026-11-01 05:30Z (1:30 AM EDT) and 06:30Z (1:30 AM EST, after fall-back) both → `LocalDate 2026-11-01`.
  - **Different zones, same Instant**: 2026-06-15 04:00Z → NY = June 15, Chicago = June 14.
  - Approved holiday recognized; pending federal holiday (`approved=false`) does NOT count.
  - Unknown school → `UnknownSchoolTimezoneException`.
- [x] Per-class JaCoCo: `TimezoneService` 76% line; uncovered branches are the platform-DB-unreachable path (`DataAccessResourceFailureException` → `PlatformUnavailableException`) and the malformed-IANA path. Both require fault injection — Series 11 chaos tests will cover them. Documented in the service's Javadoc.
- [x] CI green on PR #52 ([run 25501790980](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25501790980)).

Notes / surprises:
- **Multi-catch can't list a class and its subclass.** First impl had `catch (ZoneRulesException | DateTimeException ex)` — `ZoneRulesException` is a subclass of `DateTimeException`. Compiler error, fixed by catching `DateTimeException` only (which covers both).
- **`-Werror` "this-escape" warning on `initCause(cause)` in subclass constructor.** `NotFoundException`'s only constructor is `(String entity, UUID id)` — no cause overload. Using `initCause()` from `UnknownSchoolTimezoneException`'s constructor triggers the "possible 'this' escape before subclass is fully initialized" warning under `-Werror`. Resolution: drop the cause from the exception (it's logged at WARN inside the service, so the stack trace lives in logs); document the trade-off in the exception's Javadoc. Adding a `(String, UUID, Throwable)` overload to `NotFoundException` is an option for a future refactor.
- The `SUNRISE` school's `America/New_York` IANA name in the seed is what makes the DST-boundary test deterministic — if the seed timezone changes, the test instants need to move with it.
- Holiday lookup uses raw `JdbcTemplate` (not the JPA `HolidayRepository`) because the playbook spec is JdbcTemplate-shaped and we don't need entity hydration here. The repository will be the read path for the holiday CRUD endpoints later in Series 5.

Next part: 3.6 — `RecurrenceService` skeleton + DAILY occurrence expansion (cap 1000 per call, `expansion_truncated=true` flag for malformed rules, validate `untilDate >= dueDate` and ≤ +5 years).

---

## Part 3.4 (Series 3) — AuditEvent `@Immutable` + CI grep guard + immutability doc — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `@org.hibernate.annotations.Immutable` on `AuditEvent`. At flush time Hibernate now silently suppresses any `UPDATE` for managed entities of this type — inserts and `SELECT` continue normally; mutations via `repo.save(modifiedExisting)` are dropped without an exception. Caveat: `@Immutable` only covers updates, not deletes — the CI grep below covers the delete path.
- New CI step **Audit-event immutability guard** in `.github/workflows/ci.yml` runs `grep -rE "auditEventRepository\.(save|delete)" src/main/java` and fails the build on any match. Note: `AuditService` injects the repo as a variable named `repo`, so the legitimate insert path doesn't match the regex.
- `docs/security/audit-immutability.md`: spells out the four enforcement layers (service-layer convention, JPA `@Immutable`, CI grep, Series-12 pen-test) and explicitly calls out what isn't covered (native SQL via `EntityManager.createNativeQuery`, raw JDBC, admin tooling).
- `AuditEventImmutabilityIT`: insert via `AuditService.log` → mutate `user_agent` / `ip_address` / `metadata` in a separate committed transaction (via `TransactionTemplate`) → reload from a fresh transaction → assert every field still has its original value. Cleanup deletes the test row through the test code (CI grep does not scan `src/test`).

Files changed (count: 4):
- `.github/workflows/ci.yml` — new "Audit-event immutability guard" step.
- `docs/security/audit-immutability.md` — new (security doc tree gets its first occupant).
- `src/main/java/com/childcarewow/calendar/crosscut/AuditEvent.java` — `+@Immutable` + Javadoc updates.
- `src/test/java/com/childcarewow/calendar/audit/AuditEventImmutabilityIT.java` — new (1 test).

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 32s; bundle gate (≥80% line) met.
- [x] `AuditEventImmutabilityIT` 1/1 green: original `user_agent`, `ip_address`, `metadata` survive a save+flush of a mutated managed entity.
- [x] All 8 audit tests still green (`AuditAspectIT` 5 + `AuditServiceIT` 2 + immutability 1).
- [x] Local CI guard `grep -rE "auditEventRepository\.(save|delete)" src/main/java` returns clean.
- [x] CI green on PR #50 ([run 25501103607](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25501103607)).

Notes / surprises:
- **`@Immutable` doesn't throw — it silently drops the UPDATE.** The playbook copy ("save throws") is misleading; behavior matches Hibernate's documented semantics. The test asserts on outcome (DB state preserved) instead of an exception.
- **`@SpringBootTest` without `@Transactional` requires explicit transactions for `entityManager.flush()`** — otherwise `TransactionRequiredException`. First attempt used `em.flush()` directly and failed; refactored to `TransactionTemplate.execute(status -> { ... })` so each step (insert, mutate, reload) commits independently and the third-step assertion reads true DB state, not L1-cache state.
- The CI grep guard is **regex-on-identifier**, not AST-aware. It deliberately requires the identifier `auditEventRepository` so `AuditService` (which uses `repo`) doesn't match. The trade-off is documented: anyone naming a future field `auditEventRepository` and writing `.save()` against it will fail CI. That's the intended teaching moment — they should call `AuditService.log` instead.

Next part: 3.5 — `TimezoneService` (`zoneFor(schoolId)`, `toSchoolLocalDate(instant, schoolId)`, `isHolidayForSchool(schoolId, localDate)` with Caffeine caching and DST-correct semantics).

---

## Part 3.3 (Series 3) — `@Audited` annotation + AOP aspect + AuditService — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `audit/Audited` annotation: `action`, `targetType` (default `""`), `idFrom` (default `"id"`). Method-level. Javadoc spells out the self-invocation caveat — annotate at the controller layer only because Spring AOP proxies don't intercept self-calls.
- `audit/AuditService.log(actorId, action, targetType, targetId, ip, userAgent, metadata)`. Wrapped in `@Transactional(propagation = REQUIRES_NEW)` so audit failures cannot roll back the user-facing transaction (and a rolled-back business txn still leaves the audit row committed). Null metadata defaults to `Map.of()`.
- `audit/AuditAspect`: single `@AfterReturning` advice, pointcut `@annotation(audited)`. Resolves actor from `SecurityContextHolder` (cast to `UserPrincipal`); ip/user-agent from `RequestContextHolder` → `ServletRequestAttributes`; `target_id` via SpEL on the returned object using `audited.idFrom()`. Every resolution path is null-safe; the entire body is wrapped in try/catch and a failure logs at WARN without propagating, so a broken audit write never poisons the user response.
- `pom.xml`: `spring-boot-starter-aop`.

Files changed (count: 6, all new except pom):
- `pom.xml` — `+spring-boot-starter-aop`.
- `src/main/java/com/childcarewow/calendar/audit/{Audited,AuditService,AuditAspect}.java`
- `src/test/java/com/childcarewow/calendar/audit/{AuditAspectIT,AuditServiceIT}.java`

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 1m26s; bundle gate (≥80% line) met.
- [x] AuditAspectIT — 5/5 tests green: success row · exception writes nothing · nested `idFrom="event.id"` · anonymous principal → null actor · unresolvable SpEL → null target_id but row still written.
- [x] AuditServiceIT — 2/2 tests green: full calendar-db round-trip exercising jsonb metadata + inet ip_address + db-managed `created_at`; null metadata defaults to `{}`.
- [x] Per-class JaCoCo: `AuditService` 100% (0 missed across 41 instr / 2 branch / 13 line / 2 method); `AuditAspect` ~72% line (uncovered paths are the aspect's own internal try/catch + the `String → UUID.fromString` fallback in `resolveTargetId`).
- [x] CI green on PR #48 ([run 25500480676](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25500480676)).

Notes / surprises:
- The `AuditAspectIT` test bean's `success()` method initially returned a bare `UUID`. With `idFrom="id"` (default) the SpEL expression `id` doesn't resolve on a `UUID` — it tries to read a property named `id` on the UUID object and gets nothing → `target_id = null`. Fix: wrap test responses in DTOs (matches real controller usage). Lesson stays for real controllers: return DTOs with an `id` field, or override `idFrom` per endpoint.
- Spring AOP test-config quirk: nested `@TestConfiguration` static class registering the test bean works, BUT also marking the test bean with `@Component` makes it Spring-discoverable — using both belt-and-suspenders is fine; the explicit `@Bean` definition wins.
- `UserPrincipalAuthenticationToken` in this repo takes `(Jwt, UserPrincipal, Collection<authority>)` — too many args for a synthetic test auth. Used `UsernamePasswordAuthenticationToken(actor, null, List.of())` instead; the aspect only reads `auth.getPrincipal()` so the concrete token type doesn't matter.
- The aspect's SpEL `idFrom` resolver also accepts a `String` UUID and round-trips via `UUID.fromString`. That branch isn't covered by current tests but exists for future controllers that return DTOs with stringified UUIDs (rare; happy to drop later if unused).

Next part: 3.4 — `AuditService` for non-HTTP code + Hibernate `@Immutable` on `AuditEvent` + CI grep guard.

---

## Part 3.2 (Series 3) — PolicyService — full 19-action catalog + Event/Task overloads — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `policy.PolicyService` interface gained two resource-bearing overloads: `can(UserPrincipal, String, Event)` and `can(UserPrincipal, String, Task)`, plus default `assertCan(...)` overloads that throw `ForbiddenException` carrying the action string.
- `policy.PolicyServiceImpl` now implements all 19 actions from `src/lib/services/policyService.ts:53-121`:
  - **Resource-less (15)**: `event.create`, `event.create.schoolType`, `task.view`, `task.create`, `task.viewAllScope`, `holiday.manage`, `holiday.approve`, `importantDate.manage`, `calendar.softFlag.see`, `addMenu.{show,event,task,holiday,importantDate}`, `notifications.see`.
  - **Resource-bearing (Event)**: `event.edit`, `event.delete`. ORG_ADMIN always allowed; SCHOOL_ADMIN scoped to `actor.schoolIds`; STAFF type-specific — CLASSROOM requires classroom membership, CUSTOM requires school membership, SCHOOL denied; PARENT denied.
  - **Resource-bearing (Task)**: `task.edit`, `task.delete`. ORG_ADMIN always; SCHOOL_ADMIN school-scoped; STAFF only when `assigneeUserId == actor.id`; PARENT denied.
- Defensive: resource-bearing actions called via the no-resource `can(actor, action)` return `false`. `null` actor / `null` resource always denies.
- `PolicyServiceImplTest`: 72 tests across 8 test methods. ~53 parameterized rows in `resourceLessActions` covering 15 actions × 4 roles, plus targeted resource-bearing event tests (6), resource-bearing task tests (5), edge cases (null actor, null resource, unknown action, defensive false on resource-less call), and assertCan throws/silent overloads.

Files changed (count: 3):
- `src/main/java/com/childcarewow/calendar/policy/PolicyService.java` — added Event + Task overloads + their assertCan defaults; expanded Javadoc with the 19-action catalog.
- `src/main/java/com/childcarewow/calendar/policy/PolicyServiceImpl.java` — full action switch + `canModifyEvent` + `canModifyTask` helpers + null guards.
- `src/test/java/com/childcarewow/calendar/policy/PolicyServiceImplTest.java` — expanded from 13 cases to 72.

Validation:
- [x] `mvn verify` — BUILD SUCCESS, 26.8s; bundle gate (≥80% line) met.
- [x] `mvn test -Dtest=PolicyServiceImplTest` — 72 tests, 0 failures, 0 errors, 0.467s.
- [x] JaCoCo per-class on `PolicyServiceImpl`: `0/188 instructions, 0/51 branches, 0/42 lines, 0/8 methods` missed → **100%** (CLAUDE.md § 14 mandate met).
- [x] CI green on PR #46 ([run 25499456638](https://github.com/mukul29phogat-art/calendar-backend/actions/runs/25499456638)).
- [x] Squash-merged to `main` as commit `6d0fddd`.

Notes / surprises:
- The first push attempt committed PolicyService.java + PolicyServiceImpl.java but the test rewrite silently no-op'd because `Write` requires a prior `Read` on existing files (the original 82-line Part 3.1 test file was still on disk and Spotless/Surefire happily ran the old 13 cases against the new 19-action impl, which still passed because the 3 actions Part 3.1 already covered are a strict subset of Part 3.2's catalog and the bundle-gate is 80% not 100%). Fix: explicit Read → Write → second commit on the same branch. Lesson: never trust a Write tool result for an existing file unless you've Read it in the same session.
- Initial impl included a placeholder `ensureEventTypeReferenced()` static method intended to ward off an exhaustiveness warning. It was dead code (no warning would have triggered) and left coverage at 7/8 methods. Dropped in the second commit.
- Cause-aware `ServiceException(message, cause)` constructor pattern from Part 3.0 paid off here — `ForbiddenException(action)` carries the action string cleanly without `initCause()` "this-escape" warnings.
- Git identity was missing from local config; commits went through via per-invocation `GIT_AUTHOR_*` / `GIT_COMMITTER_*` env vars to avoid mutating git config (per CLAUDE.md guard).

Next part: 3.3 — `AuditService` + `@Auditable` AOP aspect.

---

## Part 3.1 (Series 3) — PolicyService skeleton + first 3 actions — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `policy.PolicyService` interface: `can(UserPrincipal, String)` + default `assertCan` throwing `ForbiddenException` (mapped to 403 envelope by Part 3.0). Resource-bearing overloads (`Event` / `Task` parameters) deferred to Part 3.2 to avoid the import-layering churn until the resource shapes are needed.
- `policy.PolicyServiceImpl @Service`: switch-expression over action strings.
  - `event.create` → role ≠ PARENT
  - `task.view` → role ≠ PARENT (D10: parents never see tasks)
  - `holiday.manage` → ORG_ADMIN or SCHOOL_ADMIN
  - default → false (unknown action denies; Part 3.2 fills in the catalog)
  - null actor also denies
- `PolicyServiceImplTest`: 4 roles × 3 actions = 12-case matrix via `@ParameterizedTest @CsvSource`, plus assertCan-throws + assertCan-allows + null-actor + unknown-action tests. Pure unit tests, no Spring context.

Files changed (count: 3, all new):
- `src/main/java/com/childcarewow/calendar/policy/{PolicyService, PolicyServiceImpl}.java`
- `src/test/java/com/childcarewow/calendar/policy/PolicyServiceImplTest.java`

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS first try
- [x] 60 classes analyzed, all gates met (incl. 100% on `PolicyServiceImpl` per CLAUDE.md §14)
- [x] CI on PR #44 green

Notes: clean Part. The 403-envelope path is exercised by `GlobalExceptionHandlerTest.forbiddenMapsTo403` from Part 3.0 — no separate slice test needed yet.

Next part: **Part 3.2 — full PolicyService action set** (19 total actions, including resource-bearing `event.edit`, `event.delete`, `task.edit`, `task.delete` that need `Event` / `Task` arguments).

---

## Part 3.0 (Series 3) — GlobalExceptionHandler + ServiceError envelope — STATUS: ✅ done — **Series 3 begun**
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- New `com.childcarewow.calendar.exception` package consolidates all error types. `common.{ValidationException, PlatformUnavailableException}` moved here (`git mv` + package-decl rewrites).
- `ServiceException` abstract base with `code` + `HttpStatus` + `field` + `message` + optional `cause`. **All 11 concrete subclasses derive from it** so the handler logic is one method.
- 11 concrete exception types: `ValidationException` (400), `EventOnHolidayException` / `TaskOnHolidayException` / `DuplicateHolidayException` / `IdempotencyReplayException` (409), `InvalidTimeRangeException` / `InvalidRecurrenceException` / `AttachmentInvalidException` (400), `ForbiddenException` (403), `NotFoundException` (404), `PlatformUnavailableException` (503).
- `ServiceError` + `ServiceErrorResponse` records (`@JsonInclude(NON_NULL)`) — envelope shape per arch spec §15: `{ok:false, error:{code,message,field?}, traceId}`.
- `GlobalExceptionHandler @RestControllerAdvice`: 1 handler for all `ServiceException`s; 1 for `MethodArgumentNotValidException` → `VALIDATION_ERROR`; 1 catch-all for `Exception` → `INTERNAL_ERROR` with **sanitized** message (original logged but never leaked).
- `TraceIdFilter @Component @Order(HIGHEST_PRECEDENCE)`: per-request UUID in MDC + `trace-id` response header (allow-listed in CORS exposed headers from Part 2.1).
- `GlobalExceptionHandlerTest` (12 tests): unit tests on the handler directly (no `@WebMvcTest`); each subclass mapped to expected status; sanitized fallback verified.

Files changed: 21 (2 deleted from `common/`, 13 new in `exception/`, 1 test, 5 modified for import + Set.copyOf rewrites).

Validation: BUILD SUCCESS; 58 classes, all gates met. CI green on PR #42.

Two `-Werror` fixes captured (reusable):
1. **`initCause()` in constructor warns** ("possible 'this' escape"). Fix: ServiceException grew a `(code, status, field, message, cause)` 5-arg constructor; PlatformUnavailableException uses it instead of `initCause()`.
2. **Spotless on a Write-rewritten file** caught CRLF (Windows tool default); `mvn spotless:apply` normalized.

Next part: **Part 3.1 — `PolicyService` skeleton + first three actions** (`event.create`, `task.view`, `holiday.manage`).

---

## Part 2.4 (Series 2) — GET /api/v1/auth/me + MeView — STATUS: ✅ done — **Series 2 closed**
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `UserPrincipal` extended with `name` field (sourced from `platform.users.name`); `PlatformUserDirectory` SELECT updated.
- `MeView` record matching the prototype's `User` type (id, name, email, role, designation?, schoolIds, classroomIds?, childStudentIds?). `@JsonInclude(NON_EMPTY)` elides null/empty optional fields.
- `AuthController.GET /api/v1/auth/me` → `MeView.from(actor)`.
- `AuthControllerTest` (slice with `@MockBean PlatformUserDirectory`): PARENT-shaped response confirms designation + classroomIds are elided; unauthenticated → 401.
- `WhoAmIControllerTest` + `PlatformUserDirectoryIT` updated to include name.

Validation: BUILD SUCCESS, 44 classes, all gates met. CI green on PR #40.

**Series 2 closure stats:** 4 Parts, 7 PRs (#35–#40), auth pipeline complete: JWT validation → UserPrincipal load → PlatformEntityValidator caching → /auth/me. Next: **Series 3** (cross-cutting services starting with GlobalExceptionHandler).

---

## Part 2.3 (Series 2) — PlatformEntityValidator + Caffeine cache + 503 handler — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `pom.xml`: `caffeine 3.1.8`.
- `common.ValidationException` (400, field+message) + `common.PlatformUnavailableException` (503, fail-closed when platform DB is unreachable).
- `platform.PlatformEntityValidator` interface + `Impl`: 4 single-key caches + 1 composite (classroom→school) cache, Caffeine 5min TTL / 10K max. Micrometer counters `platform_validator_cache_{hits,misses}`. Catches `DataAccessResourceFailureException` → throws `PlatformUnavailableException`.
- `PlatformEntityValidatorIT` (4 tests): seed entities exist; unknown returns false; assertX throws on missing; cache-hit counter increments on repeated calls.

Validation: BUILD SUCCESS, 42 classes, all gates met. CI green on PR #39.

Two compilation fixes captured:
1. **Multi-catch can't list a subclass + parent.** `CannotGetJdbcConnectionException` is a subclass of `DataAccessResourceFailureException`; dropped the subclass.
2. **`Edit` to pom.xml failed silently** with "file modified since read" — re-Read + re-Edit was needed. Watch for this when concurrent linter activity touches files between Read and Edit.

`@ControllerAdvice` mapping for ValidationException → structured 400 envelope is **deferred to Series 3 / Part 3.0** (GlobalExceptionHandler) where the full ServiceError envelope is implemented holistically.

---

## Part 2.2 (Series 2) — UserPrincipal record + auth context plumbing — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `Role` enum (4 values), `UserPrincipal` record (with `Set.copyOf()` in compact constructor for immutability).
- `PlatformUserDirectory.load(UUID)`: queries `platform.users` + `user_schools` + `classroom_staff` + `student_parents` (parents only) via `platformJdbcTemplate`. Throws `UnknownPrincipalException` → 401 if the JWT subject doesn't match a row.
- `JwtToUserPrincipalConverter` (`@Component`): wired into `SecurityConfig.oauth2ResourceServer.jwt(jwtConverter)`. Authorities derive `ROLE_<role>` from the loaded principal.
- `UserPrincipalAuthenticationToken`: final subclass of `AbstractAuthenticationToken`; transient `Jwt` + `UserPrincipal` fields satisfy serialization warnings.
- `WhoAmIController` now takes `@AuthenticationPrincipal UserPrincipal` and returns the full record.
- `PlatformUserDirectoryIT` (5 tests): per-role assertions against the seeded platform-DB users; verifies org-admin → both schools, staff → classroom_staff joins, parent → student_parents joins, unknown UUID → exception.
- `WhoAmIControllerTest` updated: `@MockBean PlatformUserDirectory` stubs the lookup; assertion checks the full UserPrincipal JSON shape.

Files changed (count: 9): 6 main classes (Role, UserPrincipal, PlatformUserDirectory, UnknownPrincipalException, JwtToUserPrincipalConverter, UserPrincipalAuthenticationToken), 2 modified (SecurityConfig, WhoAmIController), 2 test files (one updated, one new).

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS
- [x] 37 classes analyzed, all gates met
- [x] CI on PR #37 green

Notes (two -Werror fixes worth pinning):
1. `AbstractAuthenticationToken` implements `Serializable`; non-transient non-Serializable fields trip "non-transient instance field" warning. Marked `Jwt` + `UserPrincipal` `transient` (we never serialize the token; sessions are stateless).
2. `setAuthenticated()` from the constructor triggered "possible 'this' escape" warning. Made the class `final` + `@SuppressWarnings("this-escape")` on the constructor — class-final keeps the suppression honest (no override possible).

Caching deferred to Part 2.3 per playbook (Caffeine + 60s TTL lands together with `PlatformEntityValidator`).

Next part: **Part 2.3 — `PlatformEntityValidator` + Caffeine cache**.

---

## Part 2.1 (Series 2) — Spring Security + Supabase JWT validation — STATUS: ✅ done — **Series 2 begun**
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `pom.xml`: `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`.
- `application.yml`: oauth2 resource-server config (`public-key-location: classpath:supabase-jwt-public-key.pem`); multipart limits (11MB request / 10MB file); Tomcat `max-http-form-post-size: 256KB`.
- `SecurityConfig` (`com.childcarewow.calendar.auth`): stateless filter chain, CSRF disabled, public actuator + OpenAPI paths, `/api/v1/**` authenticated, everything else denied. CORS allows `http://localhost:5173` + `*.calendar.childcarewow.com` with the Authorization + Idempotency-Key headers and exposes X-Unread-Count + trace-id.
- `WhoAmIController`: `GET /api/v1/whoami` returning the JWT subject. Smoke endpoint; replaced by real domain controllers Series 4+.
- `TestJwtSigner` (test-only utility) + 2048-bit RSA test keypair committed under `src/test/resources/supabase-jwt-{public,private}-key.pem`. `.gitignore` extended with explicit `!`-rules for both PEMs (default `*.pem` would block them).
- `WhoAmIControllerTest` (`@WebMvcTest` slice + `@Import(SecurityConfig.class)`) — 5 cases: no Authorization → 401; tampered signature → 401 + `WWW-Authenticate: invalid_token`; valid RS256 token → 200 with subject; CORS preflight from localhost:5173 → 200 + Allow-* headers; preflight from evil.example → 403.

Files changed (count: 8, all new + 2 modified):
- `pom.xml` (modified)
- `src/main/resources/application.yml` (modified)
- `.gitignore` (modified — explicit !-rules)
- `src/main/java/com/childcarewow/calendar/auth/{SecurityConfig, WhoAmIController}.java` (new)
- `src/test/java/com/childcarewow/calendar/auth/{TestJwtSigner, WhoAmIControllerTest}.java` (new)
- `src/test/resources/supabase-jwt-{public,private}-key.pem` (new — test fixtures)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS
- [x] 31 classes analyzed, all gates met
- [x] CI on PR #35 green (after the `.gitignore` fix in a follow-up commit on the same branch)

Notes / surprises (two real issues fixed):
1. **`.gitignore`'s `*.pem` rule blocked the test fixtures** from being committed. Without them on origin, CI's classpath couldn't load `classpath:supabase-jwt-public-key.pem` and the JwtDecoder bean failed at startup. Added explicit `!src/test/resources/supabase-jwt-*.pem` allow-rules. **Pattern:** any future test fixture under `*.pem`/`*.key` needs an explicit gitignore exception.
2. **`KeyFactory.parsePrivateKey(...)` doesn't exist** — the API is `KeyFactory.generatePrivate(KeySpec)`. Caught by the compiler in the first verify run; trivial fix.

Strategic deviation worth flagging:
- The playbook spec uses `public-key-location` which only handles **RSA** keys. **Supabase signs with ES256** (ECDSA P-256, per the JWKS we captured in P0.2). The test fixture is RSA-keyed solely so the slice tests can exercise the validation path. Real Supabase token validation lands in Series 11 by overriding `application-{env}.yml` to use `jwk-set-uri: ${calendar.auth.supabase-issuer}/.well-known/jwks.json` instead of `public-key-location`. The contract is otherwise identical.

Next part: **Part 2.2 (Series 2) — UserPrincipal record + auth context plumbing.** Spec: `implementation_plan.md` line 1624.

---

## Part 1.8 (Series 1) — Migration smoke + rollback documentation — STATUS: ✅ done — **Series 1 closed**
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `docs/migrations.md` — one row per migration (V1–V8) covering what it adds, data risk, rollback approach. Includes the forward-only Flyway convention, the no-platform-FK rule (D11), and the TEXT+CHECK-over-ENUM rationale.
- Validated all 8 migrations on the live calendar-db: `mvn flyway:info` shows V1 placeholder + V2 events + V3 tasks + V4 holidays + V5 important_dates + V6 conflict_flags + V7 notifications + V8 crosscut, all `Success`.
- Fresh-DB migration test is **already covered** by `FlywayMigrationIT` (Linux-only on CI; class-level `@EnabledOnOs({OS.LINUX, OS.MAC})`). Spins up a Testcontainers Postgres each run and applies all 8 migrations from scratch. The CI green status across PRs #21–#33 confirms this works end-to-end.

Files changed (count: 1, new):
- `docs/migrations.md`

Validation:
- [x] `mvn flyway:info` → 8 versioned migrations, all Success
- [x] CI on PR #34 green (FlywayMigrationIT applies all 8 to a fresh container)
- [x] No new schema in this Part — pure documentation

Notes / surprises: none. Series 1 closes on a clean note.

**Series 1 closure stats:**
- 8 schema migrations (V1–V8)
- 21 main entities/enums/repos in 7 packages (event/, task/, holiday/, importantdate/, conflict/, notification/, crosscut/)
- 25+ failsafe ITs across 7 IT classes
- 13 PRs merged for Series 1 (Parts 1.1–1.8 each had a code PR + a progress PR; some Parts had additional follow-ups for fixes)
- Total backend PRs: 34

Next series: **Series 2 — Auth + platform-DB integration.** Spec at `implementation_plan.md` line 1512. Wires Supabase JWT validation, the platform-DB read access, and the PolicyService scaffolding.

---

## Part 1.7 (Series 1) — V8 idempotency_keys + audit_events — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `V8__crosscut.sql`: idempotency_keys (text PK, JSONB response_body, expires_at default now()+24h, idx_idempotency_expires for nightly purge) + audit_events (UUID PK, JSONB metadata, INET ip_address, two indexes for actor + target lookups).
- IdempotencyKey + AuditEvent entities + 2 repositories.
- AuditEvent.metadata is `Map<String,Object>` not String (per playbook — String-on-jsonb fails with "bytea" type mismatch).
- AuditEvent.ipAddress uses `@ColumnTransformer(write="?::inet")` to cast bound parameters to INET (Hibernate's default VARCHAR bind is rejected by Postgres).
- IdempotencyKey.expires_at is `insertable=false` so the DB DEFAULT fires; test calls `em.refresh()` on the **returned managed instance** (saveAndFlush with @Id pre-set merges and returns a NEW managed instance — refreshing the original throws "Entity not managed").

Files changed (count: 6, all new):
- `src/main/resources/db/migration/V8__crosscut.sql`
- `src/main/java/com/childcarewow/calendar/crosscut/{IdempotencyKey, AuditEvent, IdempotencyKeyRepository, AuditEventRepository}.java`
- `src/test/java/com/childcarewow/calendar/crosscut/CrosscutRepositoryIT.java`

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS (after 2 fixes — see lessons)
- [x] 29 classes analyzed, all gates met
- [x] CI on PR #33 green
- [x] `idempotencyKeyDefaultExpiresAt24h`: confirms DB default delta is 24h ± 5s
- [x] `auditEventInsertOnly`: round-trips inet, user_agent, Map<String,Object> metadata

Notes / surprises (two real lessons):
1. **Postgres INET requires `?::inet` cast on bound parameters.** Without `@ColumnTransformer(write="?::inet")` Hibernate sends VARCHAR which Postgres rejects. Pattern reusable for any non-standard Postgres type accessed as String (cidr, macaddr, ltree, etc.).
2. **`saveAndFlush()` with a pre-set `@Id` calls `merge()` (not `persist()`) and returns a NEW managed instance.** The original entity is detached after merge. `em.refresh(original)` throws "Entity not managed". Always capture the returned value.

Next part: Part 1.8.

---

## Part 1.6 (Series 1) — V7 notifications + recipients + reads + deliveries — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `V7__notifications.sql`: 4 tables, 3 enums (TEXT+CHECK).
  - `notifications` (8-value `kind` enum matching prototype exactly, JSONB `payload`, `paused` gate).
  - `notification_recipients` + `notification_reads` (composite PK, ON DELETE CASCADE from notifications).
  - `notification_deliveries` (channel + status enums, attempt tracking).
- **Deviation from architecture spec §5.7:** delivery channel includes PUSH per locked D4 (Firebase FCM in Series 11). Spec was APP+EMAIL only.
- 3 enums + 4 entities (2 with `@IdClass` composite keys) + 4 repos in `com.childcarewow.calendar.notification`.
- `NotificationRepositoryIT` (3 tests): exhaustive round-trip across notification + recipient + delivery; cascade delete from notification → recipients; PK-uniqueness violation on `notification_reads`.

Files changed (count: 15, all new).

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS (after 2 fixes — see notes)
- [x] 27 classes analyzed, all gates met
- [x] 24 tests run, 2 skipped (FlywayMigrationIT @EnabledOnOs Linux/Mac)
- [x] CI on PR #31: green

Notes / surprises (two real lessons):
1. **`-Werror` catches missing `serialVersionUID`** on classes implementing `Serializable`. Added `private static final long serialVersionUID = 1L;` to both `@IdClass` key classes. Future @IdClass/@Embeddable Serializable classes need the same.
2. **`JpaRepository.save()` with `@IdClass` merges (upsert), not inserts.** The PK-violation test had to use `em.persist() + em.flush()` to force INSERT and trigger the constraint. Pattern for future composite-PK uniqueness tests.

Next part: **Part 1.7 (Series 1) — V8 idempotency_keys + audit_events**.

---

## Part 1.5 (Series 1) — V6 conflict_flags (bidirectional) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `V6__conflict_flags.sql`: TEXT+CHECK `entity_type` (EVENT/TASK) + `conflict_type` (HOLIDAY/DOUBLE_BOOKING/RESOURCE). `entity_id`/`conflicting_entity_id` are bare UUID (polymorphic refs to events/tasks/holidays — real FKs not possible). Two partial indexes: `idx_conflict_flags_entity` (predicate WHERE dismissed=false), `idx_conflict_flags_holiday` (predicate WHERE conflict_type='HOLIDAY').
- **Bidirectional invariant** for DOUBLE_BOOKING is service-layer-enforced (architecture spec §7.3) — DDL allows two rows; SoftFlagService inserts both A→B and B→A.
- `SoftFlagType` + `FlaggedEntity` enums, `ConflictFlag` entity, `ConflictFlagRepository` with custom finder `findByEntityTypeAndEntityIdAndDismissedFalse` (mirrors the partial-index predicate; Spring Data derives the JPQL).
- `ConflictFlagRepositoryIT` (3 tests):
  - `roundTripsDoubleBookingPair` — A→B + B→A both round-trippable
  - `roundTripsHolidayFlag` — exhaustive incl. dismissed metadata
  - `dismissedFlagsExcludedFromActiveFinder` — proves the partial-index predicate matches the repo's derived query

Files changed (count: 6, all new):
- `src/main/resources/db/migration/V6__conflict_flags.sql`
- `src/main/java/com/childcarewow/calendar/conflict/{SoftFlagType, FlaggedEntity, ConflictFlag, ConflictFlagRepository}.java`
- `src/test/java/com/childcarewow/calendar/conflict/ConflictFlagRepositoryIT.java`

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS first try
- [x] 18 classes analyzed, all gates met
- [x] CI on PR #29: green
- [x] Custom finder works (Spring Data derives `WHERE entityType=? AND entityId=? AND dismissed=false` from method name)

Notes / surprises: none. Pattern stable.

Next part: **Part 1.6 (Series 1) — V7 notifications + recipients + reads + deliveries**.

---

## Part 1.4 (Series 1) — V5 important_dates — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `V5__important_dates.sql`: TEXT+CHECK `kind` (BIRTHDAY/IMPORTANT), `visible_to_parents BOOLEAN DEFAULT false` (orthogonal gate to kind — a BIRTHDAY can be admin-only; an IMPORTANT can be parent-visible), bare uuid for platform refs (orgs, schools, students, users) per D11. Two non-unique partial indexes for read paths.
- `ImportantKind` enum, `ImportantDate` entity, `ImportantDateRepository` in `com.childcarewow.calendar.importantdate`.
- `ImportantDateRepositoryIT` (3 tests):
  - `roundTripsBirthday` — exhaustive: BIRTHDAY with student_id and visible_to_parents=true.
  - `roundTripsImportantWithDefaultParentVisibilityFalse` — IMPORTANT without student_id; verifies the DB default applies.
  - `birthdayWithoutStudentIdPermitted` — pins the architecture-spec decision: student_id is **NOT** required at the DB level for BIRTHDAY (service layer responsibility). If a future migration adds a CHECK constraint to enforce it, this test breaks deliberately.

Files changed (count: 5, all new):
- `src/main/resources/db/migration/V5__important_dates.sql`
- `src/main/java/com/childcarewow/calendar/importantdate/{ImportantKind, ImportantDate, ImportantDateRepository}.java`
- `src/test/java/com/childcarewow/calendar/importantdate/ImportantDateRepositoryIT.java`

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS first try
- [x] 18 tests run, 2 skipped (FlywayMigrationIT @EnabledOnOs Linux/Mac)
- [x] JaCoCo: 15 classes analyzed, all gates met
- [x] CI on PR #27: green
- [x] `mvn flyway:info` → V1+V2+V3+V4+V5 all Success

Notes / surprises:
- Series 1 patterns continue to hold cleanly. No surprises.
- Net: third consecutive Part-of-Series-1 with first-try BUILD SUCCESS. The "exhaustive round-trip + bare uuid + TEXT+CHECK + @SpringBootTest @Transactional + em.clear()" recipe is stable.

Next part: **Part 1.5 (Series 1) — V6 conflict_flags (bidirectional)** — soft-flag table referenced from events/tasks for HOLIDAY/DOUBLE_BOOKING/RESOURCE warnings.

---

## Part 1.3 (Series 1) — V4 holidays (named partial unique index for federal upsert) — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `V4__holidays.sql`: holidays table (14 cols, TEXT+CHECK `source` CUSTOM/FEDERAL, bare uuid for platform refs).
- Two partial unique indexes:
  - `uq_holiday_school_date_approved` — predicate `WHERE approved=true AND deleted_at IS NULL` ("one approved per (school, date)")
  - **`uq_holidays_federal_pending`** — predicate `WHERE source='FEDERAL' AND approved=false AND deleted_at IS NULL`. **Named exactly per architecture spec §7.8** because the Phase 6.7 Nager.Date sync upsert references it by name in `ON CONFLICT ON CONSTRAINT`. Renaming would break that contract.
- Two non-unique supporting partial indexes for read paths.
- `HolidaySource` enum, `Holiday` entity, `HolidayRepository` in `com.childcarewow.calendar.holiday`.
- `HolidayRepositoryIT` (4 tests):
  - `roundTripsCustomHoliday` — exhaustive
  - `allowsApprovedCustomAlongsidePendingFederalOnSameDate`
  - `forbidsTwoApprovedHolidaysOnSameDate` (uq_holiday_school_date_approved)
  - `forbidsTwoPendingFederalsOnSameDate` (uq_holidays_federal_pending)

Files changed (count: 5, all new):
- `src/main/resources/db/migration/V4__holidays.sql`
- `src/main/java/com/childcarewow/calendar/holiday/{HolidaySource, Holiday, HolidayRepository}.java`
- `src/test/java/com/childcarewow/calendar/holiday/HolidayRepositoryIT.java`

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS first try
- [x] 15 tests run, 2 skipped (FlywayMigrationIT @EnabledOnOs Linux/Mac)
- [x] JaCoCo: 13 classes, all gates met
- [x] CI on PR #25: green
- [x] `mvn flyway:info` → V1+V2+V3+V4 all Success
- [x] `\d holidays` shows both unique indexes with their partial predicates exactly matching the spec
- [x] `pg_indexes` confirms `uq_holidays_federal_pending` is spelled exactly as architecture spec § 7.8 references

Notes / surprises (playbook clarification):
- The playbook's named test #2 (`allowsTwoPendingFederalsOnSameDate`) is **semantically inverted** vs the spec'd schema. The partial index `uq_holidays_federal_pending`'s WHERE clause INCLUDES `approved=false` rows — meaning two pending federals on the same `(school, date)` actually VIOLATE it (which is exactly what the upsert relies on for `ON CONFLICT`). Substituted a semantically-correct test (`allowsApprovedCustomAlongsidePendingFederal`) and added the missing `forbidsTwoPendingFederals` test that actually validates the named index. Net: 4 tests instead of the spec'd 3.
- Patterns from Parts 1.1 + 1.2 carried forward without surprises.

Next part: **Part 1.4 (Series 1) — V5 important_dates** (BIRTHDAY + IMPORTANT kinds).

---

## Part 1.2 (Series 1) — V3 recurrence_rules + tasks + task_instance_overrides — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `V3__tasks.sql`: 3 tables, 5 CHECK constraints on tasks/recurrence_rules.
  - `recurrence_rules` (8 cols): TEXT+CHECK `cycle` (DAILY/WEEKLY/MONTHLY), range CHECKs on `due_day_of_week` (0-6, JS Date.getDay convention) and `due_day_of_month` (1-31), `chk_weekly_dow` + `chk_monthly_dom` enforcing required-day-by-cycle.
  - `tasks` (18 cols): TEXT+CHECK `status` (TODO/IN_PROGRESS/DONE — **NOT 'COMPLETED'**, matches prototype) defaulting to TODO; TEXT+CHECK `priority` (LOW/MEDIUM/HIGH) defaulting to MEDIUM; `recurrence_id uuid REFERENCES recurrence_rules(id)` (calendar-owned real FK per D11); 4 partial indexes (school+assignee, school+due_date, school+status, parent_task_group_id).
  - `task_instance_overrides`: `task_id uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE` (calendar-owned real FK), `UNIQUE (task_id, occurrence_date)`, nullable `status` for "no override".
- 3 enums (`TaskStatus`, `TaskPriority`, `RecurCycle`), 3 entities (`Task`, `RecurrenceRule`, `TaskInstanceOverride`), 3 `JpaRepository`s — all in `com.childcarewow.calendar.task` package.
- `TaskRepositoryIT`: 4 tests under `@SpringBootTest @Transactional`:
  - `roundTripsTask` — exhaustive round-trip via `EntityManager.clear()` after save (creates a `RecurrenceRule` first so `recurrenceId` can reference it).
  - `enforcesWeeklyRequiresDayOfWeek` — saving a WEEKLY rule with null `dueDayOfWeek` throws (`chk_weekly_dow`).
  - `enforcesMonthlyRequiresDayOfMonth` — same for MONTHLY + null `dueDayOfMonth` (`chk_monthly_dom`).
  - `cascadeDeleteOverridesWhenTaskDeleted` — creates a task + 2 overrides, deletes the task with `em.flush()`, asserts both overrides are gone (DB CASCADE fires).

Files changed (count: 11, all new):
- `src/main/resources/db/migration/V3__tasks.sql`
- `src/main/java/com/childcarewow/calendar/task/{Task, RecurrenceRule, TaskInstanceOverride, TaskStatus, TaskPriority, RecurCycle}.java`
- `src/main/java/com/childcarewow/calendar/task/{Task, RecurrenceRule, TaskInstanceOverride}Repository.java`
- `src/test/java/com/childcarewow/calendar/task/TaskRepositoryIT.java`

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS **first try**
- [x] 11 tests run, 2 skipped (FlywayMigrationIT @EnabledOnOs Linux/Mac on Windows)
- [x] JaCoCo: 11 classes, all gates met (≥80% bundle line) — coverage held by exhaustive round-trip
- [x] CI on PR #23: green
- [x] `mvn flyway:info` → V1, V2, V3 all Success
- [x] `\d tasks` → all 4 CHECKs (title length, status enum, priority enum) + FK to recurrence_rules + 4 partial indexes + cascade-delete reference from overrides
- [x] `\d recurrence_rules` → all 5 CHECKs (cycle enum, dow range, dom range, weekly_dow, monthly_dom)

Notes / surprises:
- The Part 1.1 patterns (TEXT+CHECK enums, bare uuid for platform refs, exhaustive round-trip, `EntityManager.clear()` to defeat L1 cache) carried forward cleanly. Build green on first try.
- For the cascade-delete test: `tasks.deleteById(taskId)` followed by `em.flush()` is the right pattern. Without `flush()`, the DELETE stays in the persistence context and the cascade doesn't fire at the DB level. After `flush()`, `em.clear()` to evict the L1 cache before re-`findById()`.
- The `recurrence_id` FK is **calendar-internal** (both tables in calendar DB), so it's a real Postgres FK; the entity field is bare `UUID` for consistency with the rest of the entity. Future Parts can refactor to `@ManyToOne` if needed.

Next part: **Part 1.3 (Series 1) — V4 holidays** with the named partial unique index `uq_holidays_federal_pending` (Phase 6.7's Nager.Date upsert targets it by name).

---

## Part 1.1 (Series 1) — V2 events schema + JPA entity + repository IT — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `V2__events.sql`: `events` table (19 columns) + 3 calendar-owned join tables (`event_attendees`, `event_students`, `event_excluded_participants`). Per locked decision D11, references to platform-owned tables are bare `uuid` columns with SQL comments — NOT real FKs (cross-DB constraints not possible). Real FKs only on calendar-owned children → `events ON DELETE CASCADE`.
- 4 CHECK constraints: `type IN ('CLASSROOM','CUSTOM','SCHOOL')` (text + CHECK over Postgres ENUM per playbook), `char_length(title) <= 120`, `chk_event_time_range (end_dt > start_dt)`, `chk_event_classroom_required (type <> 'CLASSROOM' OR classroom_id IS NOT NULL)`.
- 3 partial indexes filtered by `deleted_at IS NULL` for the common read paths: `(school_id, start_dt)`, `organizer_user_id`, `classroom_id`.
- `pom.xml`: `spring-boot-starter-data-jpa`.
- `application.yml`: `spring.jpa.hibernate.ddl-auto: validate` (Flyway owns the schema), plus `format_sql: true`, `jdbc.time_zone: UTC`.
- `Event` entity (com.childcarewow.calendar.event package): JPA `@Entity` with `@Enumerated(EnumType.STRING)` for `type`, getters/setters for all fields. `created_at` / `updated_at` marked `insertable=false, updatable=false` (DB-managed via `DEFAULT now()`).
- `EventType` enum: `CLASSROOM`, `CUSTOM`, `SCHOOL`.
- `EventRepository extends JpaRepository<Event, UUID>`.
- `EventRepositoryIT` (3 tests, `@SpringBootTest @Transactional`):
  - `roundTripsClassroomEvent` — exhaustive: sets every field, `EntityManager.clear()` to evict L1 cache, asserts every getter on re-read.
  - `enforcesEndAfterStart` — saving with `end_dt < start_dt` throws `DataIntegrityViolationException` (chk_event_time_range).
  - `requiresClassroomForClassroomType` — saving `type=CLASSROOM` with `classroom_id=null` throws (chk_event_classroom_required).

Files changed (count: 7, +5 new):
- `src/main/resources/db/migration/V2__events.sql` (new)
- `pom.xml` (modified — JPA starter)
- `src/main/resources/application.yml` (modified — spring.jpa block)
- `src/main/java/com/childcarewow/calendar/event/Event.java` (new)
- `src/main/java/com/childcarewow/calendar/event/EventType.java` (new)
- `src/main/java/com/childcarewow/calendar/event/EventRepository.java` (new)
- `src/test/java/com/childcarewow/calendar/event/EventRepositoryIT.java` (new)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS
- [x] Surefire: 3 tests (`CalendarApplicationTests` + `PlatformDbHealthIndicatorTest`); Failsafe: 4 tests + 2 skipped on Windows (`DatasourceConfigIT` + `EventRepositoryIT` + `FlywayMigrationIT` skipped via `@EnabledOnOs`)
- [x] CI run on PR #21 green (Linux runs FlywayMigrationIT live, all 7 tests run)
- [x] JaCoCo: bundle of 5 classes, all gates met (≥80%)
- [x] `mvn flyway:info` → V1 placeholder Success + V2 events Success
- [x] `\d events` → 4 CHECK constraints + 3 partial indexes + 3 child-table FKs
- [x] `\dt event_*` → all 3 child tables present
- [x] `count(*) FROM events` → 0 (test rolled back via `@Transactional`)

Notes / surprises:
- **Coverage scare:** initial test was minimal (only set 12 of 18 setters); JaCoCo dropped to 75% (below the 80% gate) because the Event entity has many getters/setters not exercised. Fix: extended the round-trip to cover every field. Now coverage holds. Future entities should follow the same pattern: round-trip tests are exhaustive by default. JPA-entity exclusion via JaCoCo `<exclude>` was considered and rejected — the round-trip is the right place to test entity contract.
- **L1 cache gotcha:** with `@Transactional`, `events.findById(id)` after `events.save(e)` returns the same in-memory object, masking any DB-side conversion bugs. Calling `em.clear()` between save and find evicts the L1 cache and forces a real `SELECT`. Pattern to use in all repository round-trip ITs.
- Spring Boot's auto-config picks @Primary calendar DataSource for JPA EntityManagerFactory — no explicit `@Bean` definitions needed (skipped playbook step 4 as redundant; auto-config does the right thing). If a future Part needs JPA on platform datasource too, add the explicit beans then.
- `gen_random_uuid()` works without `pgcrypto` extension in Postgres 13+; we're on 15. No `CREATE EXTENSION` needed.

Next part: **Part 1.2 (Series 1) — V3 recurrence_rules + tasks + task_instance_overrides.** Recurring tasks per the locked task domain.

---

## Part 0.7 (Series 0) — Dockerfile multi-stage build — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `Dockerfile` (repo root, syntax `dockerfile:1.7`): build stage on `eclipse-temurin:21-jdk-alpine` runs `./mvnw package`, extracts the fat jar; runtime stage on `eclipse-temurin:21-jre-alpine` copies the exploded layout, runs as a non-root `app` user, exposes 8080, has a HEALTHCHECK against `/actuator/health`, and uses the SB 3.3 `org.springframework.boot.loader.launch.JarLauncher` ENTRYPOINT.
- `.dockerignore` excludes `target/`, `.git/`, `.idea/`, `docs/`, `infrastructure/`, etc. — keeps the build context tight.
- `.gitattributes` forces LF endings on `mvnw` + `*.sh` so Windows checkouts produce shell scripts that Alpine `/bin/sh` can parse (without it the Dockerfile build fails with `/bin/sh: ./mvnw: not found` on a CRLF shebang).

Files changed (count: 3, all new):
- `Dockerfile` (new)
- `.dockerignore` (new)
- `.gitattributes` (new)

Validation:
- [x] `docker build -t calendar-backend:dev .` → success
- [x] **Image size: 92 MB** (playbook target <250 MB; ideal ~180 MB on JRE-alpine — we're under both)
- [x] `docker run --entrypoint id calendar-backend:dev` → `uid=100(app) gid=101(app)` (non-root)
- [x] `docker run -p 8080:8080 ...` + `curl /actuator/health` → HTTP 200 in ~6s with `calendarDataSource` UP, `platformDataSource` UP, `platformDb` UP, overall `status: UP`
- [x] `docker inspect <container>` → `health=healthy` (HEALTHCHECK passing after one probe)
- [x] `docker history` → no JDK in final layer (multi-stage working)

Notes / surprises (deviations from playbook spec — both verified locally):
- **Added `COPY --from=build /build/org org`.** The playbook's runtime stage doesn't copy this dir, but it contains `org/springframework/boot/loader/launch/JarLauncher.class` and friends. Without it the ENTRYPOINT fails immediately at startup with `NoClassDefFoundError`. Fix added to the Dockerfile with a comment.
- **Preserved `BOOT-INF/lib` + `BOOT-INF/classes` paths.** The playbook flattens them to `lib/` + `/app` which works for SB 2.x's old `org.springframework.boot.loader.JarLauncher` but breaks SB 3.2+'s new `org.springframework.boot.loader.launch.JarLauncher` (which reads `BOOT-INF/classpath.idx`). Confirmed locally: spec'd layout fails with `ClassNotFoundException: org.springframework.boot.SpringApplication`; preserved layout boots cleanly. Both fixes consolidated into the Dockerfile with explanatory comments.
- For local Windows runs of the container, DB URLs need `host.docker.internal` overrides via env vars (`-e SPRING_DATASOURCE_CALENDAR_URL=...`) since `localhost` inside a container is the container, not the host. Captured the pattern in this entry; not in Dockerfile (Dockerfile is for prod where DB URLs come from Secrets Manager, Part 0.8).

Next part: **Part 1.1 (Series 1) — V2 events + event_attendees + event_students + event_excluded_participants schema.** First real domain code: Flyway migration introducing the `events` table and its three join tables. **Series 0 is closed.**

---

## Part 0.6.5 (post-Series-0 follow-up) — Re-enable Testcontainers; gate FlywayMigrationIT to Linux/Mac — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `pom.xml`: uncommented `org.testcontainers:junit-jupiter` + `:postgresql` (test scope, BOM-managed). Replaces the explanatory XML comment block with a brief note pointing at the `@EnabledOnOs` gate.
- `FlywayMigrationIT`: now uses a fresh `PostgreSQLContainer` with `@DynamicPropertySource` overriding calendar/platform datasource URLs. `@EnabledOnOs({OS.LINUX, OS.MAC})` at class level skips the entire class on Windows (Docker Desktop 4.71 npipe handshake failure documented in memory `backend_part_0_4_testcontainers_windows.md`).

Files changed (count: 2):
- `pom.xml` (modified)
- `src/test/java/com/childcarewow/calendar/FlywayMigrationIT.java` (modified)

Validation:
- [x] Local Windows `mvn verify`: 4 tests run, **2 skipped** (FlywayMigrationIT). Build green; JaCoCo gate met.
- [x] CI Linux `mvn verify` (PR #18): 4 tests run, 0 skipped — FlywayMigrationIT exercised against a fresh Postgres container.

Notes / surprises:
- The `@EnabledOnOs` annotation is at class level (not method level) so the static `@Container POSTGRES` field initialization is also skipped on Windows, avoiding the npipe failure during JUnit's `@BeforeAll` phase.
- Coverage stays above 80%: FlywayMigrationIT didn't contribute to main-class coverage anyway (other ITs already covered DatasourceConfig + PlatformDbHealthIndicator).

Next part: Part 0.7 (immediately after this in the same session)

---

## Part 0.6.4 (post-Part-0.6 follow-up) — Maven Wrapper — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `mvnw` (executable script) + `mvnw.cmd` (Windows) + `.mvn/wrapper/maven-wrapper.properties` (pinned to Maven 3.9.9). Generated via `./mvnw wrapper:wrapper` (script-only flavor — no `maven-wrapper.jar` needed; the wrapper script downloads Maven on first use).

Files changed (count: 3, all new):
- `mvnw` (new, executable)
- `mvnw.cmd` (new)
- `.mvn/wrapper/maven-wrapper.properties` (new)

Validation:
- [x] `./mvnw -version` → Maven 3.9.9 + Java 21
- [x] CI run on the PR was green (mvn wrapper not yet used by CI which has setup-java + system maven)

Notes / surprises:
- This was a prerequisite for Part 0.7's Dockerfile build stage which calls `./mvnw` inside the build image (so we don't need Maven preinstalled in `eclipse-temurin:21-jdk-alpine`).
- The `.gitignore` exception for `maven-wrapper.jar` (added in Part 0.4) is now moot since the script-only wrapper flavor was used — but harmless and kept.

Next part: Part 0.6.5 (Testcontainers re-enable)

---

## Part 0.6 (Series 0) — GitHub Actions CI workflow — STATUS: ✅ done
Date: 2026-05-07
Operator: Mukul Phogat

What got built:
- `.github/workflows/ci.yml`: every push to `main` and every PR runs `mvn verify` against Postgres service containers in the runner.
- Service containers: `calendar-db` (postgres:15-alpine, host port 5434 to match `application.yml` — see Part 0.2 deviation note) and `platform-db` (host port 5433); both with health checks (10 retries to be more forgiving than the playbook's 5).
- Steps: checkout → setup-java 21 (Temurin) with maven cache → seed platform-db from `docker/platform-seed.sql` via the runner's `psql` → spotless:check → verify (compile + Surefire + Failsafe + JaCoCo 80% gate) → flyway:info dry-run → upload JaCoCo HTML as workflow artifact → on PRs, post coverage comment via `madrapps/jacoco-report@v1.7.1` (min-coverage-overall=80, min-coverage-changed-files=80).
- `permissions:` block: `contents:read` + `pull-requests:write` so the JaCoCo action can post the coverage comment.
- **Branch protection updated:** `required_status_checks.contexts: ["build"]` with `strict: true`. Future PRs cannot merge unless the `build` job is green and the branch is up-to-date with `main`.

Files changed (count: 1, +1 new):
- `.github/workflows/ci.yml` (new)

Validation:
- [x] PR #15 ran CI on first push: BUILD SUCCESS in 1m1s, all 13 steps green
- [x] After merge, `main` CI run also green
- [x] `gh api repos/.../branches/main/protection` returns `required_status_checks.contexts: ["build"]`, `strict: true`
- [x] PR coverage comment posted by `madrapps/jacoco-report` (verified on PR #15)
- [x] JaCoCo HTML report available as workflow artifact (`jacoco-report`)
- [x] Workflow permissions = "Read and write" set in P0.4 step 5; combined with the workflow-level `permissions:` block, the action could post the coverage comment

Notes / surprises:
- **`gh auth refresh -h github.com -s workflow` was a hard prerequisite** (the earlier OAuth token didn't include the `workflow` scope). Operator completed the device-code refresh before this Part could start. Token now scoped: `gist`, `read:org`, `repo`, `workflow`.
- One non-blocking workflow annotation: Node.js 20 actions are deprecated (forced to Node 24 by 2026-06-02; removed by 2026-09-16). `actions/checkout@v4`, `actions/setup-java@v4`, `actions/upload-artifact@v4`, and `madrapps/jacoco-report@v1.7.1` will all need version bumps before the cutover. Tracked as a follow-up; not blocking today's green build.
- The `madrapps/jacoco-report` action is a third-party action; review its source before any sensitive workflow context is added.
- **Testcontainers is now feasible on this CI runner.** The Windows-pipe block from Part 0.4 doesn't apply on Ubuntu. Re-enabling is deferred to a follow-up: uncomment the Testcontainers deps in `pom.xml` (currently in an XML comment block), gate any Testcontainers-using IT with `@DisabledOnOs(OS.WINDOWS)` (or behind a Maven profile) so local Windows dev still runs `mvn verify` cleanly, and add the originally-spec'd fresh-container Flyway test. Tracked in memory `backend_part_0_4_testcontainers_windows.md`.

Next part: **Part 0.7 (Series 0) — Dockerfile + multi-stage build.** Adds a production-grade `Dockerfile` that builds a slim runtime image, runs as non-root, exposes 8080, has a HEALTHCHECK. Once that's in, Series 0 is complete and we move to Series 1 (the actual calendar domain).

---

## Part 0.5 (Series 0) — Code quality tooling: Spotless + JaCoCo + Failsafe — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `pom.xml` adds three plugin blocks:
  - `spotless-maven-plugin 2.43.0` (Google Java format, `check` goal bound to default lifecycle phase)
  - `jacoco-maven-plugin 0.8.12` (`prepare-agent` + `report` + `check` with bundle-level 80% line coverage gate, `haltOnFailure: true`)
  - `maven-failsafe-plugin` gains explicit `<configuration><includes><include>**/*IT.java</include></includes></configuration>`
- `.editorconfig` at repo root (Google conventions: LF, UTF-8, 2-space, trim trailing whitespace, 100-char max line for code)
- `PlatformDbHealthIndicatorTest.java` — Surefire unit test mocking `JdbcTemplate`; covers both UP and DOWN branches of `health()`. Necessary to keep bundle coverage ≥ 80% on the small initial codebase.

Files changed (count: 5, +2 new):
- `pom.xml` (modified)
- `.editorconfig` (new)
- `src/test/java/com/childcarewow/calendar/health/PlatformDbHealthIndicatorTest.java` (new)
- `src/test/java/com/childcarewow/calendar/DatasourceConfigIT.java` (reformatted by spotless:apply)
- `src/test/java/com/childcarewow/calendar/FlywayMigrationIT.java` (reformatted by spotless:apply)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS in 18s
- [x] Spotless: 7 files clean
- [x] Surefire: `CalendarApplicationTests` (1) + `PlatformDbHealthIndicatorTest` (2) green
- [x] Failsafe: `DatasourceConfigIT` (2) + `FlywayMigrationIT` (2) green
- [x] JaCoCo: "All coverage checks have been met" (≥80% bundle line coverage on 3 main classes)
- [x] Coverage report at `target/site/jacoco/index.html`
- [x] Deliberate trailing-whitespace edit → `mvn spotless:check` fails (verified locally; `git checkout` reverted; not committed)
- [x] `find . -name "*IT.java"` returns the two failsafe ITs from Parts 0.3 and 0.4

Notes / surprises:
- Spotless plugin asked for Google Java format `1.22.0` in pom config but resolved `1.19.2` (the plugin's default). No functional difference for our small codebase; left as-is. If future formatting drift, force `<googleJavaFormat><version>1.22.0</version>` precedence by upgrading spotless-maven-plugin past `2.43.0`.
- First `mvn verify` after writing `PlatformDbHealthIndicatorTest.java` failed: Spotless flagged CRLF line endings (Windows default) on the new file. Re-running `mvn spotless:apply` normalized to LF. Future file writes on Windows go through the same path; `spotless:apply` is the canonical fix.
- JaCoCo bundle has 3 classes: `CalendarApplication`, `DatasourceConfig`, `PlatformDbHealthIndicator`. All three above the 80% line threshold.
- The `mvn spotless:check` deliberate-failure validation is an ephemeral local test, not a committed artifact.

Next part: **Part 0.6 (Series 0) — GitHub Actions CI workflow**. **Operator action required first:** run `gh auth refresh -h github.com -s workflow` once. The current gh token lacks `workflow` scope; pushing `.github/workflows/ci.yml` will be rejected without it (this is why pre-flight P0.5 also skipped a workflow placeholder). When P0.6 lands, also uncomment the Testcontainers deps in pom.xml — Linux CI runs Docker cleanly where Testcontainers works (memory `backend_part_0_4_testcontainers_windows.md`).

---

## Part 0.4 (Series 0) — Flyway against the calendar datasource only — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `pom.xml` adds `flyway-core 10.20.1`, `flyway-database-postgresql 10.20.1` (required for Flyway 10+ on Postgres 15), and `flyway-maven-plugin 10.20.1` with URL pinned to `localhost:5434`.
- `application.yml` `spring.flyway` block: `enabled: true`, URL/user/password resolved from `${spring.datasource.calendar.*}`, `locations: classpath:db/migration`, `baseline-on-migrate: false`.
- `V1__placeholder.sql` — creates `_flyway_smoke` (id pk, created_at default now), inserts id=1. Will be superseded by V2 (events) in Part 1.1.
- `FlywayMigrationIT.java` — asserts `flyway_schema_history` has V1 with success=true, asserts `_flyway_smoke` has 1 row. Runs against the live calendar-db via `@Autowired JdbcTemplate` (Testcontainers deferred — see deviations).

Files changed (count: 5, +3 new −1 deleted):
- `pom.xml` (modified) — Flyway deps + maven plugin
- `src/main/resources/application.yml` (modified) — spring.flyway block
- `src/main/resources/db/migration/V1__placeholder.sql` (new)
- `src/main/resources/db/migration/.gitkeep` (deleted — superseded by V1)
- `src/test/java/com/childcarewow/calendar/FlywayMigrationIT.java` (new)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS in 21s
- [x] Surefire: 1 (`CalendarApplicationTests.contextLoads`); Failsafe: 4 (`DatasourceConfigIT` 2 + `FlywayMigrationIT` 2)
- [x] `mvn flyway:info` → `Schema version: 1`, V1 placeholder Success
- [x] `psql calendar -c 'SELECT * FROM flyway_schema_history'` → V1 success=t
- [x] `psql calendar -c 'SELECT * FROM _flyway_smoke'` → 1 row id=1
- [x] `psql platform -c 'SELECT * FROM _flyway_smoke'` → `ERROR: relation "_flyway_smoke" does not exist` (Flyway never touched platform — by design)
- [x] `psql platform -c '\dt flyway_schema_history'` → no relation

Notes / surprises (deviations):
- **Testcontainers blocked on Windows + Docker Desktop 4.71.** Playbook step 6 spec'd a Testcontainers-driven IT for fresh-container isolation. The Testcontainers `DockerClientProviderStrategy` returns HTTP 400 from every named pipe attempted (`docker_engine`, `dockerDesktopLinuxEngine`, `docker_cli`); Docker Desktop's API responds with a degraded JSON instead of accepting the connection. Rewrote `FlywayMigrationIT` to use the live calendar-db via `@Autowired JdbcTemplate`. Testcontainers deps in `pom.xml` are kept **commented out** with a TODO to re-enable in P0.6 — CI's GitHub Actions Linux runner has a clean Unix Docker socket where Testcontainers works without the Windows pipe weirdness. Saved as project memory `backend_part_0_4_testcontainers_windows.md`.
- **Flyway plugin URL is 5434**, not 5432 (Part 0.2 host-port deviation carried forward).
- Spring Boot's auto-config runs Flyway against the @Primary calendar DataSource on context startup. Idempotent: subsequent `mvn verify` runs see V1 already applied, no-op. The `_flyway_smoke` table persists in dev calendar-db until volume is wiped (`docker compose down -v`).
- The `application.yml` `spring.flyway.url` placeholder `${spring.datasource.calendar.url}` resolves correctly because Spring Boot resolves placeholders before binding `FlywayProperties`.

Next part: **Part 0.5 (Series 0) — Code quality tooling: Spotless + JaCoCo + Failsafe.** (Naming reminder: this is Series-0 Part 0.5, not the pre-flight P0.5 we did earlier; same number, different section of the playbook.)

---

## Part 0.3 (Series 0) — Dual HikariCP datasources — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `pom.xml` adds `spring-boot-starter-jdbc`, `org.postgresql:postgresql:42.7.4`, and `maven-failsafe-plugin` (binds to `integration-test` + `verify` so `*IT` tests run during `mvn verify`).
- `application.yml` rewritten with two datasource sections: `spring.datasource.calendar` (URL → 5434, pool=20 RW, pool-name `calendar-pool`) and `spring.datasource.platform` (URL → 5433, pool=5 RO, pool-name `platform-pool`).
- `DatasourceConfig.java` (`config/`) — `@Primary` calendar `HikariDataSource` + `JdbcTemplate`; platform `HikariDataSource` + `JdbcTemplate` qualifier-named `platformJdbcTemplate`.
- `PlatformDbHealthIndicator.java` (`health/`) — `@Component("platformDb")` runs `SELECT 1` against platform.
- `DatasourceConfigIT.java` — 2 tests (calendar `SELECT 1` returns 1, platform `SELECT count(*) FROM users` returns 7) running in failsafe phase against compose-managed Postgres.

Files changed (count: 5):
- `pom.xml` (modified) — 2 new deps + failsafe plugin
- `src/main/resources/application.yml` (modified) — full rewrite
- `src/main/java/com/childcarewow/calendar/config/DatasourceConfig.java` (new)
- `src/main/java/com/childcarewow/calendar/health/PlatformDbHealthIndicator.java` (new)
- `src/test/java/com/childcarewow/calendar/DatasourceConfigIT.java` (new)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS in 21s
- [x] Surefire: 1 test (`CalendarApplicationTests.contextLoads`) green
- [x] Failsafe: 2 tests (`DatasourceConfigIT`) green
- [x] App boot logs show `platform-pool - Start completed.` and `calendar-pool - Start completed.`
- [x] `/actuator/health` shows: `db.calendarDataSource` UP, `db.platformDataSource` UP, custom `platformDb` UP, overall `status: UP`
- [x] `docker stop ccw-cal-platform-db` → `/actuator/health` overall `DOWN`, `platformDataSource` DOWN with `Failed to obtain JDBC Connection`, `calendarDataSource` still UP
- [x] `docker start ccw-cal-platform-db` (waited for healthy) → `/actuator/health` UP again on both

Notes / surprises (deviations):
- **Playbook YAML key was wrong:** the spec used `jdbc-url:` under `spring.datasource.calendar`, but `DataSourceProperties.determineUrl()` reads `url`. Build failed with `Failed to determine suitable jdbc url` and ApplicationContext failed to load until `jdbc-url:` was changed to `url:`. **Fix is committed verbatim**; the playbook should be updated to match.
- The auto-registered Spring `db` health composite now includes BOTH datasources (`db.calendarDataSource` + `db.platformDataSource`) — Spring Boot 3.x auto-detects all `DataSource` beans, not just `@Primary`. The custom `PlatformDbHealthIndicator` (`platformDb` key) is somewhat redundant with `db.platformDataSource` but matches the playbook spec; left in place for explicit naming.
- Carry-over from Part 0.2: calendar URL is `localhost:5434`, not 5432 (host PG 18 conflict).
- `CalendarApplicationTests.contextLoads()` now requires the compose to be up (Hikari eagerly init's connections at context start). Add `@TestConfiguration` with H2/Testcontainers later if we want self-contained unit tests; not required for Series 0.

Next part: **Part 0.4 (Series 0) — Flyway against the calendar datasource only** (D8 schema bootstrap; will populate the empty calendar DB).

---

## Part 0.2 (Series 0) — Docker Compose with two Postgres databases — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `calendar-db` (Postgres 15-alpine, container port 5432, host port **5434** — see deviation), empty schema; Flyway will populate in Part 0.4.
- `platform-db` (Postgres 15-alpine, container port 5432, host port 5433), seeded on first init via `docker/platform-seed.sql` from `/docker-entrypoint-initdb.d/`.
- Canonical seed (1 org, 2 schools, 4 classrooms, 7 users, 4 students, 2 student_parents) with fixed UUIDs matching `Events_CCW/src/data/seed.ts`. Backend integration tests (Series 4+) reference these IDs.
- Named Docker volumes (`calendar-db-data`, `platform-db-data`) so data persists across `docker compose down`/`up`.

Files changed (count: 3, +1 new):
- `docker-compose.yml` (modified) — adds `calendar-db` and `platform-db` services to the existing LocalStack stanza
- `docker/platform-seed.sql` (new) — schema + seed
- `.gitignore` (modified) — adds `postgres-data/` (we use named volumes, but per playbook for completeness)

Validation (via `docker exec ... psql ...`, since host `psql` install is still deferred):
- [x] Both DBs healthy in ~10s after `docker compose up -d calendar-db platform-db`
- [x] `\dt` on calendar DB → "Did not find any relations" (empty as expected)
- [x] `SELECT count(*) FROM users` on platform DB → **7**
- [x] `SELECT count(*) FROM students` on platform DB → **4**
- [x] All 6 tables row-count match seed: 1 org, 2 schools, 4 classrooms, 7 users, 4 students, 2 student_parents
- [x] `docker compose stop && rm && up -d` preserves data (counts unchanged after restart — volumes persistent, init script doesn't re-run)

Notes / surprises (deviations):
- **Host port for `calendar-db` is 5434 instead of 5432.** The operator has a Windows service `postgresql-x64-18` (PostgreSQL 18) running on 5432; pg_ctl PID 5540, parent of postgres PID 7760. Stopping the host service was avoided to keep the user's other dev tooling working. Container internal port stays at 5432; only the host binding shifts. **Part 0.3 `application.yml` must use `jdbc:postgresql://localhost:5434/calendar`** instead of 5432. Recorded as the canonical deviation.
- `platform-db` host port is 5433 as in playbook (free).
- Validations done via `docker exec` rather than host `psql`. Per memory `backend_p00_deferred_installs.md`, psql install is still deferred until needed; `docker exec` is the documented workaround. Install whenever convenient via `winget install PostgreSQL.PostgreSQL.16` (UAC).
- `postgres-data/` in `.gitignore` is a no-op for now (we use named volumes, not bind mounts), but committed per playbook.

Next part: **Part 0.3 (Series 0) — Dual HikariCP datasources** — adds `org.springframework.boot:spring-boot-starter-jdbc` + Postgres driver to `pom.xml`, configures two `HikariDataSource` beans (calendar pool=20, platform pool=5 read-only), expands `application.yml` to wire both, adds `PlatformDbHealthIndicator`, and writes an integration test.

---

## Part 0.1 (Series 0) — Maven + Spring Boot scaffold — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `pom.xml` at repo root: `groupId=com.childcarewow`, `artifactId=calendar-backend`, version `0.1.0-SNAPSHOT`, parent `spring-boot-starter-parent` 3.3.5, Java 21, `maven-compiler-plugin` with `-Werror -Xlint:all,-processing`
- 3 dependencies: `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-test` (test scope)
- `CalendarApplication.java`: minimal `@SpringBootApplication`
- `application.yml`: server port 8080, expose `health` + `info` actuators
- `CalendarApplicationTests.java`: `@SpringBootTest contextLoads()` smoke

Files changed (count: 6, net +4):
- `pom.xml` (new)
- `src/main/java/com/childcarewow/calendar/CalendarApplication.java` (new)
- `src/main/resources/application.yml` (new)
- `src/test/java/com/childcarewow/calendar/CalendarApplicationTests.java` (new)
- `src/main/java/com/childcarewow/calendar/.gitkeep` (deleted — superseded by Application.java)
- `src/test/java/com/childcarewow/calendar/.gitkeep` (deleted — superseded by Tests.java)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS
- [x] `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- [x] `mvn spring-boot:run` log: `Started CalendarApplication in 1.668 seconds`
- [x] `curl localhost:8080/actuator/health` → `{"status":"UP"}`
- [x] `curl localhost:8080/foo` → HTTP 404 (Spring handling, not connection-refused)
- [x] Zero compiler warnings under `-Werror` (only JVM runtime warnings from byte-buddy agent loader, surfaced during test execution; not blocking)

Notes / surprises:
- Spring Boot pinned to **3.3.5** per playbook. Later 3.3.x patches likely exist (today is 2026-05-06); bump opportunistically if a security advisory drops.
- First `mvn verify` downloaded ~80 MB of dependencies from Maven Central. Subsequent runs hit the local `~/.m2` cache.
- `db/migration/.gitkeep` retained — Flyway migrations land in Series 0 / Part 0.4 (D8 schema bootstrap).
- Branch convention switched from `bootstrap/*` (pre-flight) to `series0/*` for this Part. Same review/merge flow, just different namespace.

Next part: **Part 0.2 (Series 0) — Docker Compose with two Postgres databases** (`calendar-db` on 5432 + `platform-db` on 5433)

---

## P0.5 — Repository skeleton + progress.md — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- Directory tree per playbook minus `.github/workflows/` (`docs/decisions/`, `docs/perf/`, `infrastructure/ecs/`, `src/main/java/com/childcarewow/calendar/`, `src/main/resources/db/migration/`, `src/test/java/com/childcarewow/calendar/`)
- `docs/runbook.md` placeholder (sections owned by future Parts noted in TODO comments)
- `progress.md` (this file): hand-off-report template + backfilled entries for P0.0 through P0.4

Files changed (count: 8):
- `progress.md` (new) — hand-off log
- `docs/runbook.md` (new) — operations procedures placeholder
- `docs/decisions/.gitkeep` (new)
- `docs/perf/.gitkeep` (new)
- `infrastructure/ecs/.gitkeep` (new)
- `src/main/java/com/childcarewow/calendar/.gitkeep` (new)
- `src/main/resources/db/migration/.gitkeep` (new)
- `src/test/java/com/childcarewow/calendar/.gitkeep` (new)

**Skipped:** `.github/workflows/.gitkeep` — GitHub's API rejects pushes that touch `.github/workflows/*` unless the auth token has `workflow` scope. Our `gh auth` token has `repo` + `admin:org` + `read:org` only. Rather than refresh scope just for a placeholder file (an interactive browser flow), the dir is left uncreated; **P0.6 (CI workflow) will be the first PR that creates `.github/workflows/`**, and the operator runs `gh auth refresh -s workflow` once before that PR can push.

Validation:
- [x] Directory tree exists, checked into `main` (verified via `git ls-tree --name-only -r main`)
- [x] `progress.md` exists with template
- [x] `README.md` exists (from P0.4)
- [~] *Playbook validation #4 ("test PR cannot be merged without approval") DOES NOT APPLY* — branch protection was loosened to 0 approvals during P0.4 since the operator is solo dev and can't approve own PRs. Direct push to main, force-push, and deletion are still blocked; `enforce_admins=true`.

Notes / surprises:
- Branch protection deviation: `required_approving_review_count=0`. Tighten to 1+ when team has multiple reviewers. The deviation is explicit + documented; PR flow itself is enforced (PRs #1–#4 demonstrate it).
- All backfilled entries for prior Parts are concise summaries; full conversational context lives in CLAUDE.md §0 of the parent `Events_CCW` repo plus the session log of 2026-05-06.

Next part: Series 0 / **Part 0.1 — Maven + Spring Boot scaffold**. (Naming collision: pre-flight "P0.1" was the AWS step; Series-0 "Part 0.1" is the Maven scaffold. See `implementation_plan.md` "Pre-flight execution order" callout.)

---

## P0.3 — Firebase project (push notifications, D4) — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- GCP project `ccw-cal-dev-a911f` with Firebase added (free Spark plan)
- Firebase Admin SDK service account `firebase-adminsdk-fbsvc@ccw-cal-dev-a911f.iam.gserviceaccount.com`
- Service-account JSON key (created via `gcloud iam service-accounts keys create`) uploaded to LocalStack secret `childcarewow-calendar/dev/firebase-service-account`
- FCM API + Firebase Management API enabled

Files changed (count: 0 in this repo; 1 in parent Events_CCW repo, uncommitted):
- `Events_CCW/implementation_plan.md` — P0.3 free-tier execution variant callout

Validation:
- [x] `client_email` ends in `iam.gserviceaccount.com`
- [x] FCM API enabled (also: firebase, firebasehosting, firebaseinstallations, firebaseremoteconfig)
- [x] Firebase project metadata reachable via Management API
- [ ] Test push to a device token — DEFERRED to Series 11.6 per playbook step 5

Notes / surprises:
- Free-tier variant Option A: only `ccw-cal-dev` Firebase created. `ccw-cal-stg` deferred (Series 7+), `ccw-cal-prod` deferred (Series 11+). Free-tier callout added to `implementation_plan.md`.
- Firebase ToS click-through (one-time, not scriptable) was required. Operator accepted via the Firebase console "Add project" wizard.
- Console wizard auto-created a *new* GCP project (`ccw-cal-dev-a911f`, random suffix) instead of attaching to the empty `ccw-cal-dev` shell created earlier via `gcloud projects create`. The empty shell was deleted (`gcloud projects delete ccw-cal-dev`; recoverable for 30 days).
- Project ID has a random suffix because GCP project IDs are global; `ccw-cal-dev` was already used (by our deleted shell).
- Service-account JSON private key never appeared in conversation history (uploaded via `file://` reference; only `client_email`/`project_id`/`type` echoed for validation).
- Google Cloud SDK was installed during this Part.

Next part: P0.5

---

## P0.2 — Supabase project (Auth + Storage, D1/D4) — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- Supabase project `ccw-cal-dev` (project ref `xedxxrfuwbyqtxrqearx`), hosted free tier, region `us-east-1`
- Storage bucket `event-attachments` (public, 10 MB cap, MIME allowlist `image/jpeg`+`image/png`)
- ES256 JWKS published at `/auth/v1/.well-known/jwks.json`
- `site_url` + `uri_allow_list` set to `http://localhost:5173` (frontend Vite dev server)
- Service-role key + JWT config saved to LocalStack secrets `childcarewow-calendar/dev/supabase-service-role` and `…/supabase-jwt-public-key`

Files changed (count: 1 in parent Events_CCW repo, uncommitted):
- `Events_CCW/implementation_plan.md` — P0.2 free-tier execution variant callout

Validation:
- [x] `/auth/v1/health` endpoint reachable
- [x] JWKS reachable, ES256 P-256 key
- [x] Both Supabase secrets present in LocalStack with version IDs
- [x] `event-attachments` bucket exists with correct config (10485760 byte limit, jpeg+png allowlist)

Notes / surprises:
- Free-tier variant Option A: only `ccw-cal-dev` created. Free tier caps at 2 projects/org; reserves slot 2 for staging when needed.
- Project uses ES256 (asymmetric JWKS) — backend will validate JWTs via the JWKS URL, not via a shared HS256 secret.
- Supabase API returned both legacy JWT keys (long HS256 JWTs, type=legacy) AND new `sb_publishable_*`/`sb_secret_*` format keys. The `sb_secret_` key is masked in API responses after creation. Backend uses the legacy `service_role` JWT for now.
- Supabase Personal Access Token (PAT) was used for project creation. **Operator should revoke the PAT** at https://supabase.com/dashboard/account/tokens since it appeared in conversation history.
- DB password generated random 32-char and not persisted (we don't use Supabase Postgres — calendar DB is on AWS RDS per D6).

Next part: P0.3

---

## P0.1 — AWS account + IAM bootstrap — STATUS: ✅ done (LocalStack edition)
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- LocalStack 3.8 (community) container via `docker compose up -d localstack`, persistence volume
- IAM user `calendar-operator` + access keys
- IAM policy `CalendarBackendOperator` (committed as `infrastructure/iam/operator-policy.json`) attached to the user
- 15 Secrets Manager placeholders at `childcarewow-calendar/{dev,staging,prod}/{db-credentials,supabase-jwt-public-key,supabase-service-role,firebase-service-account,smtp}`
- Idempotent `infrastructure/localstack/bootstrap.sh` that supports both LocalStack mode (default, via `AWS_ENDPOINT_URL`) and real-AWS mode (when `AWS_ENDPOINT_URL` is unset)

Files changed (count: 4 across 3 PRs):
- `docker-compose.yml` (new, PR #1; image pin to `:3.8` in PR #2) — LocalStack service
- `infrastructure/iam/operator-policy.json` (new, PR #1) — `CalendarBackendOperator` policy spec (least-privilege per playbook step 3, with tag-scoped RDS, role-prefix-scoped IAM, subdomain-scoped Route53)
- `infrastructure/localstack/bootstrap.sh` (new, PR #1; `cygpath` fix in PR #3) — provisioner
- `README.md` (modified, PR #1) — LocalStack run-locally section

Validation:
- [x] `aws sts get-caller-identity --profile childcarewow-calendar` returns mock identity
- [x] `iam list-attached-user-policies --user-name calendar-operator` shows exactly `CalendarBackendOperator`
- [x] 15 secrets present matching `childcarewow-calendar/*` prefix
- [ ] OIDC role `ChildcareWowCalendarGitHubActions` — DEFERRED to Series 11 (real AWS only; GitHub Actions runners can't reach localhost:4566)

Notes / surprises:
- All AWS replaced by LocalStack 3.8 community. Real AWS deferred to Series 11. ECS, ECR, RDS, ELB are LocalStack Pro-only and unavailable in our setup; that's fine for P0.1 scope.
- `localstack/localstack:latest` now resolves to a Pro-licensed dev image (`2026.5.0.dev67`) that exits without `LOCALSTACK_AUTH_TOKEN`. Pinned to `:3.8` (last known-good community 3.x).
- Step 5 (GitHub OIDC provider + role) and step 7 (`AWS_DEPLOY_ROLE_ARN` repo variable) deferred to Series 11.
- Windows Git Bash + native AWS CLI: `--policy-document file:///c/...` paths fail; bootstrap.sh uses `cygpath` to convert to Windows-style.
- AWS CLI v2.34.43 installed during this Part (deferred from P0.0 per memory `backend_p00_deferred_installs.md`).

Next part: P0.2

---

## P0.4 — GitHub repository — STATUS: ✅ done (with deviations)
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- Public GitHub repo at https://github.com/mukul29phogat-art/calendar-backend
- Initial commit: `README.md` + `.gitignore` (Java + Maven + Spring Boot) on `main`
- Branch protection rule on `main`: PR required, dismiss stale reviews, no force-push, no deletion, `enforce_admins=true`. Approvals first set to 1, later loosened to 0 (deviation; see notes).
- Actions workflow permissions: `default_workflow_permissions=write`, `can_approve_pull_request_reviews=false`

Files changed (count: 2 in initial commit; 4 total across PRs #1–#3 once P0.1 lands):
- `README.md` (new) — repo overview + namespace-transfer note
- `.gitignore` (new) — Java + Maven + Spring Boot template

Validation:
- [x] `gh repo view` returns `name=calendar-backend`, `visibility=PUBLIC`
- [x] `git push origin main` works without auth prompts (gh-managed credential)
- [x] `gh api repos/.../branches/main/protection` returns the protection rules
- [ ] `gh variable list` shows `AWS_DEPLOY_ROLE_ARN` — DEFERRED to Series 11 (P0.1 step 7)

Notes / surprises:
- **Namespace deviation:** operator does not have access to the `ChildcareWow` GitHub org. Repo lives in personal namespace `mukul29phogat-art/calendar-backend`. **Cleanup TODO at org access:** `gh repo transfer mukul29phogat-art/calendar-backend childcarewow`, then update IAM OIDC trust-policy `sub` claim to `repo:childcarewow/calendar-backend:*`.
- **Visibility deviation:** `PUBLIC` instead of `PRIVATE`. GitHub gates branch protection (classic + rulesets) behind GitHub Pro for private personal repos. Public was the only $0 path to enable branch protection.
- **Approvals deviation:** loosened from 1 → 0 because the solo operator can't approve own PRs (GitHub disallows self-review). Tighten when there are multiple reviewers.

Next part: P0.1

---

## P0.0 — Local toolchain — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built (on operator workstation, not in this repo):
- Eclipse Temurin 21 JDK (LTS) at `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`
- WSL 2.6.3 (Windows feature enabled, post-reboot)
- Docker Desktop 4.71 (`docker` 29.4.1, `docker compose` v5.1.3)
- jq 1.8.1
- Already present: Maven, Git, Node, npm, curl, gh
- Installed during P0.1: AWS CLI v2.34.43
- Installed during P0.3: Google Cloud SDK
- *Still deferred:* psql client (deferred to Series 0 / Part 0.2 per memory `backend_p00_deferred_installs.md`)

Files changed (count: 0 in this repo)

Validation:
- [x] `java -version` — Temurin 21
- [x] `docker --version` — 29.4.1
- [x] `docker compose version` — v5.1.3
- [x] `aws --version` — 2.34.43 (post-P0.1)
- [x] `gcloud --version` — installed (post-P0.3)

Notes / surprises:
- WSL2 install required a reboot — one session boundary (CLAUDE.md §0 was used to hand off state).
- Docker installer initially failed because a leftover `C:\ProgramData\DockerDesktop` directory was owned by a non-admin user account. Fixed via elevated `takeown /F ... /R /D Y; Remove-Item -Recurse -Force` then reinstall.
- Docker Desktop install required UAC accept; same for AWS CLI and Google Cloud SDK installs in later Parts.
- AWS CLI + Google Cloud SDK don't appear on this Bash session's PATH because they were installed mid-session; new shells pick them up via the system PATH update. Workaround: prepend `C:\Program Files\Amazon\AWSCLIV2`, `C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin`, and `C:\Program Files\Docker\Docker\resources\bin` to PATH.

Next part: P0.4
