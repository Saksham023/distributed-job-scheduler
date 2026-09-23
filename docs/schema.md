# Database schema

PostgreSQL, chosen over DynamoDB/Mongo/Cassandra deliberately: this project's
scale doesn't need horizontal write scaling or single-key partition access
patterns, and its actual access patterns (join jobs to their schedule,
range-scan `job_executions` by status + time) are exactly what a relational
engine with B-tree indexes is built for. The one semi-flexible field
(`jobs.params`) lives as `JSONB` inside an otherwise normalized schema rather
than being a reason to go full document-store.

The schema is managed by **Flyway**: versioned SQL files in
[`job-scheduler-service/src/main/resources/db/migration/`](../job-scheduler-service/src/main/resources/db/migration/),
starting with `V1__init.sql`. At startup Flyway applies any migration not yet
recorded in the database's `flyway_schema_history` table. Rules: never edit a
migration that has already run (checksum validation fails startup); every
schema change is a new `V<n>__<description>.sql` file. Chosen over Docker's
init-script mechanism (runs only once on an empty database, so later changes
never reach existing databases) and over Liquibase (also standard, but
XML/YAML changelogs; Flyway's plain SQL matches the hand-written-SQL approach).

## Tables

- **`users`** — owns jobs. Kept minimal (no auth) since this project isn't
  building authentication, just enough to scope/authorize job ownership
  (`GET /jobs` needs to filter to the caller's own jobs).
- **`task_types`** — extensible catalog of job kinds (`welcome_email`,
  `reminder_email`, ...; a `service` column anticipates future non-email
  channels like SMS/push). A real lookup table because this list is
  genuinely open-ended and carries attached metadata (templates reference it).
- **`templates`** — the content per task type. Versioned, with `is_active`
  marking which version is currently used; supports swapping content without
  touching jobs or job_executions. Placeholders (e.g. `{{first_name}}`) are
  filled at send time from `jobs.params`.
- **`jobs`** — the request itself: owner, task type, `params` (JSONB —
  recipient, subject, template variables, whatever the task type needs), and
  `schedule_type`. Deliberately does **not** hold `recipient` as its own
  column — folded into `params` since it's just more template-rendering
  data, not something that needs its own indexable column at this scale.
- **`one_time_schedules`** / **`recurring_schedules`** — schedule details
  split into satellite tables (1:1 with `jobs` via `job_id` as PK) instead of
  nullable columns bolted onto `jobs`. See "Design decisions" below.
- **`job_executions`** — the actual queue of concrete, timestamped instances
  to run. Decoupled from `jobs` so recurring jobs can have many executions
  pre-generated ahead of their fire time. This is the table the poller scans
  (`status = 'PENDING' AND scheduled_at <= now() + 5 min`) and the table that
  tracks per-attempt status/errors for retries.

## Design decisions worth remembering

- **`schedule_type` is a constrained string (`CHECK`), not a lookup table
  with a foreign key.** Rule used to decide this in general: a lookup table
  earns its place when the value set is genuinely open-ended and can grow
  without a code change (`task_types` qualifies). A value like `schedule_type`
  is structural — it decides which satellite table to join and which code
  path the generator takes — so a new value always requires new code anyway.
  Same reasoning applies to `jobs.status` and `job_executions.status`.

- **Schedule details are normalized into `one_time_schedules` /
  `recurring_schedules` rather than kept as nullable columns on `jobs`.**
  This is a genuine "both work" judgment call, not a performance-driven one:
  - NULLs are *not* a meaningful space cost (Postgres stores a null bitmap,
    ~1 bit per nullable column per row — not the column's declared width).
  - The join cost is a primary-key-indexed lookup
    (`jobs.id = one_time_schedules.job_id`), effectively free at this scale.
  - The real trade: normalized costs one extra insert (in a transaction) at
    job-creation time and a bit more query-building code, in exchange for a
    schema where no column is ever null "because it doesn't apply," and room
    to add a third schedule shape later without touching `jobs`.
  - General framework for this kind of "both are valid" call: weigh where
    the system is actually likely to grow, who else has to read the schema,
    what the query layer/ORM makes cheap vs. annoying, whether there's a
    *measured* (not hypothetical) performance concern, and existing
    convention elsewhere in the codebase — roughly in that order.

- **`generated_until` on `recurring_schedules`** is the watermark the
  generator advances; drives a lookahead-window top-up strategy (e.g.
  "always keep 1 day of executions materialized") rather than a fixed count
  (e.g. "next 100 executions"), since a fixed count doesn't scale sensibly
  across jobs with very different frequencies (10s vs. daily).

- **`UNIQUE (job_id, scheduled_at)` on `job_executions`** makes the generator
  idempotent if it ever runs twice for the same window.

- **UUID primary keys are UUIDv7, generated by the database**
  (`DEFAULT uuidv7()`, Postgres 18+, set in `V2__use_uuidv7.sql`). v7 starts
  with a millisecond timestamp, so new IDs sort after old ones and inserts
  append to the end of the primary-key B-tree; random v4
  (`gen_random_uuid()`, the original V1 default) scatters inserts across the
  index and degrades once it outgrows memory — matters most for the
  fast-growing `job_executions`. Generated DB-side rather than in Java so
  every writer (app, future services, manual `psql`) gets correct IDs; the
  app reads them back via `INSERT ... RETURNING id`.

## Status lifecycle

Fixed-list values (`schedule_type`, `jobs.status`, `job_executions.status`,
`task_types.service`) are stored as the **uppercase Java enum name**
(`'ONE_TIME'`, `'PENDING'`, ...), enforced by `CHECK` constraints
(`V3__use_enum_names_for_fixed_values.sql`). Java maps them to enums
(`ScheduleType`, `JobStatus`, `JobExecutionStatus`); repositories bind
`enum.name()` when writing. Lowercase names below are prose, not stored values.

**`job_executions.status`**: `pending` → `queued` (SQS push confirmed) →
`processing` (worker claim — see `docs/infrastructure.md`) → `completed` /
`failed`. A retry does **not** go back to `pending`/`queued` — it stays at
`processing` through redeliveries, `attempt` incrementing each time, until
it either completes or (not yet built) reaches the DLQ.

**`jobs.status`**: `active` (default) → `completed`. Who flips it to
`completed`, decided per schedule type since the two cases aren't
symmetric:
- **One-time**: the worker does it directly, in the same transaction as
  marking the single `job_executions` row `completed` — trivial, since a
  one-time job has exactly one execution ever, no ambiguity.
- **Recurring**: **not** the worker's job — deliberately left to the
  generator. A worker checking "any non-terminal executions left for this
  job?" would be wrong: a recurring job can have zero pending executions
  simply because the generator hasn't topped up its next batch yet
  (`generated_until` is a lookahead watermark, not "all future occurrences
  exist as rows"). Only the generator actually knows the recurrence rule
  (`ends_at`/`max_occurrences`), so on each run, after advancing
  `generated_until`, if it determines no further occurrences will ever be
  generated **and** the last generated execution is itself terminal, it
  flips `jobs.status = 'completed'`. If that last execution isn't terminal
  yet, it just skips and re-checks next run — idempotent, no new component
  needed.

**`failed` is currently unreachable** — nothing in the v1 design sets it.
The intended meaning, once built: `job_executions.status = 'failed'` is set
by the (not-yet-built) DLQ consumer when a message exhausts
`maxReceiveCount` and lands in the DLQ — i.e. `failed` means "gave up after
repeated attempts, needs human inspection," not "a single attempt errored."

## Deferred — real-system features, not needed for learning

Scoped out deliberately, not forgotten:
- **Pause/cancel a job.** `jobs.status` reserves `paused`/`cancelled`, but
  there's no API or logic behind them yet, and no defined behavior for what
  happens to already-generated `job_executions` when a job is cancelled
  mid-flight.
- **DLQ consumer** — see `docs/infrastructure.md`. Needed to make `failed`
  actually reachable.
- **Redis distributed lock** for the worker claim race — see
  `docs/infrastructure.md`, planned for the post-v1 hardening pass.

## Open / not yet decided

- `cron_expression` parsing library (needs to support arbitrary cron syntax,
  not just fixed intervals) — not yet chosen.
