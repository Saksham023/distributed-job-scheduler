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
- [ ] Watcher: scheduled task that publishes `PENDING` executions due within
      the lookahead window and marks them `QUEUED`.
- [ ] Worker: receive → atomic claim → execute → `COMPLETED` → delete message;
      flips a one-time job's `jobs.status` to `COMPLETED`. Needs its own IAM
      identity (receive/delete/change-visibility only).
- [ ] Decide what "send email" means in v1 (AWS SES vs. logging stand-in).
- [ ] `welcome_email` template row (worker renders it) and a `TaskService`
      enum once the worker reads `task_types.service`.
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

## API features

- [ ] `GET /jobs` list endpoint: one join query + pagination (never per-row
      queries, i.e. no N+1).
- [ ] `GET /task-types` so a frontend can build its dropdown.
- [ ] Pause / cancel a job (`PAUSED` / `CANCELLED` exist in the schema); define
      what happens to already-generated executions.

## Failure handling (hardening pass)

- [ ] Redis distributed lock for the worker claim race (design in
      `infrastructure.md`, v2).
- [ ] DLQ consumer: set `job_executions.status = 'FAILED'` for messages that
      exhausted `maxReceiveCount` (currently `FAILED` is unreachable).
- [ ] Exponential backoff between retries.
- [ ] Visibility-timeout heartbeat (`ChangeMessageVisibility`) for long jobs.

## Security & configuration

- [ ] Authentication; take `userId` from the authenticated caller, not the
      request body. Until then an unknown `userId` violates the foreign key
      and returns `500` instead of `400`.
- [ ] Move DB credentials to environment variables (`SPRING_DATASOURCE_*`).
- [ ] Production AWS access via IAM roles, not access keys.
- [ ] AWS account is on a 33-day promotional Free Plan (created ~2026-09-22):
      upgrade or move before it expires if the project is still running.

## Code quality

- [ ] Make the 5-minute window one shared property used by both the
      `POST` fast path (`JobService.FAST_PATH_WINDOW`) and the watcher.
- [ ] Inject a `java.time.Clock` instead of calling `now()` directly, so
      time-dependent logic (delay calculation, "due soon") is testable.
- [ ] Root `.gitignore` with `.idea/`; first commit.
