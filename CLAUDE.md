# Job Scheduler

A learning project: a job scheduler that accepts scheduling requests over HTTP,
persists them, and executes them (once, or on a recurring cron-like schedule)
via a poller + queue + worker pipeline. Not built for the 10,000 req/s scale of
a production system like AWS's own scheduler — deliberately scoped down to a
single Postgres instance and a straightforward service, for learning purposes.

## Planned architecture

- **API** — `POST /jobs` to schedule a job, `GET /jobs/:id` (and `GET /jobs`)
  to check status.
- **One codebase, three processes (for now).** API, watcher and worker live in
  one Spring Boot project, each role in its own package, and run as separate
  processes: the `watcher` / `worker` Spring profiles switch the role on
  (`app.watcher.enabled`, `app.worker.enabled`) and turn off the web server
  and Flyway. Later: a multi-module Maven project (see backlog).
- **Generator** — periodic process that tops up `job_executions` rows for
  recurring jobs, staying a lookahead window ahead of `recurring_schedules.generated_until`.
- **Watcher/poller** — periodic process (e.g. every minute) that selects
  `job_executions` due in the next 5 minutes and pushes them to SQS.
- **Queue + workers** — SQS; workers consume, execute the job, update the
  execution's status. See [`docs/infrastructure.md`](docs/infrastructure.md)
  for the crash/retry/dedup semantics.

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

## Docs

- [`docs/schema.md`](docs/schema.md) — table-by-table schema design and the
  reasoning behind each normalization/lookup-table decision. Full DDL in
  Flyway migrations under
  `job-scheduler-service/src/main/resources/db/migration/` (`V1__init.sql`, ...);
  the app applies them automatically at startup.
- [`docs/infrastructure.md`](docs/infrastructure.md) — SQS queue design
  (ack ordering, idempotency, DLQ), the watcher and worker, email, and local
  Postgres/Mailpit via Docker.
- [`docs/test-plan.md`](docs/test-plan.md) — manual end-to-end test plan
  (happy path, load, duplicates, failures, crashes, outages) and the results
  of the last run.

## Build philosophy: happy path first

Get one-time jobs flowing end-to-end first. Failure handling is designed up
front and documented, but only the parts settled for v1 are built: the
watcher's batch/failure rules and the worker's rules above (claim, `PROCESSING`
reset, permanent/transient/last-attempt failures, ack last). Further hardening
(heartbeats, backoff, DLQ consumer, optional Redis lock) stays in the backlog
until there's a working demo.

## v1 scope

One-time jobs only. Recurring jobs, the generator and the cron library come
after v1. Service code lives in `job-scheduler-service/` (Spring Boot 4,
Java 21, `JdbcClient` with hand-written SQL, Flyway, AWS SDK v2 SQS).

## Backlog

Everything deferred (known gaps, post-v1 features, hardening, open decisions)
is tracked in [`docs/backlog.md`](docs/backlog.md). When something is
postponed, add it there; when it's done, remove it.

## Dev SQL

Manual, local-only SQL (test user, inspection queries) lives in `dev/sql/`,
outside `src/` so it's never packaged or mistaken for a Flyway migration.
