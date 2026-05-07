# Migrations

Flyway-managed migrations for the calendar datasource. Run via Spring Boot autoconfig at app startup or `mvn flyway:migrate` / `mvn flyway:info` from the CLI.

## Conventions

- **Forward-only.** Once a migration is in `flyway_schema_history` of any environment (dev/staging/prod), do **NOT** edit it — Flyway will refuse to migrate. Rollbacks are new forward migrations (V9, V10, ...).
- **Idempotent assumption.** Spring Boot runs Flyway on every startup. Migrations must be safe to re-encounter (already-applied rows are skipped via `flyway_schema_history`).
- **No platform-DB FKs.** Per locked decision D11, references to platform-owned tables (organizations, schools, classrooms, students, users) are bare `uuid` columns with SQL comments — never `REFERENCES`. Real Postgres FKs are used only between calendar-owned tables.
- **TEXT + CHECK over Postgres ENUM.** Adding values to a Postgres enum requires `ALTER TYPE ... ADD VALUE` which is non-transactional and migration-unfriendly. All Series 1 enums are TEXT columns with CHECK constraints.

## Migration log

| Version | What it adds | Data risk | Rollback approach |
|---|---|---|---|
| **V1** | `_flyway_smoke` (single-row placeholder verifying Flyway wiring) | None | Forward-only: drop in a future migration when no longer needed |
| **V2** | `events` + 3 calendar-owned join tables (`event_attendees`, `event_students`, `event_excluded_participants`); 4 CHECK constraints; 3 partial indexes filtered by `deleted_at IS NULL` | Adds tables only; no data risk | Forward-only: `V9__rollback_events.sql` with `DROP TABLE` if ever needed |
| **V3** | `recurrence_rules`, `tasks`, `task_instance_overrides`; 5 CHECK constraints (cycle enum + dow/dom range + weekly_dow + monthly_dom); 4 partial indexes; tasks→recurrence_rules real FK; overrides→tasks `ON DELETE CASCADE` | Adds tables only | Forward-only |
| **V4** | `holidays` + the **named** partial unique index `uq_holidays_federal_pending` that the Phase 6.7 Nager.Date upsert targets via `ON CONFLICT ON CONSTRAINT`; standard "one approved per (school, date)" unique index | Adds tables only | Forward-only. Renaming `uq_holidays_federal_pending` is a **breaking change** — the upsert references it by name. |
| **V5** | `important_dates` (TEXT+CHECK kind BIRTHDAY/IMPORTANT; orthogonal `visible_to_parents` boolean gate) | Adds tables only | Forward-only |
| **V6** | `conflict_flags` (TEXT+CHECK entity_type + conflict_type; bidirectional double-booking enforced at service layer per arch spec §7.3) | Adds tables only | Forward-only |
| **V7** | `notifications` + 3 join tables (`notification_recipients`, `notification_reads`, `notification_deliveries`); JSONB `payload`; delivery channel enum **includes PUSH** per locked D4 (was APP+EMAIL only in arch spec §5.7) | Adds tables only | Forward-only |
| **V8** | `idempotency_keys` (text PK, JSONB response_body, default `expires_at = now() + 24h`, `idx_idempotency_expires` for the Phase 3.13 nightly purge) + `audit_events` (UUID PK, JSONB metadata, INET ip_address, two indexes for actor+target lookups) | Adds tables only; audit is insert-only enforced at app layer (Phase 3.4 `@Immutable`), not at DB layer | Forward-only |

## Validation

`mvn flyway:info` from the repo root with the local compose stack up shows all 8 versioned migrations as `Success`. CI (PR + main) re-runs the full migration set against a fresh Testcontainers Postgres on every push (via `FlywayMigrationIT` in `src/test/java/com/childcarewow/calendar/`).

A clean-room migration test:

```bash
docker compose down -v
docker compose up -d calendar-db platform-db
# wait for healthchecks
./mvnw -B flyway:migrate
./mvnw -B flyway:info  # all 8 should show Success
```

End-to-end time on a developer laptop is < 30 seconds.

## Inter-migration dependencies

None as of V8: no calendar-table → calendar-table FKs cross migration boundaries. Within-migration FKs (V2 events ↔ event_*; V3 tasks ↔ task_instance_overrides + tasks → recurrence_rules; V7 notifications ↔ recipients/reads/deliveries) are all defined in the same SQL file.

When a future migration introduces a cross-migration FK (e.g., a hypothetical `event_holiday_override` linking `events` and `holidays`), verify the parent migration ran first by checking its `flyway_schema_history` row before the new one.
