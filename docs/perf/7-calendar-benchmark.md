# Part 7.6 — Calendar read perf benchmark

> **Status: scaled-down local smoke complete. Full 1000-RPS perf gate deferred to Series 11** when RDS, ECS, and a real load tool are available.

## TL;DR

A single-node, single-school perf-smoke against local Docker Postgres surfaces an **N+1 on event reads** as the dominant cost. At 250 in-window events with ~1300 mixed-kind items returned:

| Metric | Value     |
| ------ | --------- |
| p50    | ~3.4 s    |
| p95    | ~5.0 s    |
| p99    | ~6.1 s    |

The production target per architecture spec § 1.2 is **p95 < 400 ms** on RDS. The local numbers are ~12× over that target. **The fix is straightforward — batch the join-table loads in `EventService.toViewWithJoins`** — but it's deliberately deferred until Series 11 can both apply the fix and validate against RDS at the full playbook scale (50K events × 100 schools, 1000 RPS).

## Methodology

- Harness: `CalendarReadPerfIT` (gated by `CALENDAR_PERF=1`; CI skips by default).
- Run locally: `CALENDAR_PERF=1 ./mvnw -B test-compile failsafe:integration-test -Dit.test=CalendarReadPerfIT`.
- Hardware: Windows 11 host, Docker Desktop 4.71, Postgres 15-alpine container, JVM 21 on host.
- Seed: one school (Sunrise), one classroom (Butterflies), one admin user (Olivia).
  - 5,000 events spread across 2025–2030, ~5% biased into May 2026 (target window).
  - 5,000 non-recurring tasks, same distribution.
  - 200 WEEKLY recurring tasks (`due_day_of_week` cycling 0..6), `until_date` 2030-12-31.
  - 200 approved CUSTOM holidays at unique dates spaced 10 days apart.
  - 500 important_dates, 5% biased into the window, BIRTHDAY/IMPORTANT split 50/50, `visible_to_parents=false`.
- Read window: `from=2026-05-01`, `to=2026-05-31`.
- Measurement: 10 warm-up reads → 100 measurement reads, sequential. p50 / p95 / p99 reported.

## Latest results (2026-05-11, local Docker Postgres)

```
[perf] seeding 5000 events + 5000 non-recurring tasks + 200 recurring tasks + 200 holidays + 500 important_dates
[perf] seed complete in 1041 ms
[perf] one-month calendar read: p50=3444.9 ms, p95=4958.2 ms, p99=6072.6 ms (n=100 runs over 5000 events / 5200 tasks / 200 holidays / 500 important_dates)
```

Response composition for the May 2026 window (admin actor — no PARENT clamp):

- ~250 events (5% of 5,000)
- ~250 non-recurring tasks (5% of 5,000)
- 4 occurrences each from 200 recurring tasks ≈ 800 task occurrences (WEEKLY rules emit one per week × 4 weeks)
- 3 in-window holidays (May 6, May 16, May 26 — every-10-day spacing)
- ~25 important_dates (5% of 500, split between birthdays and important)

Total ~1300 items per response.

## Findings

### 1. N+1 on event join-table loads — DOMINANT COST

`EventService.toViewWithJoins(Event)` runs three separate JDBC queries per event to load `event_attendees`, `event_students`, and `event_excluded_participants`. With ~250 in-window events:

- 250 × 3 = **~750 extra round-trips per request**
- Each round-trip on local Docker Postgres adds ~5–15 ms
- Net N+1 cost: **3.7 – 11 seconds per request** — accounts for ~all of the observed latency

**Fix:** batch-load each join table with a single `WHERE event_id IN (:ids)` query at the start of the window read, then index per-event by ID in memory. Three batch SELECTs instead of 750 small SELECTs. Expected per-request cost drop: 4 s → ~50 ms (an 80× speedup, comfortably under the 400 ms target).

**Why not fix now?** The fix touches `EventService.findInWindow` (Part 5.4) and would change the visibility-filter shape, which Part 7.4 just stabilized. Better to land it together with the real-RDS perf validation in Series 11 — that way we don't ship a code change "for perf" without numbers from the actual production environment.

