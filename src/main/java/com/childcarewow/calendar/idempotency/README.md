# Idempotency middleware

Implements the `Idempotency-Key` contract from architecture spec § 6.10 and § 7.9.

## Storage key

Clients send an `Idempotency-Key` header with an opaque value (typically a UUID). The cache key
**persisted in `idempotency_keys`** is:

```
SHA-256(actor.id() + ":" + clientIdempotencyKey)
```

i.e. **scoped per user**. Two clients sending the same UUID hit different rows. This matters
because:

- Idempotency-Key values are not cryptographically random — clients have used `now()` + counter
  tricks, and collisions across users are common.
- Without scoping, an attacker who guesses another user's key could read their cached create
  response (which contains the new entity's ID and details).

## Handled routes

The filter only applies to the listed POST creates:

- `POST /api/v1/events`
- `POST /api/v1/tasks`
- `POST /api/v1/holidays`
- `POST /api/v1/important-dates`
- `POST /api/v1/attachments/sign-upload`

Everything else passes through. In particular `PATCH /api/v1/tasks/{id}/status` is **not** an
idempotent surface — it intentionally lets clients retry status changes without replay caching.

## Behavior

| Condition                               | Result                                                                    |
| --------------------------------------- | ------------------------------------------------------------------------- |
| Header missing/blank                    | Pass through, no caching.                                                 |
| Route not in allowlist                  | Pass through, no caching.                                                 |
| No authenticated principal              | Pass through (auth filter rejects later).                                 |
| Cached row, same body hash              | Return cached status + body; no controller invocation; no DB writes.      |
| Cached row, different body hash         | `409 IDEMPOTENCY_REPLAY` (envelope per § 15).                             |
| No cached row                           | Run the controller, then persist `(scopedKey, requestHash, response)` for `2xx` only. |
| Persist failure                         | Logged at WARN; the user's response is unaffected.                        |

## TTL & purge

The schema sets `expires_at = created_at + interval '24 hours'`. `IdempotencyPurgeJob` runs daily
at 04:00 UTC and deletes expired rows.

ShedLock-backed coordination is **deferred to Series 11.4** (real ECS deployment). During
single-instance dev the cron runs once per day on the running instance; this is acceptable
because the purge is idempotent (a second runner deleting nothing is a no-op).

## Threat model

A replayed cached response can outlive the underlying entity (e.g. a task was created then
deleted, but a retry hits the cache and gets the old `201` payload). This is **by design** — the
cached response is a replay of the original create, not a fresh read. Clients that want fresh
data should `GET` after the create, not retry the create.

If a request is captured in transit and replayed by an attacker, the attacker's `actor.id()`
will differ (their auth token, not the original user's), so the scoped key differs and they hit
a fresh cache miss — they don't see the original user's response.

## Test seams

- `IdempotencyFilter.shouldApply(request)` is package-private for unit tests of the route
  allowlist.
- `IdempotencyFilter.sha256Hex(byte[])` is package-private so tests can assert hash stability.
- `IdempotencyPurgeJob.purgeUsingClock(OffsetDateTime)` accepts a synthetic cutoff so the cron
  doesn't have to actually fire to test deletion semantics.
