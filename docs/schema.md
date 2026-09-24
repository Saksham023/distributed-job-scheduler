# Database schema

PostgreSQL, chosen over DynamoDB/Mongo/Cassandra deliberately: this project's
scale doesn't need horizontal write scaling or single-key partition access
patterns, and its actual access patterns (join jobs to their schedule,
range-scan `job_executions` by status + time) are exactly what a relational
engine with B-tree indexes is built for. The one semi-flexible field
(`jobs.params`) lives as `JSONB` inside an otherwise normalized schema rather
than being a reason to go full document-store.

The schema is managed by **Flyway**: versioned SQL files in
[`db-migrations/src/main/resources/db/migration/`](../db-migrations/src/main/resources/db/migration/),
starting with `V1__init.sql`. The `db-migrations` app (a one-shot Spring Boot
process, run before the services) applies any migration not yet
recorded in the database's `flyway_schema_history` table. Rules: never edit a
migration that has already run (checksum validation fails startup); every
schema change is a new `V<n>__<description>.sql` file. Chosen over Docker's
init-script mechanism (runs only once on an empty database, so later changes
never reach existing databases) and over Liquibase (also standard, but
XML/YAML changelogs; Flyway's plain SQL matches the hand-written-SQL approach).

**`spring.flyway.placeholder-replacement=false`**: Flyway normally replaces
`${name}` in migrations with configured values. We don't use that feature,
and migrations that carry template content (HTML, JSON Schema) can contain
`${` (e.g. a dollar-quote tag followed by `{`), which Flyway would reject as
an undefined placeholder. Test migrations by running them through Flyway, not
only `psql`: `psql` doesn't do placeholder replacement.

## Tables

- **`users`** — owns jobs. Kept minimal (no auth) since this project isn't
  building authentication, just enough to scope/authorize job ownership
  (`GET /jobs` needs to filter to the caller's own jobs).
- **`task_types`** — extensible catalog of job kinds (`welcome_email`,
  `reminder_email`, ...; a `service` column anticipates future non-email
  channels like SMS/push). A real lookup table because this list is
  genuinely open-ended and carries attached metadata (templates reference it).
- **`templates`** — the content per task type: an HTML `body`, a plain-text
  `subject`, and a `params_schema` (JSON Schema) listing the `params` a job
  must supply. Versioned: a change is a **new row** (next `version`,
  `is_active`), never an edit of an existing one. See "Templates and `params`
  validation" below.
- **`jobs`** — the request itself: owner, task type, `params` (JSONB —
  recipient, template variables, whatever the task type needs),
  `schedule_type`, and `template_id` (the exact template version the job was
  validated against). Deliberately does **not** hold `recipient` as its own
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

## Templates and `params` validation

- **Body is HTML, subject is plain text.** Rendered with **Mustache**
  (JMustache) in the worker, not with hand-written string replacement, which
  would need its own HTML escaping, would re-substitute inside inserted values
  (a `first_name` of `{{to}}`), and can't do optional sections. Mustache
  escapes `{{x}}` in HTML by default (client values can't inject links/markup;
  `{{{x}}}`, unescaped, is never used for client data), fails on a missing
  `{{x}}` by default, and omits an optional `{{#x}}…{{/x}}` section when `x` is
  absent. The subject is rendered with HTML escaping **off** (it isn't HTML).
  Chosen over Thymeleaf (built for web pages, heavier) and FreeMarker (allows
  much more logic in templates than we want).
- **Required `params` are declared, not inferred**: each template version has
  a `params_schema` in **JSON Schema**, the industry standard for describing
  valid JSON (used by OpenAPI, Kubernetes CRDs, Confluent Schema Registry). A
  per-task-type Java DTO isn't possible: task types are data (rows added
  without a deployment), so their parameter rules must be data too. The
  schema covers more than placeholders: `to` (required, email format) is never
  in the template text; optional fields and types are expressed too.
  Declared rather than parsed from the template on every `POST`.
- **`POST /jobs`**: load the task type's active template → validate `params`
  against its `params_schema` → `400` listing the failing fields → save the
  job with `template_id`. The template text is not parsed at `POST`.
- **Template version pinned on the job** (`jobs.template_id`). A job is
  validated against today's template but may run next week; if the template
  changed in between (e.g. a new required placeholder), using the new version
  would fail a job that was valid when accepted. The worker loads the template
  by `jobs.template_id`, never "whichever is active now". For recurring jobs
  (later) pinning means content never updates for the job's lifetime:
  revisit then.
- **Safety net in the worker**: rendering is strict, so a placeholder missing
  from `params` (e.g. schema and template out of sync) makes the execution a
  **permanent** failure (`FAILED`), never a half-filled email.
- **Welcome email v1 `params`**: `to` (required, email), `first_name`
  (required, non-empty), `company_name` (optional; an optional section in the
  template).
- No client-supplied `from`: the sender is always `app.email.from`.
- **Unknown `params` are ignored**, not rejected: only the declared, required
  fields must be present (`additionalProperties` left open). Trade-off: a
  misspelled *optional* field is silently ignored.
- **Exactly one active template per task type**, enforced by a partial unique
  index (`task_type_id` WHERE `is_active`), plus `UNIQUE (task_type_id,
  version)`. New jobs always get the active version; existing jobs keep the
  (possibly inactive) version they were accepted with.
- **Versions are never edited** (convention: templates only arrive through
  reviewed migrations). A trigger could enforce it later.
- **No active template for a task type at `POST` → `500`** (our
  misconfiguration, not the client's), logged.
- **Schema violations → `400`** in the same `errors` list (`field`,
  `message`) as the DTO validation errors.
- **`jobs.template_id` added with expand → backfill → contract**: add the
  column nullable, fill existing rows with their task type's active template,
  then set `NOT NULL`. The migration must work on a database that already has
  rows, not only an empty one.

## Status lifecycle

Fixed-list values (`schedule_type`, `jobs.status`, `job_executions.status`,
`task_types.service`) are stored as the **uppercase Java enum name**
(`'ONE_TIME'`, `'PENDING'`, ...), enforced by `CHECK` constraints
(`V3__use_enum_names_for_fixed_values.sql`). Java maps them to enums
(`ScheduleType`, `JobStatus`, `JobExecutionStatus`); repositories bind
`enum.name()` when writing. Lowercase names below are prose, not stored values.

**`job_executions.status`**: `pending` → `queued` (SQS push confirmed) →
`processing` (worker claim — see `docs/infrastructure.md` → "Worker") →
`completed`, or `failed` (permanent error, or transient error on the last
attempt). A transient failure sets it back to `queued` with `error_message`
while SQS redelivers; `attempt` increments on every claim. A worker that
receives a message whose execution is `processing` never executes it: it
resets it to `queued` (only `WHERE status = 'PROCESSING'`) and leaves the
message for redelivery. `processing` therefore always means "a worker has it
right now (or crashed mid-way)".

**`jobs.status`**: `active` (default) → `completed` or `failed`. For a one-time
job it mirrors its single execution's terminal outcome (`failed` added to
`jobs.status` for this). Who flips it, decided per schedule type since the
two cases aren't symmetric:
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

**`failed` means "gave up"**, not "one attempt errored": set by the worker
for a permanent error, or for a transient error on the last allowed attempt
(the message then goes to the DLQ for human inspection).

## Deferred — real-system features, not needed for learning

Scoped out deliberately, not forgotten (tracked in `docs/backlog.md`):
- **Pause/cancel a job.** `jobs.status` reserves `paused`/`cancelled`, but
  there's no API or logic behind them yet, and no defined behavior for what
  happens to already-generated `job_executions` when a job is cancelled
  mid-flight.

## Open decisions

Tracked in [`backlog.md`](backlog.md) → "Next major item 1: recurring jobs"
(cron library, template pinning for recurring jobs, generator lookahead).
