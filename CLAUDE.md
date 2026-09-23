# Job Scheduler

A learning project: a job scheduler that accepts scheduling requests over HTTP,
persists them, and executes them (once, or on a recurring cron-like schedule)
via a poller + queue + worker pipeline. Not built for the 10,000 req/s scale of
a production system like AWS's own scheduler — deliberately scoped down to a
single Postgres instance and a straightforward service, for learning purposes.

## Planned architecture

- **API** — `POST /jobs` to schedule a job, `GET /jobs/:id` (and `GET /jobs`)
  to check status. One main service talking to Postgres, no separate
  services per component.
- **Generator** — periodic process that tops up `job_executions` rows for
  recurring jobs, staying a lookahead window ahead of `recurring_schedules.generated_until`.
- **Watcher/poller** — periodic process (e.g. every minute) that selects
  `job_executions` due in the next 5 minutes and pushes them to SQS.
- **Queue + workers** — SQS; workers consume, execute the job, update the
  execution's status. See [`docs/infrastructure.md`](docs/infrastructure.md)
  for the crash/retry/dedup semantics.

## Docs

- [`docs/schema.md`](docs/schema.md) — table-by-table schema design and the
  reasoning behind each normalization/lookup-table decision. Full DDL in
  Flyway migrations under
  `job-scheduler-service/src/main/resources/db/migration/` (`V1__init.sql`, ...);
  the app applies them automatically at startup.
- [`docs/infrastructure.md`](docs/infrastructure.md) — SQS queue design
  (ack ordering, idempotency, DLQ) and local Postgres via Docker.

## Build philosophy: happy path first

The schema and SQS design already account for failure modes (statuses on
`jobs`/`job_executions`, the ack-last ordering, DLQ). But for the first
working version, **failure handling is deliberately not implemented** — a
worker dying mid-processing, redelivery, retries, DLQ wiring, and similar
recovery scenarios are known and documented (see `docs/infrastructure.md`),
not solved. Get one-time and recurring jobs flowing end-to-end first; come
back to failure/recovery scenarios as a deliberate later pass once there's a
working demo, not before.

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
