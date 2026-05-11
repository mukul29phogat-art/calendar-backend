-- Lock table for ShedLock (net.javacrumbs.shedlock). Coordinates @Scheduled jobs
-- across multi-instance ECS so each scheduled tick runs on exactly one instance.
--
-- Wired by Part 6.7. Two jobs use it today: FederalHolidaySyncJob (NAGER_SYNC)
-- and IdempotencyPurgeJob (IDEMPOTENCY_PURGE). Future @Scheduled methods just
-- annotate with @SchedulerLock(name="...") — the table is shared.
--
-- Schema is dictated by ShedLock's JdbcTemplateLockProvider; do NOT add columns
-- or change types without consulting the ShedLock docs.

CREATE TABLE shedlock (
  name        varchar(64) PRIMARY KEY,
  lock_until  timestamptz NOT NULL,
  locked_at   timestamptz NOT NULL,
  locked_by   varchar(255) NOT NULL
);
