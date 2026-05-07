-- Conflict flags: soft, non-blocking warnings attached to events/tasks for
-- HOLIDAY / DOUBLE_BOOKING / RESOURCE conflicts.
--
-- BIDIRECTIONAL note: for DOUBLE_BOOKING, the SoftFlagService inserts TWO rows
-- (A → B with entity_id=A and conflicting_entity_id=B; plus B → A with
-- entity_id=B and conflicting_entity_id=A) so each side independently surfaces
-- the warning. The DDL doesn't enforce this — it just permits two rows. The
-- "if A→B exists then B→A also exists" invariant lives in the service layer
-- (architecture spec §7.3).
--
-- For HOLIDAY: entity_id = the event/task being flagged; conflicting_entity_id
--             = the holiday id.
-- For RESOURCE (deferred to a later Part): same shape as DOUBLE_BOOKING.
--
-- Per D11, refs to platform-owned tables (orgs, schools, users) are bare uuid.
-- entity_id / conflicting_entity_id are bare uuid because they polymorphically
-- reference events OR tasks OR holidays — a real FK is impossible.

CREATE TABLE conflict_flags (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id                   uuid NOT NULL,                                       -- platform.organizations(id) — no FK
  school_id                uuid NOT NULL,                                       -- platform.schools(id) — no FK
  entity_type              text NOT NULL CHECK (entity_type IN ('EVENT', 'TASK')),
  entity_id                uuid NOT NULL,                                       -- the flagged calendar item
  conflict_type            text NOT NULL CHECK (conflict_type IN ('HOLIDAY', 'DOUBLE_BOOKING', 'RESOURCE')),
  conflicting_entity_id    uuid,                                                -- the OTHER item, or the holiday id
  message                  text NOT NULL,
  dismissed                boolean NOT NULL DEFAULT false,
  dismissed_by_user_id     uuid,                                                -- platform.users(id) — no FK
  dismissed_at             timestamptz,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now()
);

-- Read paths: list active flags by entity, list flags pointing at a holiday.
CREATE INDEX idx_conflict_flags_entity  ON conflict_flags(entity_type, entity_id)  WHERE dismissed = false;
CREATE INDEX idx_conflict_flags_holiday ON conflict_flags(conflicting_entity_id)   WHERE conflict_type = 'HOLIDAY';
