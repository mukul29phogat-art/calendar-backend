# Audit-event immutability

The `audit_events` table is the system's tamper-evident log. COPPA review and the architecture
spec (§7.10) require that once a row is written, **no code path mutates or deletes it**.

## Layers of enforcement

| Layer | Mechanism | What it stops |
|---|---|---|
| 1. Spring service | Only `AuditService.log(...)` writes to the repository, and it always calls `save()` on a transient entity (no `@Id` set, so Hibernate issues an `INSERT`). | Accidental `save()` of a modified existing row from new code paths. |
| 2. JPA / Hibernate | `@org.hibernate.annotations.Immutable` on `AuditEvent`. Modifications to a managed entity are silently ignored at flush time — Hibernate does not emit an `UPDATE`. | Programmatic mutation through JPA, even by mistake. |
| 3. CI guard | A `grep` step in `.github/workflows/ci.yml` fails the build if any file under `src/main` references `auditEventRepository.save` or `auditEventRepository.delete`. | A new committer adopting the typed repo name and writing an update/delete. |
| 4. Pen-test | Manual review in Series 12 (Part 12.3). | Native-SQL bypasses, raw JDBC, or admin tooling. |

## What is *not* protected

`@Immutable` is a Hibernate-only construct. It does **not**:

- Block `DELETE` (Hibernate documents `@Immutable` as covering updates only). No code currently
  deletes audit rows; the CI grep prevents new code from doing so.
- Block native SQL — `entityManager.createNativeQuery("UPDATE audit_events ...")` would succeed
  if anyone wrote it.
- Affect the Postgres schema. The columns are not `IMMUTABLE` from the database's perspective.

If we ever need true tamper-evident storage at the DB layer, the path is one of: a `BEFORE
UPDATE/DELETE` trigger that raises an exception, a `REVOKE UPDATE, DELETE` against the calendar
service role, or write-once storage (S3 Object Lock) for the eventual archival sink.

## Adding new audit-writing code

Always go through `AuditService.log(...)`. Never inject `AuditEventRepository` into service code,
and never name a variable `auditEventRepository` in `src/main` — the CI grep flags both `.save`
and `.delete` on that exact identifier as a build failure.

For controller-level events, prefer the `@Audited` annotation (Part 3.3) so the cross-cutting
concern is invisible to the controller body.
