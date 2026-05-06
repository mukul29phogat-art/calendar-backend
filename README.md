# calendar-backend

Backend service for the **ChildcareWow School Operations Calendar**.

## Status

Bootstrap repo (P0.4 of the backend build). Empty until **P0.1** populates the Maven + Spring Boot scaffold.

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

See **Part 0.0** (toolchain setup) and **Part 1.0.1** (Postgres + Docker Compose) of `implementation_plan.md` in the parent repo.

## Progress log

`progress.md` (created in P0.5) records every Part's hand-off report. Don't reconstruct status from `git log` — use `progress.md`.
