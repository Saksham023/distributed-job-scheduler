# Backlog

Everything deliberately deferred, in one place. Add an item whenever something
is postponed; remove it when it's done. Design reasoning lives in
`schema.md` / `infrastructure.md`; this file is the to-do list.

## Next up (finishes v1)

- [x] `JobController`: `POST /api/v1/jobs` (201 + `Location`),
      `GET /api/v1/jobs/{id}`. Fast path verified end-to-end (job due in 2 min
      → `QUEUED`).
- [x] `GlobalExceptionHandler`: domain exceptions and Spring MVC errors return
      `ProblemDetail` (RFC 9457); validation failures include an `errors` list.
- [x] Manual end-to-end test (Postman): job due in 2 min → `QUEUED`; job due
      in 1 h → `PENDING`; 404 / 400 error shapes verified.
- [x] Watcher: scheduled task that publishes `PENDING` executions due within
      the lookahead window and marks them `QUEUED` (`watcher/ExecutionWatcher`,
      runs in its own process via the `watcher` profile). Verified end to end.
- [ ] Worker: receive → claim → execute → complete → delete message, with
      the `PROCESSING` reset and permanent/transient/last-attempt failure
      handling. Design settled in `infrastructure.md` → "Worker". Needs its own
      IAM identity (receive/delete/change-visibility only).
- [ ] SQS console: raise `job-executions` redrive `maxReceiveCount` 3 → 5.
- [ ] Migration: add `FAILED` to `jobs.status` (+ `JobStatus` enum).
- [x] "Send email" in v1: `EmailSender` interface; Mailpit locally
      (`docker/mailpit/`), SES later.
- [ ] Migration: `templates.params_schema` (JSON Schema), `jobs.template_id`
      (pinned template version); `welcome_email` v1 template row (HTML body,
      subject, schema: `to`, `first_name`, optional `company_name`).
- [ ] `POST /jobs`: validate `params` against the active template's schema
      (networknt json-schema-validator, Jackson 3) and save `template_id`.
- [x] Worker rendering with JMustache (strict; subject without HTML escaping):
      `template/TemplateRenderer`; `SmtpEmailSender` sends HTML. Verified in
      Mailpit (optional section, HTML escaping, missing value → not sent).
- [x] Receiving messages: `@SqsListener` (Spring Cloud AWS 4.x) in manual
      acknowledgement mode (see `infrastructure.md` → "Worker").
- [ ] `TaskService` enum once the worker reads `task_types.service`.
- [ ] Integration tests with Testcontainers (real Postgres, runs Flyway).

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
- [ ] Reconcile executions stuck at `QUEUED` whose SQS message no longer
      exists (queue purged, message outlived the 4-day retention, lost). The
      watcher only picks up `PENDING`, so such rows are never delivered. Fix:
      periodically republish `QUEUED` rows older than a threshold (e.g.
      scheduled more than 15 min ago); the worker's claim makes any resulting
      duplicate harmless. Found in local testing after purging the queue.
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
- [ ] Production AWS access via IAM roles, not access keys.
- [ ] AWS account is on a 33-day promotional Free Plan (created ~2026-09-22):
      upgrade or move before it expires if the project is still running.

## Code quality

- [x] One shared lookahead (`app.watcher.lookahead`) used by both the `POST`
      fast path and the watcher.
- [ ] Inject a `java.time.Clock` instead of calling `now()` directly, so
      time-dependent logic (delay calculation, "due soon") is testable.
- [x] Root `.gitignore`; first commits pushed to GitHub.
