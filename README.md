# calendar-backend

Backend service for the **ChildcareWow School Operations Calendar**.

## Status

P0.4 + P0.1 complete. AWS surface is **emulated via LocalStack** during dev — real AWS is deferred to Series 11. Spring Boot scaffold lands in **Part 0.1** (Series 0).

## Namespace note

> This repo is currently hosted at `mukul29phogat-art/calendar-backend`. **It will be transferred to `childcarewow/calendar-backend`** once company access to the org is granted. When that happens:
>
> 1. `gh repo transfer mukul29phogat-art/calendar-backend childcarewow`
> 2. Update the IAM OIDC trust-policy `sub` claim from `repo:mukul29phogat-art/calendar-backend:*` to `repo:childcarewow/calendar-backend:*` (see Part 0.1 step 5 of `implementation_plan.md`).
> 3. Update any references in this README, GitHub Actions workflows, and the parent repo's docs.

## Where the source-of-truth docs live

The architecture spec, locked decisions, and execution playbook all live in the parent frontend repo (`Events_CCW`):

| Doc | Purpose |
|---|---|
| `Backend-Implementation-Plan.md` | Architecture spec (v5.0) |
| `Decision-Record.md` | D1–D13 + Q1/Q2 (locked 2026-04-30) |
| `Build-Sequence.md` | 95-phase atomic build sequence with rubric |
| `implementation_plan.md` | The execution playbook (115 parts: P0 + Series 0–12) |
| `CLAUDE.md` | Repo-wide guidance for Claude Code sessions; live state in §0 |

## Run locally

Toolchain setup: see **Part 0.0** of `implementation_plan.md` in the parent repo.

### LocalStack (emulated AWS)

```bash
# 1. Start LocalStack
docker compose up -d localstack

# 2. Verify it's healthy
curl http://localhost:4566/_localstack/health

# 3. Bootstrap IAM user, policy, and Secrets Manager placeholders
./infrastructure/localstack/bootstrap.sh
```

The bootstrap script is **idempotent** — re-run it any time. AWS CLI profile `childcarewow-calendar` must exist locally (one-time setup; see CLAUDE.md §0 in the parent repo).

**State persistence:** the LocalStack container uses a named Docker volume (`localstack-data`). State survives container restarts. To wipe and start fresh:

```bash
docker compose down -v
docker compose up -d localstack
./infrastructure/localstack/bootstrap.sh
```

### Postgres + Spring Boot

Land in Part 0.1 + Part 1.0.1 of the playbook. This README will be updated then.

## Progress log

`progress.md` (created in P0.5) records every Part's hand-off report. Don't reconstruct status from `git log` — use `progress.md`.
