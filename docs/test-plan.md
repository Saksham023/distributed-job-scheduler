# Test plan

Manual end-to-end tests, from the happy path to deliberate failures. Run them
in order: each phase assumes the previous ones pass. Every test states what
"pass" means, so the result is a clear yes or no.

## How to observe

| What | How |
|---|---|
| Statuses, attempts, errors | `dev/sql/05_test_results.sql` (queries 1–4) |
| Email count | Mailpit sidebar, or `curl -s "http://localhost:8025/api/v1/messages?limit=1" \| grep -o '"total":[0-9]*'` |
| Queue / DLQ counts | SQS console: `job-executions` and `job-executions-dlq` → "Messages available" / "in flight" |
| What each process did | IntelliJ Run window for API, Watcher(s), Worker(s) |

## Phase 0: clean start (before every phase)

1. Stop all app processes.
2. Run `dev/sql/06_reset_jobs.sql` (empties job data, keeps task types,
   templates, users).
3. Mailpit → **Delete all**. SQS console → `job-executions` → **Purge**
   (and the DLQ, if it has messages).
4. Start what the phase needs: **API**, **Watcher**, **Worker** (run
   configurations; `Worker` uses `AWS_PROFILE=job-scheduler-worker`).

## Phase 1: one job at a time (functional happy path)

Postman, `POST /api/v1/jobs`, `userId` 1, `taskType` `welcome_email`,
`params` `{"to": "test.user@example.com", "first_name": "Test"}`.

| # | Test | Pass |
|---|---|---|
| 1.1 | **Fast path**: `scheduledAt` = now + 2 min | `201` with `executionStatus: QUEUED`; ~2 min later: email in Mailpit, `GET` shows `status` and `executionStatus` `COMPLETED` |
| 1.2 | **Watcher path**: now + 7 min | `201` with `PENDING`; ~2 min later the watcher logs `queued 1`; at +7 min email + `COMPLETED` |
| 1.3 | **Optional param**: add `"company_name": "Acme"` | Email says "Thanks for signing up with Acme." |
| 1.4 | **Escaping**: `"first_name": "<b>Sam</b>"` | Email shows `<b>Sam</b>` as text, not bold |

## Phase 2: API validation (regression)

No worker needed. Each should return the stated error and create **no** rows.

| # | Request | Pass |
|---|---|---|
| 2.1 | `scheduledAt` in the past | `400`, `errors` names `scheduledAt` |
| 2.2 | `"taskType": "nope"` | `400` "Unknown task type" |
| 2.3 | `params` without `first_name` | `400`, `errors`: `params.first_name` |
| 2.4 | `"to": "not-an-email"` | `400`, `errors`: `params.to` |
| 2.5 | `GET /api/v1/jobs/abc` | `400` |
| 2.6 | `GET` a valid but unknown UUID | `404` |
| 2.7 | After 2.1–2.4: `SELECT count(*) FROM jobs` | `0` |

## Phase 3: load (happy path at volume)

Setup: `Watcher` and `Worker` both allow multiple instances (Edit
Configurations → Modify options → Allow multiple instances). Start API and
**2 × Worker**, **no watcher yet**. In `03`, set `generate_series(1, 2000)`.

1. Run `dev/sql/03_load_test_pending_executions.sql`.
2. **Immediately start 2 × Watcher together.** A fixed-delay `@Scheduled`
   task runs once at startup, so both drain at the same moment. (With
   watchers already running, their one-minute runs rarely line up and one
   drains everything alone, which never exercises `SKIP LOCKED`. 2,000 rows
   make the drain long enough to overlap despite slightly different startup
   times.)
3. Wait ~5 minutes.

| Check | Pass |
|---|---|
| `05` query 1 | 2,000 × `COMPLETED / COMPLETED`, attempt 1 |
| `05` queries 2–4 | empty |
| Mailpit total | exactly 2,000 |
| Watcher logs | **both** watchers queued a share, adding up to 2,000 |
| Queue | 0 available, 0 in flight |

## Phase 4: duplicate messages

Every execution gets **two** messages; exactly one email each must result.

1. In `03`, set `generate_series(1, 200)` and change `30 +` to `90 +` (all
   due 1.5–5 min out: late enough that the duplicates are created before any
   message is due, and still inside the watcher's 5-minute lookahead, so the
   first watcher run publishes all 200. A larger offset puts some rows outside
   the window, and those get only one message).
