# Backlog

Everything still to do, in one place. Add an item whenever something is
postponed; mark it `[x]` when done. Design reasoning lives in `schema.md` /
`infrastructure.md`; this file is the to-do list.

**Where things stand (2026-09-24):** v1 (one-time email jobs) is built and
passes the full end-to-end test plan. Both major items are done: the
multi-module split and recurring jobs (built and checked end to end). Next:
re-run the test plan on the split services, with recurring-job phases added.

## v1: one-time jobs (done)

- [x] API: `POST /api/v1/jobs` (201 + `Location`), `GET /api/v1/jobs/{id}`,
      `ProblemDetail` errors; `params` validated against the active template's
      JSON Schema; template version pinned on the job (`jobs.template_id`).
- [x] Watcher: batches of 10, `FOR UPDATE SKIP LOCKED`,
      drains until empty, shared lookahead with the `POST` fast path.
- [x] Worker: `@SqsListener` in manual acknowledgement
      mode; atomic claim, `PROCESSING` reset, permanent/transient/last-attempt
      failures, ack last; strict Mustache rendering; HTML email via
      `EmailSender` (Mailpit locally); mail timeouts (5s/10s/10s).
- [x] Schema: `FAILED` job status (V5); `params_schema` + `template_id` (V6).
- [x] Worker IAM identity (receive/delete/change-visibility/get attributes);
      redrive `maxReceiveCount = 5`.
- [x] End-to-end test plan run (`docs/test-plan.md` → "Results").

## Remaining v1 polish

- [ ] Integration tests with Testcontainers (real Postgres, runs Flyway), to
      turn the manual test plan into repeatable automated tests.
- [ ] `TaskService` enum once code reads `task_types.service` (e.g. when a
      second channel like SMS arrives).

## Next major item 1: recurring jobs

Schema already exists (`recurring_schedules`: `cron_expression`, `starts_at`,
`ends_at`, `max_occurrences`, `generated_until`); design in `schema.md`
("Status lifecycle" covers who completes a recurring job). Start by settling
the open decisions, then build.

Decisions (details in `schema.md` → "Recurring jobs"):
- [x] Cron library: Spring's `CronExpression`; clients send standard 5-field
      cron, prefixed with `0 ` for seconds.
- [x] Minimum frequency: once a minute (follows from the 5-field syntax).
- [x] Window: one hour of executions always materialized; the API creates
      the first window in the job's transaction, the generator (every
      minute) tops it up.
- [x] Missed runs (generator down): skipped, not caught up.
- [x] Template pinning: recurring jobs keep the version they were created
      with (a "move to latest template" feature can come later).
- [x] Migration V7: `recurring_schedules.timezone`, `occurrences_generated`,
      checks on `max_occurrences` and `ends_at`.

Build:
- [x] API: optional `cronExpression` (+ `startsAt`, `endsAt`,
      `maxOccurrences`, `timezone`) on `CreateJobRequest`; its presence means
      `RECURRING`. Backward compatible for existing clients. Cross-field rules
      in `ScheduleParser` → sealed `Schedule` (`OneTime` / `Recurring`).
- [x] `JobService.createJob`: `switch` on the `Schedule`; recurring creates the
      first hour of executions (one `unnest` insert) in the job's transaction;
      one batched fast path (`publishDueSoon`) for both kinds. Checked end to
      end: an every-minute job with `maxOccurrences` 3 ran 3 times on time.
- [x] Generator (`generator-service`, `infrastructure.md` → "Generator"):
      refill below 30 min to 1 h ahead, batches with `SKIP LOCKED`, missed runs
      skipped, `ON CONFLICT DO NOTHING`, completes exhausted jobs, marks
      invalid stored schedules `FAILED`. Checked with ten edge-case schedules
      and two generators at once.
- [x] `GET /jobs/{id}` shows the next unfinished run (else the most recent)
      and, for recurring jobs, a `recurrence` block (cron, zone, limits, runs
      created).
- [ ] Mark a recurring job `FAILED` automatically in more cases? Today only an
      invalid stored schedule does; failed individual runs never fail the job
      (by design).
- [x] `GET /jobs/{id}/executions?limit=&before=`: newest first, keyset
      pagination on `scheduled_at` (served by the `UNIQUE (job_id,
      scheduled_at)` index), `nextBefore` cursor, `limit` 1–100 (default 20),
      `404` for an unknown job.
- [x] End-to-end check with all four services (2026-09-24): every-minute
      job, `maxOccurrences` 5, shortened window (3 min) passed on the command
      line so the generator had to act: API created and fast-pathed runs 1–3,
      generator created 4–5 at T+2 min, watcher published them, worker sent 5
      emails on time (attempt 1), generator marked the job `COMPLETED` 38 s
      after the last run. No errors in any log.
- [ ] Extend `docs/test-plan.md` with recurring-job phases.

## Next major item 2: multi-module Maven project

- [x] Restructure this repo into a parent `pom.xml` with modules `common`,
      `api-service`, `watcher-service`, `worker-service` (and later the
      generator). Each service is its own Spring Boot app and Docker image;
      `common` is a library packed into each JAR at build time, never
      deployed alone.
