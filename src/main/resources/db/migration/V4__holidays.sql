-- Holidays. Two sources: CUSTOM (school-defined) and FEDERAL (synced from Nager.Date).
-- Federal holidays land with approved=false and require admin approval before they
-- block scheduling.
--
-- Per locked decision D11, refs to platform-owned tables (organizations, schools,
-- users) are bare uuid columns with SQL comments — no real FKs across DBs.
--
-- Enum (CUSTOM/FEDERAL) is TEXT+CHECK rather than Postgres ENUM (Series 1 pattern).

CREATE TABLE holidays (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id              uuid NOT NULL,                                              -- platform.organizations(id) — no FK
  school_id           uuid NOT NULL,                                              -- platform.schools(id) — no FK; always required, including federals
  date                date NOT NULL,
  name                text NOT NULL CHECK (char_length(name) <= 120),
  notes               text,                                                       -- prototype field name (not 'description')
  source              text NOT NULL CHECK (source IN ('CUSTOM', 'FEDERAL')),
  approved            boolean NOT NULL DEFAULT false,
  approved_by_user_id uuid,                                                       -- platform.users(id) — no FK
  approved_at         timestamptz,
  created_by_user_id  uuid,                                                       -- platform.users(id) — no FK
  deleted_at          timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);

-- Uniqueness: one APPROVED holiday per (school, date). Pending federals can coexist
-- with an existing approved CUSTOM on the same date — the approve action then fails
-- with DUPLICATE_HOLIDAY at the service layer.
CREATE UNIQUE INDEX uq_holiday_school_date_approved
  ON holidays(school_id, date)
  WHERE approved = true AND deleted_at IS NULL;

-- Named partial unique index used by the Nager.Date sync upsert (Phase 6.7).
-- Postgres ON CONFLICT cannot reference a partial-index predicate inline; it must
-- target a named index. The exact spelling (uq_holidays_federal_pending) is part
-- of the upsert's API contract — do NOT rename without updating §7.8 of the
-- architecture spec and the sync job query.
CREATE UNIQUE INDEX uq_holidays_federal_pending
  ON holidays(school_id, date)
  WHERE source = 'FEDERAL' AND approved = false AND deleted_at IS NULL;

CREATE INDEX idx_holidays_school_date ON holidays(school_id, date)               WHERE deleted_at IS NULL;
CREATE INDEX idx_holidays_pending     ON holidays(school_id, date)               WHERE source = 'FEDERAL' AND approved = false;