2. Start API, 1 × Watcher, **no workers yet**. Run `03`.
3. Wait for the watcher to log `queued 200` (within ~1 min).
4. Force a second publish of every execution:
   `UPDATE job_executions SET status = 'PENDING' WHERE status = 'QUEUED';`
   The watcher's next run publishes all 200 again → 400 messages.
5. Start **2 × Worker**. Wait until everything is due and processed (~7 min).

| Check | Pass |
|---|---|
| Mailpit total | exactly **200** (not 400) |
| `05` query 1 | 200 × `COMPLETED`; some with attempt 2 is fine |
| Worker logs | "reset to QUEUED" and/or deleted leftovers, no errors |
| Queue | empty afterwards |

## Phase 5: mail server down (transient failure)

Start API, Watcher, Worker. Create 3 jobs due in 1 min (Postman).

**5a. Recovers.** Stop Mailpit
(`docker compose -f ".../docker/mailpit/docker-compose.yml" stop`) right
after creating the jobs. Watch 2–3 minutes, then start it again (`start`).

| Check | Pass |
|---|---|
| While down (`05` query 2) | executions `QUEUED` with `error_message` (mail connection error), `attempt` climbing each minute |
| After restart | all `COMPLETED`, `attempt` > 1, 3 emails |

**5b. Gives up.** Same, but leave Mailpit stopped for ~8 minutes (5 attempts
a minute apart, then SQS moves each message to the DLQ one visibility timeout
after the last attempt).

| Check | Pass |
|---|---|
| `05` query 1 | 3 × `FAILED / FAILED`, attempt 5 |
| `error_message` | starts with "Gave up after 5 attempts" |
| DLQ | 3 messages available |
| Main queue | empty |

Restart Mailpit and purge the DLQ afterwards.

## Phase 6: bad data (permanent failure)

A job missing `first_name`, inserted directly (bypassing API validation):

```sql
WITH j AS (
    INSERT INTO jobs (user_id, task_type_id, template_id, params, schedule_type)
    SELECT u.id, t.task_type_id, t.id, '{"to": "test.user@example.com"}', 'ONE_TIME'
    FROM users u, templates t
    WHERE u.email = 'test.user@example.com' AND t.is_active
    RETURNING id
), s AS (
    INSERT INTO one_time_schedules (job_id, scheduled_at) SELECT id, now() + interval '1 minute' FROM j
)
INSERT INTO job_executions (job_id, scheduled_at) SELECT id, now() + interval '1 minute' FROM j;
```

