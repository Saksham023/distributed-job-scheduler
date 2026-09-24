# Job Scheduler

A job scheduler that accepts scheduling requests over HTTP, persists them in
Postgres, and executes them at their scheduled time through a watcher → SQS →
worker pipeline. Built as a learning project, but every major decision
(libraries, patterns, tooling) is held to production standards. Scoped to a
single Postgres instance, not the 10,000 req/s scale of a large managed
scheduler.

## Current status (2026-09-24)

**v1 is complete and tested: one-time email jobs, end to end.** All nine
phases of the end-to-end test plan pass (load of 2,000 jobs, duplicate
messages, mail-server and database outages, bad data, killed processes):
see [`docs/test-plan.md`](docs/test-plan.md) → "Results".

**Split into a multi-module Maven project (done 2026-09-24):** builds, and a
fast-path and a watcher-path job ran end to end on the split services. Next
(details in [`docs/backlog.md`](docs/backlog.md)):
1. Re-run the test plan on the split services, and add recurring-job phases
   to it.

**Recurring jobs (done 2026-09-24):** API, generator and executions endpoint
built; checked end to end with all four services (an every-minute job with
`maxOccurrences` 5: API created and fast-pathed runs 1–3, the generator
created 4–5, the watcher published them, 5 emails on time, job `COMPLETED`).

## Architecture (as built)

- **Multi-module Maven project, one repo.** A parent `pom.xml` at the root
  (Spring Boot parent, BOMs, shared versions, module list) and five modules:

  | Module | What it is | Package |
  |---|---|---|
  | `common` | Plain library JAR packed into the services (never run alone): status enums, `JobExecution`, the SQS message contract and `JobExecutionPublisher` (`SqsPublisherConfig`, imported by API and watcher) | `com.jobscheduler.common` |
  | `db-migrations` | One-shot app: runs the Flyway migrations and exits (non-zero on failure). Run before the services | `com.jobscheduler.migrations` |
  | `api-service` | HTTP API (port 8080, Actuator) | `com.jobscheduler.api` |
  | `watcher-service` | Scheduled publisher, no web server | `com.jobscheduler.watcher` |
  | `worker-service` | SQS consumer + email, no web server; the only module with Spring Cloud AWS | `com.jobscheduler.worker` |
  | `generator-service` | Recurring jobs: keeps ~1 hour of executions created, completes finished jobs; no web server, no SQS | `com.jobscheduler.generator` |

  Each service has only its own code, queries (its own `JobExecutionRepository`
  etc.) and `application.properties`; no profiles or role switches. `common`
  holds only what two or more services share. No service runs Flyway.
- **API**: `POST /api/v1/jobs` validates the request and the
  job's `params` (JSON Schema of the task type's active template), saves the
  job pinned to that template version, and publishes immediately if it's due
  within the 5-minute lookahead (fast path). `GET /api/v1/jobs/{id}` returns
  the job with its next unfinished run (and, for recurring jobs, the
  schedule); `GET /api/v1/jobs/{id}/executions?limit=&before=` pages through
  its runs, newest first (keyset pagination). Errors are RFC 9457
  `ProblemDetail`.
- **Watcher**: every minute, drains `PENDING` executions due
  within the lookahead in batches of 10 (`FOR UPDATE SKIP LOCKED`), publishes
  them to SQS with a per-message delay, marks them `QUEUED`.
- **Generator**: every minute, tops up recurring schedules with fewer than 30
  minutes of executions left to one hour ahead (batches, `SKIP LOCKED`, missed
  runs skipped), and marks recurring jobs `COMPLETED` once their schedule has
  ended and no run is in flight. Details: `docs/infrastructure.md` →
  "Generator".
- **Worker**: `@SqsListener` (Spring Cloud AWS) in manual
  acknowledgement mode; claims the execution, renders the pinned template with
  Mustache, sends HTML email through `EmailSender` (Mailpit locally), marks
  `COMPLETED`, then deletes the message. Rules below.
- **Stack:** Spring Boot 4.1, Java 21, Postgres 18 (UUIDv7 keys), `JdbcClient`
  with hand-written SQL, Flyway, AWS SDK v2 + Spring Cloud AWS 4.1 (SQS),
  JMustache, networknt JSON Schema validator, Actuator.
- **Recurring jobs**: 5-field cron + IANA time zone, optional
  `startsAt`/`endsAt`/`maxOccurrences`; the API creates the first hour of
  executions and fast-paths the ones due within 5 minutes; the generator keeps
  it topped up. Design: `docs/schema.md` → "Recurring jobs".
- **Not built yet:** `GET /jobs` list, auth, real email delivery (SES).

## Worker rules (settled; see `docs/infrastructure.md` → "Worker")

- No Redis/distributed lock: coordination is the `job_executions` row plus the
  SQS visibility timeout (a lease). Delivery is at-least-once.
