# Calendar backend build progress

Each part below records its hand-off report. Newest at the top.

---

## Hand-off report template

```
Part X.Y — [Title] — STATUS: [✅ done | ⚠️ partial | ❌ blocked]
Date: YYYY-MM-DD
Operator: [name]

What got built:
- [bullet]

Files changed (count: N):
- path/to/file.ext — [one-line summary]

Validation:
- [ ] [validation step 1]   (paste output)
- [ ] [validation step 2]   (paste output)

Notes / surprises:
- [anything unexpected, deferred, or worth flagging]

Next part: X.Y+1
```

---

## Part 0.5 (Series 0) — Code quality tooling: Spotless + JaCoCo + Failsafe — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `pom.xml` adds three plugin blocks:
  - `spotless-maven-plugin 2.43.0` (Google Java format, `check` goal bound to default lifecycle phase)
  - `jacoco-maven-plugin 0.8.12` (`prepare-agent` + `report` + `check` with bundle-level 80% line coverage gate, `haltOnFailure: true`)
  - `maven-failsafe-plugin` gains explicit `<configuration><includes><include>**/*IT.java</include></includes></configuration>`
- `.editorconfig` at repo root (Google conventions: LF, UTF-8, 2-space, trim trailing whitespace, 100-char max line for code)
- `PlatformDbHealthIndicatorTest.java` — Surefire unit test mocking `JdbcTemplate`; covers both UP and DOWN branches of `health()`. Necessary to keep bundle coverage ≥ 80% on the small initial codebase.

Files changed (count: 5, +2 new):
- `pom.xml` (modified)
- `.editorconfig` (new)
- `src/test/java/com/childcarewow/calendar/health/PlatformDbHealthIndicatorTest.java` (new)
- `src/test/java/com/childcarewow/calendar/DatasourceConfigIT.java` (reformatted by spotless:apply)
- `src/test/java/com/childcarewow/calendar/FlywayMigrationIT.java` (reformatted by spotless:apply)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS in 18s
- [x] Spotless: 7 files clean
- [x] Surefire: `CalendarApplicationTests` (1) + `PlatformDbHealthIndicatorTest` (2) green
- [x] Failsafe: `DatasourceConfigIT` (2) + `FlywayMigrationIT` (2) green
- [x] JaCoCo: "All coverage checks have been met" (≥80% bundle line coverage on 3 main classes)
- [x] Coverage report at `target/site/jacoco/index.html`
- [x] Deliberate trailing-whitespace edit → `mvn spotless:check` fails (verified locally; `git checkout` reverted; not committed)
- [x] `find . -name "*IT.java"` returns the two failsafe ITs from Parts 0.3 and 0.4