| Check | Pass |
|---|---|
| `05` query 1 | `FAILED / FAILED`, **attempt 1** (no retries) |
| `error_message` | `Context: No method or field with name 'first_name' ...` (`Context` is JMustache's missing-value exception) |
| DLQ | empty (permanent failures delete their message) |
| Mailpit | no email |

## Phase 7: a worker "crashed" mid-job (`PROCESSING` reset)

1. Start API, Watcher, **no worker**. Create a job due in 3 min.
2. Once it's `QUEUED`, simulate a crashed worker:
   `UPDATE job_executions SET status = 'PROCESSING' WHERE status = 'QUEUED';`
3. Start the Worker.

| Check | Pass |
|---|---|
| Worker log at +3 min | "was PROCESSING; reset to QUEUED, message left for redelivery" |
| ~1 min later | `COMPLETED`, attempt 1 (only one real claim), exactly 1 email |

## Phase 8: processes killed during load

Phase 3 setup (2,000 rows, watchers started together). While it runs:

1. **Hard-kill one Watcher mid-drain** (`kill -9`, not a graceful stop):
   start two watchers together after inserting 2,000 rows and kill one within
   ~2 seconds.
2. **Hard-kill one Worker while it holds claimed executions**: trigger the
   kill on a condition (e.g. `PROCESSING` count > 0), not a wall-clock time.
3. Leave the remaining watcher and worker running.

| Check | Pass |
|---|---|
| `05` query 1 | all 2,000 `COMPLETED` (some later than others) |
| Mailpit | 2,000, or very slightly more: a duplicate is only possible for a job whose email was sent in the instant before its worker was killed (accepted at-least-once cost) |
| `05` queries 3–4 | empty |

## Phase 9: database outage

Start API, Watcher, Worker. Create a few jobs due in 2 min. Then stop
Postgres for ~30 seconds and start it again
(`docker compose -f ".../docker/postgres/docker-compose.yml" stop` / `start`).

| Check | Pass |
|---|---|
| While down | API `POST` returns `500`; watcher/worker log connection errors; nothing crashes permanently |
| After restart | without restarting any app: jobs complete, new `POST`s work |

## Results (run 2026-09-24)

All phases passed. Run from the terminal (API/watchers/workers started from
the JAR with the same profiles and AWS users as the IntelliJ configurations).

| Phase | Result |
|---|---|
| 1. One job at a time | Fast path, watcher path, optional section, HTML escaping: all correct |
| 2. API validation | `400`/`404` for every bad request; no rows created |
| 3. Load | 2,000 jobs: watchers split 980 / 1,020 (`SKIP LOCKED`), workers 1,054 / 946; 2,000 distinct emails; all attempt 1; 0 errors |
| 4. Duplicates | 322 messages for 200 executions (122 duplicated): exactly 200 emails; 21 caught by the `PROCESSING` reset, the rest found `COMPLETED` and deleted |
| 5a. Mail down, recovers | Failed attempts 1–2, completed on attempt 3; one email each |
| 5b. Mail down too long | `FAILED` on attempt 5 ("Gave up after 5 attempts"), 3 messages in the DLQ, main queue empty |
| 6. Bad data | `FAILED` on attempt 1 (Mustache "No method or field with name 'first_name'"), message deleted, DLQ empty |
| 7. Crashed worker | Reset at due time, completed 60s later; attempt 1; one email |
| 8. Killed processes | Watcher killed mid-drain: its uncommitted batch republished, 4 duplicates caught by the reset; worker killed with fetched-but-unstarted messages: redelivered and completed. 2,000 distinct emails |
| 9. DB outage | Everything recovered without restarts; `POST` during the outage `500` (no row written) |

Notes from the run:

- **Phase 4** first used due times of 3–6.5 min; rows due beyond the 5-minute
  lookahead weren't published in the first watcher run, so only 122 of 200
  got duplicates. The steps above now use a window that keeps all rows inside
  the lookahead.
- **Phase 8**: the worker was killed earlier than planned (a wall-clock trigger
  failed in zsh) and held no claimed executions at that moment. The
  claimed-but-unfinished case is covered deterministically by phase 7.
- **SQS counts messages delayed by a per-message `DelaySeconds` as "not
  visible"** (in flight), not as "delayed"; the "delayed" count only covers a
  queue-level delay. Counts are approximate and jump between readings.
- **Findings fixed or tracked:** SMTP had no timeouts (a hanging mail server
  would block worker threads past the visibility timeout): fixed with
  connection/read/write timeouts before phase 5. A DB outage makes each API
  request wait the pool's 30s default before failing: shorter pool timeout in
  the backlog.

## Burst test: 2,000 jobs due at the same instant (run 2026-09-24)

Measures watcher publish time and worker drain time when everything falls due
at once. `dev/sql/07_burst_test.sql` inserts N jobs with one `scheduled_at`
(T >= 8 min ahead, so one watcher run publishes them all at T - 5 min);
`dev/sql/08_burst_test_results.sql` reports drain time, lateness percentiles
and a throughput curve. Run from the terminal with `-Xmx512m` per JVM, API not
needed (migrations already applied).

| | 1 watcher, 1 worker | 1 watcher, 2 workers |
|---|---|---|
| Watcher publish (2,000) | 14.4 s | 11.4 s |
| Last job finished after T | 15.1 s | 8.0 s |
| Drain rate | ~125/s | ~227/s |
| Start lateness p50 / p95 / max | 7.0 / 14.2 / 15.0 s | 3.5 / 7.4 / 8.0 s |
| Processing per job (claim → complete) | 15 ms | 19 ms |
| Result | 2,000 `COMPLETED`, attempt 1, 2,000 emails, 0 errors | same |

Findings:

- **A worker is network-bound, not CPU-bound.** Each job takes ~15 ms of
  work, but a worker handles at most 10 messages at a time (Spring Cloud AWS
  default) and polls the next 10 when those finish: ~80 ms per cycle, of which
  ~65 ms is the SQS round trip (laptop → `ap-south-1`). Hence the near-2×
  gain from a second worker. Levers: more workers, or raise
  `maxConcurrentMessages` per worker (both in the backlog).
- **Watcher publish** is ~70 ms per batch of 10, dominated by the SQS call;
  varies run to run with internet latency (14.4 s vs 11.4 s, same code).
- **Jobs start up to ~0.9 s early**: `DelaySeconds` is whole seconds and is
  rounded down (backlog).

## After testing

Stop all processes, run `06_reset_jobs.sql`, clear Mailpit, purge both queues,
and set `03` back to `generate_series(1, 200)` and `30 +`.
