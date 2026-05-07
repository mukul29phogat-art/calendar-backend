-- Notifications storage: 4 tables.
--
-- Per locked decision D11, refs to platform-owned tables (orgs, schools, users)
-- are bare uuid. Calendar-internal FKs (notification → its recipients/reads/deliveries)
-- are real Postgres FKs with ON DELETE CASCADE.
--
-- Per locked decision D4, the delivery channel enum includes PUSH (in addition to
-- APP and EMAIL) — Firebase Cloud Messaging will fan out push deliveries from
-- Series 11 onward. The architecture spec §5.7 listed only APP+EMAIL; updated here
-- to match D4.
--
-- The notification_kind enum has 8 values matching the prototype's
-- src/types/index.ts:170-178 exactly. Future additions land in a new migration.

CREATE TABLE notifications (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id                   uuid NOT NULL,                                       -- platform.organizations(id) — no FK
  school_id                uuid NOT NULL,                                       -- platform.schools(id) — no FK
  kind                     text NOT NULL CHECK (kind IN (
    'EVENT_INVITE', 'EVENT_UPDATED', 'EVENT_CANCELLED',
    'TASK_ASSIGNED', 'TASK_UPDATED', 'TASK_DELETED',
    'TASK_STATUS_CHANGED', 'TASK_OVERDUE'
  )),
  message                  text NOT NULL,
  related_entity_id        uuid,
  related_entity_title     text,
  paused                   boolean NOT NULL DEFAULT false,
  paused_reason            text,                                                -- e.g. 'Holiday: Memorial Day'
  payload                  jsonb,                                               -- channel-specific extras
  created_at               timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_school_created ON notifications(school_id, created_at DESC);

-- recipientUserIds[] in the prototype → join table.
CREATE TABLE notification_recipients (
  notification_id  uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  user_id          uuid NOT NULL,                                               -- platform.users(id) — no FK
  PRIMARY KEY (notification_id, user_id)
);
CREATE INDEX idx_notification_recipients_user ON notification_recipients(user_id);

-- readBy[] → per-recipient read tracking (separate row when read).
CREATE TABLE notification_reads (
  notification_id  uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  user_id          uuid NOT NULL,                                               -- platform.users(id) — no FK
  read_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (notification_id, user_id)
);
CREATE INDEX idx_notification_reads_user ON notification_reads(user_id);

-- Outbound delivery tracking (Phase 5 onward): one row per (notification, recipient, channel).
CREATE TABLE notification_deliveries (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id     uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  recipient_user_id   uuid NOT NULL,                                            -- platform.users(id) — no FK
  channel             text NOT NULL CHECK (channel IN ('APP', 'EMAIL', 'PUSH')),
  status              text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'PAUSED', 'SENT', 'FAILED')),
  scheduled_at        timestamptz NOT NULL,
  sent_at             timestamptz,
  attempt_count       int NOT NULL DEFAULT 0,
  last_error          text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_deliveries_due ON notification_deliveries(status, scheduled_at)
  WHERE status IN ('QUEUED', 'PAUSED');