Notes / surprises:
- Spotless plugin asked for Google Java format `1.22.0` in pom config but resolved `1.19.2` (the plugin's default). No functional difference for our small codebase; left as-is. If future formatting drift, force `<googleJavaFormat><version>1.22.0</version>` precedence by upgrading spotless-maven-plugin past `2.43.0`.
- First `mvn verify` after writing `PlatformDbHealthIndicatorTest.java` failed: Spotless flagged CRLF line endings (Windows default) on the new file. Re-running `mvn spotless:apply` normalized to LF. Future file writes on Windows go through the same path; `spotless:apply` is the canonical fix.
- JaCoCo bundle has 3 classes: `CalendarApplication`, `DatasourceConfig`, `PlatformDbHealthIndicator`. All three above the 80% line threshold.
- The `mvn spotless:check` deliberate-failure validation is an ephemeral local test, not a committed artifact.

Next part: **Part 0.6 (Series 0) — GitHub Actions CI workflow**. **Operator action required first:** run `gh auth refresh -h github.com -s workflow` once. The current gh token lacks `workflow` scope; pushing `.github/workflows/ci.yml` will be rejected without it (this is why pre-flight P0.5 also skipped a workflow placeholder). When P0.6 lands, also uncomment the Testcontainers deps in pom.xml — Linux CI runs Docker cleanly where Testcontainers works (memory `backend_part_0_4_testcontainers_windows.md`).

---

## Part 0.4 (Series 0) — Flyway against the calendar datasource only — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `pom.xml` adds `flyway-core 10.20.1`, `flyway-database-postgresql 10.20.1` (required for Flyway 10+ on Postgres 15), and `flyway-maven-plugin 10.20.1` with URL pinned to `localhost:5434`.
- `application.yml` `spring.flyway` block: `enabled: true`, URL/user/password resolved from `${spring.datasource.calendar.*}`, `locations: classpath:db/migration`, `baseline-on-migrate: false`.
- `V1__placeholder.sql` — creates `_flyway_smoke` (id pk, created_at default now), inserts id=1. Will be superseded by V2 (events) in Part 1.1.
- `FlywayMigrationIT.java` — asserts `flyway_schema_history` has V1 with success=true, asserts `_flyway_smoke` has 1 row. Runs against the live calendar-db via `@Autowired JdbcTemplate` (Testcontainers deferred — see deviations).

Files changed (count: 5, +3 new −1 deleted):
- `pom.xml` (modified) — Flyway deps + maven plugin
- `src/main/resources/application.yml` (modified) — spring.flyway block
- `src/main/resources/db/migration/V1__placeholder.sql` (new)
- `src/main/resources/db/migration/.gitkeep` (deleted — superseded by V1)
- `src/test/java/com/childcarewow/calendar/FlywayMigrationIT.java` (new)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS in 21s
- [x] Surefire: 1 (`CalendarApplicationTests.contextLoads`); Failsafe: 4 (`DatasourceConfigIT` 2 + `FlywayMigrationIT` 2)
- [x] `mvn flyway:info` → `Schema version: 1`, V1 placeholder Success
- [x] `psql calendar -c 'SELECT * FROM flyway_schema_history'` → V1 success=t
- [x] `psql calendar -c 'SELECT * FROM _flyway_smoke'` → 1 row id=1
- [x] `psql platform -c 'SELECT * FROM _flyway_smoke'` → `ERROR: relation "_flyway_smoke" does not exist` (Flyway never touched platform — by design)
- [x] `psql platform -c '\dt flyway_schema_history'` → no relation

Notes / surprises (deviations):
- **Testcontainers blocked on Windows + Docker Desktop 4.71.** Playbook step 6 spec'd a Testcontainers-driven IT for fresh-container isolation. The Testcontainers `DockerClientProviderStrategy` returns HTTP 400 from every named pipe attempted (`docker_engine`, `dockerDesktopLinuxEngine`, `docker_cli`); Docker Desktop's API responds with a degraded JSON instead of accepting the connection. Rewrote `FlywayMigrationIT` to use the live calendar-db via `@Autowired JdbcTemplate`. Testcontainers deps in `pom.xml` are kept **commented out** with a TODO to re-enable in P0.6 — CI's GitHub Actions Linux runner has a clean Unix Docker socket where Testcontainers works without the Windows pipe weirdness. Saved as project memory `backend_part_0_4_testcontainers_windows.md`.
- **Flyway plugin URL is 5434**, not 5432 (Part 0.2 host-port deviation carried forward).
- Spring Boot's auto-config runs Flyway against the @Primary calendar DataSource on context startup. Idempotent: subsequent `mvn verify` runs see V1 already applied, no-op. The `_flyway_smoke` table persists in dev calendar-db until volume is wiped (`docker compose down -v`).
- The `application.yml` `spring.flyway.url` placeholder `${spring.datasource.calendar.url}` resolves correctly because Spring Boot resolves placeholders before binding `FlywayProperties`.

Next part: **Part 0.5 (Series 0) — Code quality tooling: Spotless + JaCoCo + Failsafe.** (Naming reminder: this is Series-0 Part 0.5, not the pre-flight P0.5 we did earlier; same number, different section of the playbook.)

---

## Part 0.3 (Series 0) — Dual HikariCP datasources — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `pom.xml` adds `spring-boot-starter-jdbc`, `org.postgresql:postgresql:42.7.4`, and `maven-failsafe-plugin` (binds to `integration-test` + `verify` so `*IT` tests run during `mvn verify`).
- `application.yml` rewritten with two datasource sections: `spring.datasource.calendar` (URL → 5434, pool=20 RW, pool-name `calendar-pool`) and `spring.datasource.platform` (URL → 5433, pool=5 RO, pool-name `platform-pool`).
- `DatasourceConfig.java` (`config/`) — `@Primary` calendar `HikariDataSource` + `JdbcTemplate`; platform `HikariDataSource` + `JdbcTemplate` qualifier-named `platformJdbcTemplate`.
- `PlatformDbHealthIndicator.java` (`health/`) — `@Component("platformDb")` runs `SELECT 1` against platform.
- `DatasourceConfigIT.java` — 2 tests (calendar `SELECT 1` returns 1, platform `SELECT count(*) FROM users` returns 7) running in failsafe phase against compose-managed Postgres.

Files changed (count: 5):
- `pom.xml` (modified) — 2 new deps + failsafe plugin
- `src/main/resources/application.yml` (modified) — full rewrite
- `src/main/java/com/childcarewow/calendar/config/DatasourceConfig.java` (new)
- `src/main/java/com/childcarewow/calendar/health/PlatformDbHealthIndicator.java` (new)
- `src/test/java/com/childcarewow/calendar/DatasourceConfigIT.java` (new)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS in 21s
- [x] Surefire: 1 test (`CalendarApplicationTests.contextLoads`) green
- [x] Failsafe: 2 tests (`DatasourceConfigIT`) green
- [x] App boot logs show `platform-pool - Start completed.` and `calendar-pool - Start completed.`
- [x] `/actuator/health` shows: `db.calendarDataSource` UP, `db.platformDataSource` UP, custom `platformDb` UP, overall `status: UP`
- [x] `docker stop ccw-cal-platform-db` → `/actuator/health` overall `DOWN`, `platformDataSource` DOWN with `Failed to obtain JDBC Connection`, `calendarDataSource` still UP
- [x] `docker start ccw-cal-platform-db` (waited for healthy) → `/actuator/health` UP again on both

Notes / surprises (deviations):
- **Playbook YAML key was wrong:** the spec used `jdbc-url:` under `spring.datasource.calendar`, but `DataSourceProperties.determineUrl()` reads `url`. Build failed with `Failed to determine suitable jdbc url` and ApplicationContext failed to load until `jdbc-url:` was changed to `url:`. **Fix is committed verbatim**; the playbook should be updated to match.
- The auto-registered Spring `db` health composite now includes BOTH datasources (`db.calendarDataSource` + `db.platformDataSource`) — Spring Boot 3.x auto-detects all `DataSource` beans, not just `@Primary`. The custom `PlatformDbHealthIndicator` (`platformDb` key) is somewhat redundant with `db.platformDataSource` but matches the playbook spec; left in place for explicit naming.
- Carry-over from Part 0.2: calendar URL is `localhost:5434`, not 5432 (host PG 18 conflict).
- `CalendarApplicationTests.contextLoads()` now requires the compose to be up (Hikari eagerly init's connections at context start). Add `@TestConfiguration` with H2/Testcontainers later if we want self-contained unit tests; not required for Series 0.

Next part: **Part 0.4 (Series 0) — Flyway against the calendar datasource only** (D8 schema bootstrap; will populate the empty calendar DB).

---

## Part 0.2 (Series 0) — Docker Compose with two Postgres databases — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `calendar-db` (Postgres 15-alpine, container port 5432, host port **5434** — see deviation), empty schema; Flyway will populate in Part 0.4.
- `platform-db` (Postgres 15-alpine, container port 5432, host port 5433), seeded on first init via `docker/platform-seed.sql` from `/docker-entrypoint-initdb.d/`.
- Canonical seed (1 org, 2 schools, 4 classrooms, 7 users, 4 students, 2 student_parents) with fixed UUIDs matching `Events_CCW/src/data/seed.ts`. Backend integration tests (Series 4+) reference these IDs.
- Named Docker volumes (`calendar-db-data`, `platform-db-data`) so data persists across `docker compose down`/`up`.

Files changed (count: 3, +1 new):
- `docker-compose.yml` (modified) — adds `calendar-db` and `platform-db` services to the existing LocalStack stanza
- `docker/platform-seed.sql` (new) — schema + seed
- `.gitignore` (modified) — adds `postgres-data/` (we use named volumes, but per playbook for completeness)

Validation (via `docker exec ... psql ...`, since host `psql` install is still deferred):
- [x] Both DBs healthy in ~10s after `docker compose up -d calendar-db platform-db`
- [x] `\dt` on calendar DB → "Did not find any relations" (empty as expected)
- [x] `SELECT count(*) FROM users` on platform DB → **7**
- [x] `SELECT count(*) FROM students` on platform DB → **4**
- [x] All 6 tables row-count match seed: 1 org, 2 schools, 4 classrooms, 7 users, 4 students, 2 student_parents
- [x] `docker compose stop && rm && up -d` preserves data (counts unchanged after restart — volumes persistent, init script doesn't re-run)

Notes / surprises (deviations):
- **Host port for `calendar-db` is 5434 instead of 5432.** The operator has a Windows service `postgresql-x64-18` (PostgreSQL 18) running on 5432; pg_ctl PID 5540, parent of postgres PID 7760. Stopping the host service was avoided to keep the user's other dev tooling working. Container internal port stays at 5432; only the host binding shifts. **Part 0.3 `application.yml` must use `jdbc:postgresql://localhost:5434/calendar`** instead of 5432. Recorded as the canonical deviation.
- `platform-db` host port is 5433 as in playbook (free).
- Validations done via `docker exec` rather than host `psql`. Per memory `backend_p00_deferred_installs.md`, psql install is still deferred until needed; `docker exec` is the documented workaround. Install whenever convenient via `winget install PostgreSQL.PostgreSQL.16` (UAC).
- `postgres-data/` in `.gitignore` is a no-op for now (we use named volumes, not bind mounts), but committed per playbook.

Next part: **Part 0.3 (Series 0) — Dual HikariCP datasources** — adds `org.springframework.boot:spring-boot-starter-jdbc` + Postgres driver to `pom.xml`, configures two `HikariDataSource` beans (calendar pool=20, platform pool=5 read-only), expands `application.yml` to wire both, adds `PlatformDbHealthIndicator`, and writes an integration test.

---

## Part 0.1 (Series 0) — Maven + Spring Boot scaffold — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- `pom.xml` at repo root: `groupId=com.childcarewow`, `artifactId=calendar-backend`, version `0.1.0-SNAPSHOT`, parent `spring-boot-starter-parent` 3.3.5, Java 21, `maven-compiler-plugin` with `-Werror -Xlint:all,-processing`
- 3 dependencies: `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-test` (test scope)
- `CalendarApplication.java`: minimal `@SpringBootApplication`
- `application.yml`: server port 8080, expose `health` + `info` actuators
- `CalendarApplicationTests.java`: `@SpringBootTest contextLoads()` smoke

Files changed (count: 6, net +4):
- `pom.xml` (new)
- `src/main/java/com/childcarewow/calendar/CalendarApplication.java` (new)
- `src/main/resources/application.yml` (new)
- `src/test/java/com/childcarewow/calendar/CalendarApplicationTests.java` (new)
- `src/main/java/com/childcarewow/calendar/.gitkeep` (deleted — superseded by Application.java)
- `src/test/java/com/childcarewow/calendar/.gitkeep` (deleted — superseded by Tests.java)

Validation:
- [x] `mvn -B clean verify` → BUILD SUCCESS
- [x] `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- [x] `mvn spring-boot:run` log: `Started CalendarApplication in 1.668 seconds`
- [x] `curl localhost:8080/actuator/health` → `{"status":"UP"}`
- [x] `curl localhost:8080/foo` → HTTP 404 (Spring handling, not connection-refused)
- [x] Zero compiler warnings under `-Werror` (only JVM runtime warnings from byte-buddy agent loader, surfaced during test execution; not blocking)

Notes / surprises:
- Spring Boot pinned to **3.3.5** per playbook. Later 3.3.x patches likely exist (today is 2026-05-06); bump opportunistically if a security advisory drops.
- First `mvn verify` downloaded ~80 MB of dependencies from Maven Central. Subsequent runs hit the local `~/.m2` cache.
- `db/migration/.gitkeep` retained — Flyway migrations land in Series 0 / Part 0.4 (D8 schema bootstrap).
- Branch convention switched from `bootstrap/*` (pre-flight) to `series0/*` for this Part. Same review/merge flow, just different namespace.

Next part: **Part 0.2 (Series 0) — Docker Compose with two Postgres databases** (`calendar-db` on 5432 + `platform-db` on 5433)

---

## P0.5 — Repository skeleton + progress.md — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- Directory tree per playbook minus `.github/workflows/` (`docs/decisions/`, `docs/perf/`, `infrastructure/ecs/`, `src/main/java/com/childcarewow/calendar/`, `src/main/resources/db/migration/`, `src/test/java/com/childcarewow/calendar/`)
- `docs/runbook.md` placeholder (sections owned by future Parts noted in TODO comments)
- `progress.md` (this file): hand-off-report template + backfilled entries for P0.0 through P0.4

Files changed (count: 8):
- `progress.md` (new) — hand-off log
- `docs/runbook.md` (new) — operations procedures placeholder
- `docs/decisions/.gitkeep` (new)
- `docs/perf/.gitkeep` (new)
- `infrastructure/ecs/.gitkeep` (new)
- `src/main/java/com/childcarewow/calendar/.gitkeep` (new)
- `src/main/resources/db/migration/.gitkeep` (new)
- `src/test/java/com/childcarewow/calendar/.gitkeep` (new)

**Skipped:** `.github/workflows/.gitkeep` — GitHub's API rejects pushes that touch `.github/workflows/*` unless the auth token has `workflow` scope. Our `gh auth` token has `repo` + `admin:org` + `read:org` only. Rather than refresh scope just for a placeholder file (an interactive browser flow), the dir is left uncreated; **P0.6 (CI workflow) will be the first PR that creates `.github/workflows/`**, and the operator runs `gh auth refresh -s workflow` once before that PR can push.

Validation:
- [x] Directory tree exists, checked into `main` (verified via `git ls-tree --name-only -r main`)
- [x] `progress.md` exists with template
- [x] `README.md` exists (from P0.4)
- [~] *Playbook validation #4 ("test PR cannot be merged without approval") DOES NOT APPLY* — branch protection was loosened to 0 approvals during P0.4 since the operator is solo dev and can't approve own PRs. Direct push to main, force-push, and deletion are still blocked; `enforce_admins=true`.

Notes / surprises:
- Branch protection deviation: `required_approving_review_count=0`. Tighten to 1+ when team has multiple reviewers. The deviation is explicit + documented; PR flow itself is enforced (PRs #1–#4 demonstrate it).
- All backfilled entries for prior Parts are concise summaries; full conversational context lives in CLAUDE.md §0 of the parent `Events_CCW` repo plus the session log of 2026-05-06.

Next part: Series 0 / **Part 0.1 — Maven + Spring Boot scaffold**. (Naming collision: pre-flight "P0.1" was the AWS step; Series-0 "Part 0.1" is the Maven scaffold. See `implementation_plan.md` "Pre-flight execution order" callout.)

---

## P0.3 — Firebase project (push notifications, D4) — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- GCP project `ccw-cal-dev-a911f` with Firebase added (free Spark plan)
- Firebase Admin SDK service account `firebase-adminsdk-fbsvc@ccw-cal-dev-a911f.iam.gserviceaccount.com`
- Service-account JSON key (created via `gcloud iam service-accounts keys create`) uploaded to LocalStack secret `childcarewow-calendar/dev/firebase-service-account`
- FCM API + Firebase Management API enabled

Files changed (count: 0 in this repo; 1 in parent Events_CCW repo, uncommitted):
- `Events_CCW/implementation_plan.md` — P0.3 free-tier execution variant callout

Validation:
- [x] `client_email` ends in `iam.gserviceaccount.com`
- [x] FCM API enabled (also: firebase, firebasehosting, firebaseinstallations, firebaseremoteconfig)
- [x] Firebase project metadata reachable via Management API
- [ ] Test push to a device token — DEFERRED to Series 11.6 per playbook step 5

Notes / surprises:
- Free-tier variant Option A: only `ccw-cal-dev` Firebase created. `ccw-cal-stg` deferred (Series 7+), `ccw-cal-prod` deferred (Series 11+). Free-tier callout added to `implementation_plan.md`.
- Firebase ToS click-through (one-time, not scriptable) was required. Operator accepted via the Firebase console "Add project" wizard.
- Console wizard auto-created a *new* GCP project (`ccw-cal-dev-a911f`, random suffix) instead of attaching to the empty `ccw-cal-dev` shell created earlier via `gcloud projects create`. The empty shell was deleted (`gcloud projects delete ccw-cal-dev`; recoverable for 30 days).
- Project ID has a random suffix because GCP project IDs are global; `ccw-cal-dev` was already used (by our deleted shell).
- Service-account JSON private key never appeared in conversation history (uploaded via `file://` reference; only `client_email`/`project_id`/`type` echoed for validation).
- Google Cloud SDK was installed during this Part.

Next part: P0.5

---

## P0.2 — Supabase project (Auth + Storage, D1/D4) — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- Supabase project `ccw-cal-dev` (project ref `xedxxrfuwbyqtxrqearx`), hosted free tier, region `us-east-1`
- Storage bucket `event-attachments` (public, 10 MB cap, MIME allowlist `image/jpeg`+`image/png`)
- ES256 JWKS published at `/auth/v1/.well-known/jwks.json`
- `site_url` + `uri_allow_list` set to `http://localhost:5173` (frontend Vite dev server)
- Service-role key + JWT config saved to LocalStack secrets `childcarewow-calendar/dev/supabase-service-role` and `…/supabase-jwt-public-key`

Files changed (count: 1 in parent Events_CCW repo, uncommitted):
- `Events_CCW/implementation_plan.md` — P0.2 free-tier execution variant callout

Validation:
- [x] `/auth/v1/health` endpoint reachable
- [x] JWKS reachable, ES256 P-256 key
- [x] Both Supabase secrets present in LocalStack with version IDs
- [x] `event-attachments` bucket exists with correct config (10485760 byte limit, jpeg+png allowlist)

Notes / surprises:
- Free-tier variant Option A: only `ccw-cal-dev` created. Free tier caps at 2 projects/org; reserves slot 2 for staging when needed.
- Project uses ES256 (asymmetric JWKS) — backend will validate JWTs via the JWKS URL, not via a shared HS256 secret.
- Supabase API returned both legacy JWT keys (long HS256 JWTs, type=legacy) AND new `sb_publishable_*`/`sb_secret_*` format keys. The `sb_secret_` key is masked in API responses after creation. Backend uses the legacy `service_role` JWT for now.
- Supabase Personal Access Token (PAT) was used for project creation. **Operator should revoke the PAT** at https://supabase.com/dashboard/account/tokens since it appeared in conversation history.
- DB password generated random 32-char and not persisted (we don't use Supabase Postgres — calendar DB is on AWS RDS per D6).

Next part: P0.3

---

## P0.1 — AWS account + IAM bootstrap — STATUS: ✅ done (LocalStack edition)
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- LocalStack 3.8 (community) container via `docker compose up -d localstack`, persistence volume
- IAM user `calendar-operator` + access keys
- IAM policy `CalendarBackendOperator` (committed as `infrastructure/iam/operator-policy.json`) attached to the user
- 15 Secrets Manager placeholders at `childcarewow-calendar/{dev,staging,prod}/{db-credentials,supabase-jwt-public-key,supabase-service-role,firebase-service-account,smtp}`
- Idempotent `infrastructure/localstack/bootstrap.sh` that supports both LocalStack mode (default, via `AWS_ENDPOINT_URL`) and real-AWS mode (when `AWS_ENDPOINT_URL` is unset)

Files changed (count: 4 across 3 PRs):
- `docker-compose.yml` (new, PR #1; image pin to `:3.8` in PR #2) — LocalStack service
- `infrastructure/iam/operator-policy.json` (new, PR #1) — `CalendarBackendOperator` policy spec (least-privilege per playbook step 3, with tag-scoped RDS, role-prefix-scoped IAM, subdomain-scoped Route53)
- `infrastructure/localstack/bootstrap.sh` (new, PR #1; `cygpath` fix in PR #3) — provisioner
- `README.md` (modified, PR #1) — LocalStack run-locally section

Validation:
- [x] `aws sts get-caller-identity --profile childcarewow-calendar` returns mock identity
- [x] `iam list-attached-user-policies --user-name calendar-operator` shows exactly `CalendarBackendOperator`
- [x] 15 secrets present matching `childcarewow-calendar/*` prefix
- [ ] OIDC role `ChildcareWowCalendarGitHubActions` — DEFERRED to Series 11 (real AWS only; GitHub Actions runners can't reach localhost:4566)

Notes / surprises:
- All AWS replaced by LocalStack 3.8 community. Real AWS deferred to Series 11. ECS, ECR, RDS, ELB are LocalStack Pro-only and unavailable in our setup; that's fine for P0.1 scope.
- `localstack/localstack:latest` now resolves to a Pro-licensed dev image (`2026.5.0.dev67`) that exits without `LOCALSTACK_AUTH_TOKEN`. Pinned to `:3.8` (last known-good community 3.x).
- Step 5 (GitHub OIDC provider + role) and step 7 (`AWS_DEPLOY_ROLE_ARN` repo variable) deferred to Series 11.
- Windows Git Bash + native AWS CLI: `--policy-document file:///c/...` paths fail; bootstrap.sh uses `cygpath` to convert to Windows-style.
- AWS CLI v2.34.43 installed during this Part (deferred from P0.0 per memory `backend_p00_deferred_installs.md`).

Next part: P0.2

---

## P0.4 — GitHub repository — STATUS: ✅ done (with deviations)
Date: 2026-05-06
Operator: Mukul Phogat

What got built:
- Public GitHub repo at https://github.com/mukul29phogat-art/calendar-backend
- Initial commit: `README.md` + `.gitignore` (Java + Maven + Spring Boot) on `main`
- Branch protection rule on `main`: PR required, dismiss stale reviews, no force-push, no deletion, `enforce_admins=true`. Approvals first set to 1, later loosened to 0 (deviation; see notes).
- Actions workflow permissions: `default_workflow_permissions=write`, `can_approve_pull_request_reviews=false`

Files changed (count: 2 in initial commit; 4 total across PRs #1–#3 once P0.1 lands):
- `README.md` (new) — repo overview + namespace-transfer note
- `.gitignore` (new) — Java + Maven + Spring Boot template

Validation:
- [x] `gh repo view` returns `name=calendar-backend`, `visibility=PUBLIC`
- [x] `git push origin main` works without auth prompts (gh-managed credential)
- [x] `gh api repos/.../branches/main/protection` returns the protection rules
- [ ] `gh variable list` shows `AWS_DEPLOY_ROLE_ARN` — DEFERRED to Series 11 (P0.1 step 7)

Notes / surprises:
- **Namespace deviation:** operator does not have access to the `ChildcareWow` GitHub org. Repo lives in personal namespace `mukul29phogat-art/calendar-backend`. **Cleanup TODO at org access:** `gh repo transfer mukul29phogat-art/calendar-backend childcarewow`, then update IAM OIDC trust-policy `sub` claim to `repo:childcarewow/calendar-backend:*`.
- **Visibility deviation:** `PUBLIC` instead of `PRIVATE`. GitHub gates branch protection (classic + rulesets) behind GitHub Pro for private personal repos. Public was the only $0 path to enable branch protection.
- **Approvals deviation:** loosened from 1 → 0 because the solo operator can't approve own PRs (GitHub disallows self-review). Tighten when there are multiple reviewers.

Next part: P0.1

---

## P0.0 — Local toolchain — STATUS: ✅ done
Date: 2026-05-06
Operator: Mukul Phogat

What got built (on operator workstation, not in this repo):
- Eclipse Temurin 21 JDK (LTS) at `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`
- WSL 2.6.3 (Windows feature enabled, post-reboot)
- Docker Desktop 4.71 (`docker` 29.4.1, `docker compose` v5.1.3)
- jq 1.8.1
- Already present: Maven, Git, Node, npm, curl, gh
- Installed during P0.1: AWS CLI v2.34.43
- Installed during P0.3: Google Cloud SDK
- *Still deferred:* psql client (deferred to Series 0 / Part 0.2 per memory `backend_p00_deferred_installs.md`)

Files changed (count: 0 in this repo)

Validation:
- [x] `java -version` — Temurin 21
- [x] `docker --version` — 29.4.1
- [x] `docker compose version` — v5.1.3
- [x] `aws --version` — 2.34.43 (post-P0.1)
- [x] `gcloud --version` — installed (post-P0.3)

Notes / surprises:
- WSL2 install required a reboot — one session boundary (CLAUDE.md §0 was used to hand off state).
- Docker installer initially failed because a leftover `C:\ProgramData\DockerDesktop` directory was owned by a non-admin user account. Fixed via elevated `takeown /F ... /R /D Y; Remove-Item -Recurse -Force` then reinstall.
- Docker Desktop install required UAC accept; same for AWS CLI and Google Cloud SDK installs in later Parts.
- AWS CLI + Google Cloud SDK don't appear on this Bash session's PATH because they were installed mid-session; new shells pick them up via the system PATH update. Workaround: prepend `C:\Program Files\Amazon\AWSCLIV2`, `C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin`, and `C:\Program Files\Docker\Docker\resources\bin` to PATH.

Next part: P0.4
