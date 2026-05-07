-- Cross-cutting tables: idempotency replay (§5.8) + COPPA audit trail (§5.9).
-- Combining in one migration is fine — they're independent.

-- §5.8 Idempotency keys for safe POST retries.
-- The application sends Idempotency-Key on every POST create endpoint; the table
-- stores the cached response so a retry returns the same body without re-running
-- the side effects. expires_at defaults to now()+24h; nightly purge runs in Phase 3.13.
CREATE TABLE idempotency_keys (
  key            text PRIMARY KEY,
  request_hash   text NOT NULL,
  response_body  jsonb NOT NULL,
  status_code    int  NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  expires_at     timestamptz NOT NULL DEFAULT (now() + interval '24 hours')
);
CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);

-- §5.9 Audit events for COPPA compliance. Insert-only — no UPDATE/DELETE in code.
-- Application-layer immutability via @Immutable lands in Phase 3.4; the schema
-- doesn't enforce immutability (which would require revoking UPDATE/DELETE
-- privileges per role; out of scope here).
--
-- Per D11, refs to platform-owned tables (users) are bare uuid columns.
CREATE TABLE audit_events (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_user_id   uuid,                                          -- platform.users(id) — no FK
  action          text NOT NULL,                                 -- e.g. 'STUDENT_VIEW', 'AUTH_LOGIN'
  target_type     text,                                          -- e.g. 'STUDENT'
  target_id       uuid,
  ip_address      inet,
  user_agent      text,
  metadata        jsonb,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor  ON audit_events(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_target ON audit_events(target_type, target_id, created_at DESC);
