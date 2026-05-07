-- Events: the core domain table for the School Operations Calendar.
-- Per locked decision D11, every reference to platform-owned tables
-- (organizations, schools, classrooms, students, users) is a bare uuid
-- column with a SQL comment — NOT a real foreign key — because those
-- tables live in a separate platform DB and FK constraints can't span
-- databases.
--
-- Per playbook Part 1.1 step 5 / Common Failure Points: enum is
-- represented as text with a CHECK constraint instead of a Postgres
-- ENUM type, to avoid the migration pain of altering enum values
-- (ALTER TYPE ... ADD VALUE is non-transactional and PG-only).

CREATE TABLE events (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id              uuid NOT NULL,                  -- platform.organizations(id) — no FK across DBs (D2/Q1)
  school_id           uuid NOT NULL,                  -- platform.schools(id) — no FK
  type                text NOT NULL CHECK (type IN ('CLASSROOM', 'CUSTOM', 'SCHOOL')),
  title               text NOT NULL CHECK (char_length(title) <= 120),
  description         text,
  classroom_id        uuid,                            -- platform.classrooms(id) — no FK
  start_dt            timestamptz NOT NULL,
  end_dt              timestamptz NOT NULL,
  all_day             boolean NOT NULL DEFAULT false,
  organizer_user_id   uuid,                            -- platform.users(id) — no FK
  invite_parents      boolean NOT NULL DEFAULT false,
  attachment_name     text,
  attachment_url      text,
  created_by_user_id  uuid NOT NULL,                  -- platform.users(id) — no FK
  updated_by_user_id  uuid,                            -- platform.users(id) — no FK
  deleted_at          timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_event_time_range CHECK (end_dt > start_dt),
  CONSTRAINT chk_event_classroom_required CHECK ((type <> 'CLASSROOM') OR (classroom_id IS NOT NULL))
);

CREATE INDEX idx_events_school_start ON events(school_id, start_dt) WHERE deleted_at IS NULL;
CREATE INDEX idx_events_organizer    ON events(organizer_user_id)   WHERE deleted_at IS NULL;
CREATE INDEX idx_events_classroom    ON events(classroom_id)        WHERE deleted_at IS NULL;

CREATE TABLE event_students (
  event_id    uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  student_id  uuid NOT NULL,                                            -- platform.students(id) — no FK
  PRIMARY KEY (event_id, student_id)
);
CREATE INDEX idx_event_students_student ON event_students(student_id);

CREATE TABLE event_attendees (
  event_id    uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL,                                            -- platform.users(id) — no FK
  PRIMARY KEY (event_id, user_id)
);
CREATE INDEX idx_event_attendees_user ON event_attendees(user_id);

CREATE TABLE event_excluded_participants (
  event_id          uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  participant_id    uuid NOT NULL,
  participant_type  text NOT NULL CHECK (participant_type IN ('USER', 'STUDENT')),
  PRIMARY KEY (event_id, participant_id)
);
