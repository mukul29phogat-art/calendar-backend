-- Tasks: internal to-do items with optional recurrence and per-occurrence overrides.
--
-- Per locked decision D11, references to platform-owned tables (organizations,
-- schools, classrooms, users) are bare uuid columns with SQL comments — NOT real
-- foreign keys (cross-DB constraints not possible). Real Postgres FKs ARE used
-- where both tables live in the calendar DB:
--   tasks.recurrence_id            -> recurrence_rules(id)
--   task_instance_overrides.task_id -> tasks(id) ON DELETE CASCADE
--
-- Enum-shaped columns are TEXT + CHECK rather than Postgres ENUM types (Part 1.1
-- pattern), to avoid the migration pain of `ALTER TYPE ... ADD VALUE`.
--
-- Status values match the prototype: TODO / IN_PROGRESS / DONE (NOT 'COMPLETED').

CREATE TABLE recurrence_rules (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  cycle                 text NOT NULL CHECK (cycle IN ('DAILY', 'WEEKLY', 'MONTHLY')),
  due_day_of_week       smallint CHECK (due_day_of_week BETWEEN 0 AND 6),    -- 0=Sun per JS Date.getDay()
  due_day_of_month      smallint CHECK (due_day_of_month BETWEEN 1 AND 31),
  due_time              time,                                                 -- 'HH:mm'
  until_date            date NOT NULL,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_weekly_dow  CHECK (cycle <> 'WEEKLY'  OR due_day_of_week  IS NOT NULL),
  CONSTRAINT chk_monthly_dom CHECK (cycle <> 'MONTHLY' OR due_day_of_month IS NOT NULL)
);

CREATE TABLE tasks (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id                  uuid NOT NULL,                                     -- platform.organizations(id) — no FK
  school_id               uuid NOT NULL,                                     -- platform.schools(id) — no FK
  parent_task_group_id    uuid,                                              -- shared across N fanned-out rows; nullable
  title                   text NOT NULL CHECK (char_length(title) <= 120),
  description             text,
  classroom_id            uuid,                                              -- platform.classrooms(id) — no FK; UI filter hint
  assignee_user_id        uuid NOT NULL,                                     -- platform.users(id) — no FK; multi-assignee = N rows
  due_date                date NOT NULL,
  due_time                time,
  status                  text NOT NULL DEFAULT 'TODO'   CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
  priority                text NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
  recurrence_id           uuid REFERENCES recurrence_rules(id),              -- calendar-owned, real FK; per D9 each fanned-out row gets its own
  created_by_user_id      uuid NOT NULL,                                     -- platform.users(id) — no FK
  updated_by_user_id      uuid,                                              -- platform.users(id) — no FK
  deleted_at              timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_tasks_school_assignee ON tasks(school_id, assignee_user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_school_due_date ON tasks(school_id, due_date)         WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_school_status   ON tasks(school_id, status)           WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_group           ON tasks(parent_task_group_id)        WHERE parent_task_group_id IS NOT NULL;

CREATE TABLE task_instance_overrides (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id           uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,    -- calendar-owned, real FK
  occurrence_date   date NOT NULL,
  status            text CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),  -- nullable: NULL means "no status override"
  title             text,                                                    -- nullable override
  due_time          time,
  skipped           boolean NOT NULL DEFAULT false,
  notes             text,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (task_id, occurrence_date)
);
