-- Reference schemas owned by the platform team in production. In dev, this fixture
-- gives the calendar service something to read against. NOT created by Flyway.
-- See implementation_plan.md Part 0.2 step 2 for the spec; UUIDs are canonical.

CREATE TABLE organizations (
  id          uuid PRIMARY KEY,
  name        text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE schools (
  id            uuid PRIMARY KEY,
  org_id        uuid NOT NULL REFERENCES organizations(id),
  name          text NOT NULL,
  timezone      text NOT NULL,
  country_code  char(2) NOT NULL DEFAULT 'US',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
  id          uuid PRIMARY KEY,
  org_id      uuid NOT NULL REFERENCES organizations(id),
  name        text NOT NULL,
  email       text NOT NULL UNIQUE,
  role        text NOT NULL CHECK (role IN ('ORG_ADMIN','SCHOOL_ADMIN','STAFF','PARENT')),
  designation text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_schools (
  user_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  school_id uuid NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, school_id)
);

CREATE TABLE classrooms (
  id          uuid PRIMARY KEY,
  org_id      uuid NOT NULL REFERENCES organizations(id),
  school_id   uuid NOT NULL REFERENCES schools(id),
  name        text NOT NULL,
  color       text,
  deleted_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE classroom_staff (
  classroom_id uuid NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (classroom_id, user_id)
);

CREATE TABLE students (
  id                          uuid PRIMARY KEY,
  org_id                      uuid NOT NULL REFERENCES organizations(id),
  school_id                   uuid NOT NULL REFERENCES schools(id),
  classroom_id                uuid NOT NULL REFERENCES classrooms(id),
  name                        text NOT NULL,
  dob                         date,
  consent_attested_at         timestamptz,
  consent_attested_by_user_id uuid,
  deleted_at                  timestamptz,
  created_at                  timestamptz NOT NULL DEFAULT now(),
  updated_at                  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE student_parents (
  student_id uuid NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (student_id, user_id)
);

-- Seed data — fixed UUIDs match the prototype seed (refer to src/data/seed.ts).
-- Use a small, stable set so integration tests can reference these IDs.
INSERT INTO organizations (id, name) VALUES
  ('11111111-1111-1111-1111-111111111111', 'ChildcareWow Demo Org');

INSERT INTO schools (id, org_id, name, timezone) VALUES
  ('22222222-2222-2222-2222-222222222221', '11111111-1111-1111-1111-111111111111', 'Sunrise Preschool',  'America/New_York'),
  ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'Maplewood Preschool','America/Chicago');

INSERT INTO users (id, org_id, name, email, role, designation) VALUES
  ('33333333-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Olivia Park',    'olivia@ccw-demo.test',    'ORG_ADMIN',    'Owner'),
  ('33333333-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Ravi Mehta',     'ravi@ccw-demo.test',      'SCHOOL_ADMIN', 'Sunrise Director'),
  ('33333333-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'Sara Kim',       'sara@ccw-demo.test',      'SCHOOL_ADMIN', 'Maplewood Director'),
  ('33333333-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'Maya Diallo',    'maya@ccw-demo.test',      'STAFF',        'Lead Teacher'),
  ('33333333-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'Tom Becker',     'tom@ccw-demo.test',       'STAFF',        'Assistant Teacher'),
  ('33333333-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', 'Priya Singh',    'priya@parent.test',       'PARENT',       NULL),
  ('33333333-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111', 'Daniel Cho',     'daniel@parent.test',      'PARENT',       NULL);

INSERT INTO user_schools (user_id, school_id) VALUES
  ('33333333-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222221'),
  ('33333333-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222'),
  ('33333333-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222221'),
  ('33333333-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222'),
  ('33333333-0000-0000-0000-000000000004', '22222222-2222-2222-2222-222222222221'),
  ('33333333-0000-0000-0000-000000000005', '22222222-2222-2222-2222-222222222221'),
  ('33333333-0000-0000-0000-000000000006', '22222222-2222-2222-2222-222222222221'),
  ('33333333-0000-0000-0000-000000000007', '22222222-2222-2222-2222-222222222222');

INSERT INTO classrooms (id, org_id, school_id, name, color) VALUES
  ('44444444-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', 'Butterflies', 'classroom-blue'),
  ('44444444-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', 'Caterpillars','classroom-yellow'),
  ('44444444-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'Sunbeams',    'classroom-orange'),
  ('44444444-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'Stars',       'classroom-pink');

INSERT INTO classroom_staff (classroom_id, user_id) VALUES
  ('44444444-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000004'),
  ('44444444-0000-0000-0000-000000000002', '33333333-0000-0000-0000-000000000005'),
  ('44444444-0000-0000-0000-000000000003', '33333333-0000-0000-0000-000000000004');

INSERT INTO students (id, org_id, school_id, classroom_id, name, dob) VALUES
  ('55555555-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', '44444444-0000-0000-0000-000000000001', 'Aanya Singh', '2021-04-12'),
  ('55555555-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', '44444444-0000-0000-0000-000000000002', 'Jordan Becker','2020-11-03'),
  ('55555555-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '44444444-0000-0000-0000-000000000003', 'Lila Cho',     '2021-08-25'),
  ('55555555-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '44444444-0000-0000-0000-000000000004', 'Noah Diallo',  '2020-02-29');

INSERT INTO student_parents (student_id, user_id) VALUES
  ('55555555-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000006'),
  ('55555555-0000-0000-0000-000000000003', '33333333-0000-0000-0000-000000000007');
