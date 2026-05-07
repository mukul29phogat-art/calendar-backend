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