- [x] `common` holds only what's genuinely shared: the SQS message contract
      (`JobExecutionMessage`), SQS config, status enums, table shapes.
      Service-specific queries and logic stay in their service (the watcher's
      batch query, the worker's claim).
- [x] Decide who owns Flyway migrations: **a separate `db-migrations`
      module** (a small Spring Boot app that runs Flyway and exits). Runs as
      its own step before a deployment (e.g. a Kubernetes Job), so no service
      needs schema-changing database rights and no service owns the schema.
      Locally: run it once, and again whenever a migration is added. Flyway
      is off in all three services.
- [x] Split done (2026-09-24): parent `pom.xml` at the root (BOMs,
      versions, module list), Maven wrapper at the root, modules `common`,
      `db-migrations`, `api-service`, `watcher-service`, `worker-service`;
      `job-scheduler-service` removed; Lombok dropped (unused). Built, the
      API context test passes, and a fast-path and a watcher-path job ran end
      to end.
- [ ] Re-run the full test plan on the split services (phases 1, 3, 5, 7 at
      least).
- [ ] Health endpoints for the watcher and worker: they have no web server
      now, so a container platform can't probe them. Add Actuator with a
      management port when they're containerized.
- [ ] One Dockerfile per service (and `db-migrations`), and a Compose file
      that runs the whole system locally.
- [x] Remove the role switches (`app.watcher.enabled`, `app.worker.enabled`,
      the `watcher`/`worker` profiles): each service contains only its own
      code.
- [ ] Keep the message format backward compatible across services (add
      fields, never rename/remove), since old and new versions run side by
      side during deploys.

## Performance (only if measured to be needed)

Baseline from the burst test (`test-plan.md`): watcher ~140–175 msgs/s,
worker ~125 jobs/s each, both limited by SQS round trips from a laptop.
- [ ] Run close to AWS (same region as SQS and the DB): round trips drop from
      tens of ms to ~1–3 ms; the biggest single gain, no code change.
- [ ] Worker concurrency: tune `maxConcurrentMessages` / `maxMessagesPerPoll`
      (default 10) per worker, sized with the DB pool and mail provider limits.
- [ ] Watcher: parallel batches (several threads, each its own lock → send →
      update transaction), or lock 100 rows and send 10 `SendMessageBatch`
      calls concurrently. Only if more watcher instances aren't enough.
- [ ] Faster crash recovery: today it takes two visibility timeouts (~120 s,
      `stress-test-report.md` §7). Setting the message's visibility to 0 after
      the `PROCESSING` reset gives ~60 s but removes the margin for a
      slow-but-alive worker; do it together with the heartbeat below.
- [ ] Connection budget: size Hikari pools per service (or add PgBouncer);
      12 services × the default 10 connections exceed Postgres's 100.
- [ ] Round the SQS delay **up** instead of down, so a job never starts before
      its `scheduled_at` (observed up to ~0.9 s early).

## Templates

- [ ] Plain-text alternative alongside the HTML body (some clients prefer it;
      helps with spam filters).
- [ ] Automated test: for every template version, every `{{placeholder}}` in
      subject/body is declared in its `params_schema` (catches schema/template
      drift once per template, not per request).
- [ ] Cache the active template per task type in memory (short TTL) if the
      per-`POST` lookup ever shows up in measurements.
- [ ] Template management API/admin (templates are inserted via migrations
      for now).

## API features

- [ ] `GET /jobs` list endpoint: one join query + pagination (never per-row
      queries, i.e. no N+1).
- [ ] `GET /task-types` so a frontend can build its dropdown.
- [ ] Pause / cancel a job (`PAUSED` / `CANCELLED` exist in the schema); define
      what happens to already-generated executions.

## Failure handling (hardening pass)

- [ ] DLQ consumer: no longer needed to make `FAILED` reachable (the worker
      marks `FAILED` on the last attempt); would only matter for messages that
      reach the DLQ without the worker seeing their last attempt (e.g. a crash
      on the last attempt).
- [ ] `SesEmailSender` for real delivery.
- [ ] Exponential backoff between retries.
- [ ] Heartbeat for jobs that could exceed 60s: extend the SQS visibility
      timeout (`ChangeMessageVisibility`) while working.
- [ ] Optional: Redis lock for the worker, only if duplicate executions prove
      to be a real problem. Rejected for v1: same guarantee as the DB +
      visibility-timeout lease, but a second source of truth and a new failure
      mode. If built: `SET key token NX PX` with TTL = visibility timeout; lock
      held → leave the message (never delete: it may be the only copy);
      release in a `finally` via compare-and-delete (Lua); Redis down →
      transient failure.

## Security & configuration

- [ ] Authentication; take `userId` from the authenticated caller, not the
      request body. Until then an unknown `userId` violates the foreign key
      and returns `500` instead of `400`.
- [ ] Move DB credentials to environment variables (`SPRING_DATASOURCE_*`).
- [ ] Shorter DB connection-pool timeout for the API (e.g.
      `spring.datasource.hikari.connection-timeout=3s`). Found in testing: with
      the 30s default, every request during a DB outage hangs 30s before
      returning `500`, tying up request threads; failing fast serves clients
      better.
- [ ] Production AWS access via IAM roles, not access keys.
- [ ] AWS account is on a 33-day promotional Free Plan (created ~2026-09-22):
      upgrade or move before it expires if the project is still running.

## Code quality

- [ ] Inject a `java.time.Clock` instead of calling `now()` directly, so
      time-dependent logic (delay calculation, "due soon") is testable.
