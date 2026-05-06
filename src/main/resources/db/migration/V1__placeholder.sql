-- Placeholder — verifies Flyway runs against the calendar DB only.
-- Will be removed (or superseded) when V2 (events) lands in Part 1.1.

CREATE TABLE _flyway_smoke (
  id          int PRIMARY KEY,
  created_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO _flyway_smoke (id) VALUES (1);