- Claim is an atomic allow-list update: `PENDING`/`QUEUED` → `PROCESSING`.
- Execution found `PROCESSING` → never execute it: reset to `QUEUED`
  (`WHERE status = 'PROCESSING'`) and leave the message (don't delete) so it
  reappears after the visibility timeout. Terminal status → delete the message.
- Marking `COMPLETED` is unconditional; every other update must never
  overwrite `COMPLETED`.
- Order: claim → execute → complete (DB commit) → delete the message (ack last).
- Failures: permanent → `FAILED` + delete; transient → back to `QUEUED`, keep
  the message; transient on the last attempt → `FAILED`, keep the message (it
  goes to the DLQ). `maxReceiveCount = 5`, matched by
  `app.worker.max-receive-count`.

## Running locally

1. **Infrastructure** (Docker): Postgres and Mailpit.
   ```bash
   docker compose -f docker/postgres/docker-compose.yml up -d
   docker compose -f docker/mailpit/docker-compose.yml up -d
   ```
   Postgres `localhost:5432` (db/user/password `job_scheduler`); Mailpit SMTP
   `localhost:1025`, inbox http://localhost:8025.
2. **AWS** (region `ap-south-1`, real SQS): queue `job-executions` (Standard,
   visibility timeout 60s, redrive to `job-executions-dlq` after 5 receives).
   Two IAM users with least privilege, keys in `~/.aws/credentials` (never in
   the repo):
   - profile `job-scheduler` → user `job-scheduler-api-dev`: `sqs:SendMessage`
     (API and watcher).
   - profile `job-scheduler-worker` → user `job-scheduler-worker-dev`: receive,
     delete, change visibility, get attributes/URL (worker). Also used to read
     the queue's message counts from the CLI.
   Neither can purge a queue or read the DLQ: do that in the SQS console.
3. **Build** from the repo root: `./mvnw package` (all modules; add
   `-DskipTests` to skip the API context test, which needs Postgres). The
   project targets Java 21; the terminal's default JDK may be older, so set
   `JAVA_HOME` to a 21+ JDK (`export JAVA_HOME=$(/usr/libexec/java_home -v 21+)`).
4. **Migrations first**: run `db-migrations` once (and again whenever a
   migration is added); it applies them and exits.
5. **Processes** (IntelliJ run configurations on each module's application
   class, or the JARs):

   | Process | Main class / JAR | `AWS_PROFILE` |
   |---|---|---|
   | Migrations (one-shot) | `DbMigrationsApplication` / `db-migrations/target/db-migrations-*.jar` | *(none)* |
   | API | `ApiApplication` / `api-service/target/api-service-*.jar` | `job-scheduler` |
   | Watcher | `WatcherApplication` / `watcher-service/target/watcher-service-*.jar` | `job-scheduler` |
   | Worker | `WorkerApplication` / `worker-service/target/worker-service-*.jar` | `job-scheduler-worker` |
   | Generator | `GeneratorApplication` / `generator-service/target/generator-service-*.jar` | *(none: no AWS)* |

   From a terminal: `AWS_PROFILE=<profile> java -jar <jar>`. Several watchers
   or workers: start the same JAR more than once.
6. **Reference data**: the `welcome_email` task type and its template come
   from migrations; the local test user (`userId` 1) from
   `dev/sql/01_seed_test_user.sql`.

## Docs

- [`docs/schema.md`](docs/schema.md) — schema design and the reasoning behind
  each decision (normalization, enums vs lookup tables, UUIDv7, templates and
  JSON Schema validation, status lifecycle). DDL lives in Flyway migrations
  under `db-migrations/src/main/resources/db/migration/`.
- [`docs/infrastructure.md`](docs/infrastructure.md) — SQS design, the watcher,
  the worker (statuses, updates, failures, `@SqsListener`), email, AWS setup,
  local Docker.
- [`docs/test-plan.md`](docs/test-plan.md) — end-to-end test plan and the
  results of the last run.
- [`docs/stress-test-report.md`](docs/stress-test-report.md) — 125,000-job
  stress and chaos test: metrics per service, recovery, findings.
- [`docs/backlog.md`](docs/backlog.md) — everything still to do, grouped;
  when something is postponed, add it there; when it's done, mark it.
- [`docs/notes/`](docs/notes/) — concept notes for revision (enum vs lookup
  table vs ID).
- `dev/sql/` — manual, local-only SQL (test user, load test, results check,
  reset), outside `src/` so it's never packaged or mistaken for a migration.

## How we work (collaboration)

- **The user writes the application code.** Claude explains the design,
  proposes code as snippets with the reasons behind them, creates empty files
  and folders when asked, and reviews/compiles/tests what the user wrote.
  Claude edits docs, dev scripts and Docker files directly.
- **Design before code.** Each piece is discussed and settled (with the
  trade-offs), then recorded in `docs/`, then built step by step.
- **Production-grade decisions**: major choices (libraries, architecture,
  tooling) must be what a production team would use. Small polish can be
  deferred to the backlog.
- **Short, one-step-at-a-time answers**: the user prefers brief explanations
  and one change at a time over long multi-part messages.
- **Testing**: Claude may run tests from the terminal when asked, explaining
  first the aim, the actions (e.g. direct inserts vs `POST` calls) and the
  expected timeline; stop all started processes afterwards.
- **Git**: commit only when asked; never push (the user pushes). No
  `Co-Authored-By` or other Claude attribution in commit messages or PRs.