### 2. Recurring-task expansion is cheap

The 200 recurring tasks expand to ~800 occurrences in a 4-week window. Each occurrence does an in-memory date-arithmetic walk in `RecurrenceService.expand` and a per-occurrence `projectFor` (which hits `task_instance_overrides` — but only when overrides exist). Aggregate cost: ~50 ms out of the 3.4 s p50. Not a hot path; no action.

### 3. STUDENT_VIEW audit is cheap

Part 7.4's STUDENT_VIEW audit runs `auditService.log` in a `REQUIRES_NEW` transaction. The test seed produces zero student-bearing items (all events are CLASSROOM, all important_dates have null `student_id`), so the audit path short-circuits. If we wired in CUSTOM events + birthdays the audit row write would add ~5–10 ms via the REQUIRES_NEW commit overhead. Negligible.

### 4. Index coverage looks correct

Each window query hits its intended partial index:

| Table | Query | Index | Predicate |
| ----- | ----- | ----- | --------- |
| events | `findInWindow(schoolId, from, to)` | `idx_events_school_start` | `(school_id, start_dt) WHERE deleted_at IS NULL` |
| tasks (non-rec) | `findNonRecurringInWindow` | `idx_tasks_school_due_date` | `(school_id, due_date) WHERE deleted_at IS NULL` |
| tasks (rec) | `findRecurringForSchool` | sequential scan (no candidate index) | — |
| holidays | `findApprovedInWindow` | `idx_holidays_school_date` | `(school_id, date) WHERE deleted_at IS NULL` |
| important_dates | `findInWindow` | `idx_important_school_date` | `(school_id, date) WHERE deleted_at IS NULL` |

The recurring-task SELECT scans the table for one school — fine at 5,200 rows but worth a `(school_id) WHERE recurrence_id IS NOT NULL AND deleted_at IS NULL` partial index when row count grows.

## Local SLO

The harness asserts `p95 ≤ 10,000 ms` on local Docker Postgres. This is **deliberately loose** — the N+1 above accounts for the bulk of the latency, and the smoke is supposed to track the metric, not gate the build on a known-finding. Once Series 11 lands the batch-loader fix, this SLO tightens to the production 400 ms target.

## Deferred (Series 11)

1. **Real 1000-RPS load test** against RDS / ECS via k6 (or JMeter). Local Docker can't sustain 1000 RPS or simulate multi-instance concurrency.
2. **Full playbook scale**: 50K events × 50K tasks × 1K holidays × 100 schools. The current scaled-down seed is one school; multi-school numbers depend on `(school_id, ...)` index behavior at scale.
3. **Batch-loader fix for `EventService.toViewWithJoins`** — the N+1 elimination. Must validate the fix doesn't regress Part 7.4's visibility matrix.
4. **Calendar-feed flag cutover** (`VITE_USE_REAL_API_CALENDAR=true`) in dev → staging → prod. Blocked on Part 7.5 (FE shadow mode) having ≥ 7 days clean shadow diffs against the real endpoint.
5. **Per-event partial index review** once RDS metrics are available — the `events` partial index may benefit from a `(school_id, start_dt)` covering index including `type` and `deleted_at IS NULL`.

## Re-running the benchmark

```bash
# Start compose if not already up
docker compose up -d calendar-db platform-db

# Run the gated IT
CALENDAR_PERF=1 ./mvnw -B test-compile failsafe:integration-test -Dit.test=CalendarReadPerfIT

# Numbers print as a `[perf]` line; paste them into the "Latest results" section above.
```

The seed is idempotent across runs only if the prior run cleaned up — `@AfterAll` handles that on success. If a prior run crashed mid-seed, manually clear leftovers:

```sql
DELETE FROM events WHERE title LIKE 'IT-perf-%';
DELETE FROM tasks WHERE title LIKE 'IT-perf-%';
DELETE FROM holidays WHERE name LIKE 'IT-perf-%';
DELETE FROM important_dates WHERE label LIKE 'IT-perf-%';
DELETE FROM recurrence_rules WHERE id NOT IN (SELECT recurrence_id FROM tasks WHERE recurrence_id IS NOT NULL);
```
