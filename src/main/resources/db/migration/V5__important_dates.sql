-- Important dates: birthdays + arbitrary important entries on the calendar.
--
-- visible_to_parents is the orthogonal parent-visibility gate; kind is what the
-- entry is (BIRTHDAY vs IMPORTANT). A birthday with visible_to_parents=false is
-- admin-only; an IMPORTANT with visible_to_parents=true is parent-visible.
--
-- The architecture spec deliberately does NOT enforce student_id-required-for-BIRTHDAY
-- at the DB layer — that's a service-layer concern. Verified by an IT in this Part.
--
-- Per D11, refs to platform-owned tables (organizations, schools, students, users)
-- are bare uuid columns with SQL comments. TEXT+CHECK over Postgres ENUM (Series 1
-- pattern).

CREATE TABLE important_dates (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id              uuid NOT NULL,                                              -- platform.organizations(id) — no FK
  school_id           uuid NOT NULL,                                              -- platform.schools(id) — no FK
  date                date NOT NULL,
  label               text NOT NULL CHECK (char_length(label) <= 120),
  kind                text NOT NULL CHECK (kind IN ('BIRTHDAY', 'IMPORTANT')),
  student_id          uuid,                                                       -- platform.students(id) — no FK; for BIRTHDAY entries
  visible_to_parents  boolean NOT NULL DEFAULT false,                             -- gate for parent visibility
  created_by_user_id  uuid,                                                       -- platform.users(id) — no FK
  deleted_at          timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_important_school_date ON important_dates(school_id, date) WHERE deleted_at IS NULL;
CREATE INDEX idx_important_student     ON important_dates(student_id)      WHERE student_id IS NOT NULL;
