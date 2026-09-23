# Backlog

Everything deliberately deferred, in one place. Add an item whenever something
is postponed; remove it when it's done. Design reasoning lives in
`schema.md` / `infrastructure.md`; this file is the to-do list.

## v1: one-time jobs (done)

- [x] API: `POST /api/v1/jobs` (201 + `Location`), `GET /api/v1/jobs/{id}`,
      `ProblemDetail` errors; `params` validated against the active template's
      JSON Schema; template version pinned on the job (`jobs.template_id`).
- [x] Watcher (`watcher` profile): batches of 10, `FOR UPDATE SKIP LOCKED`,
      drains until empty, shared lookahead with the `POST` fast path.
- [x] Worker (`worker` profile): `@SqsListener` in manual acknowledgement
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

## Recurring jobs (post-v1)

- [ ] API: optional `cronExpression` on `CreateJobRequest`; its presence means
      `RECURRING` (backward compatible, no change for existing clients).
- [ ] `JobService.createJob`: replace the hardcoded `ScheduleType.ONE_TIME`
      with a `switch` on the schedule type; add
      `JobRepository.insertRecurringSchedule`; compute the first execution
      from the cron expression.
- [ ] Choose a cron parsing library.
- [ ] Add a `timezone` column to `recurring_schedules` ("9am daily" needs a
      zone; use `ZonedDateTime` for the calculation).
- [ ] Generator: tops up `job_executions` within a lookahead window, advances
      `generated_until`, flips `jobs.status` to `COMPLETED` when the schedule
      is exhausted and the last execution is terminal.
- [ ] `GET /jobs/{id}/executions` (a recurring job has many executions).
- [ ] Give `@Scheduled` tasks their own thread pool
      (`spring.task.scheduling.pool.size`) when the generator is added: by
      default all scheduled tasks share one thread, so a long watcher drain
      would delay the generator.
- [ ] Move to a multi-module Maven project in this same repo: `common`,
      `api-service`, `watcher-service`, `worker-service`. Each service is its
      own Spring Boot app and Docker image; `common` is a library packed into
      each JAR at build time (never deployed alone). `common` holds only
      what's genuinely shared: the SQS message contract
      (`JobExecutionMessage`), SQS config, status enums, table shapes.
      Service-specific queries and logic stay in their service. Decide who
      owns Flyway migrations (API service, or a pipeline step). Until then the
      watcher runs from the same codebase in its own process via
      `app.watcher.enabled` / the `watcher` Spring profile (Option C).

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

- [x] One shared lookahead (`app.watcher.lookahead`) used by both the `POST`
      fast path and the watcher.
- [ ] Inject a `java.time.Clock` instead of calling `now()` directly, so
      time-dependent logic (delay calculation, "due soon") is testable.
- [x] Root `.gitignore`; first commits pushed to GitHub.
