# Infrastructure

## Queue: SQS

Chosen over self-hosting a queue mainly for **per-message delay** — the
watcher pushes an execution to SQS with a `DelaySeconds` matching how far out
its `scheduled_at` is (bounded by the watcher's 5-minute lookahead, well
under SQS's 15-minute max delay), so the message only becomes visible to
workers when it's actually due.

### Worker crash handling — no separate reconciliation sweep needed

SQS's visibility timeout already does the job a "find stuck executions" cron
would otherwise need to do: a worker that crashes mid-job never deletes the
message, so after the visibility timeout expires SQS redelivers it to
another worker automatically.

Required ordering for this to be safe — **ack (delete the SQS message) last**:

1. Claim the execution (see below), set `started_at = now()`
2. Do the actual work (send the email)
3. Set `status = 'COMPLETED'` / `'FAILED'` (+ `error_message`), `finished_at = now()`
4. Only then delete the SQS message

This means at-least-once execution, not exactly-once — accepted trade-off,
not a bug to fix.

### Claiming an execution (POST fast-path can leave a row at `pending`)

Whoever pushes to SQS (the `POST /jobs` fast path or the watcher) is
responsible for flipping `job_executions.status` to `'queued'` right after a
successful send. In the ordinary case a worker only ever sees `'queued'`.
But if the pusher crashes after the send succeeds and before that DB update
commits, the message is genuinely in the queue while the row still says
`'pending'` — a worker must treat that the same as `'queued'`, not as an
error state.

**v1 claim query** (a single atomic conditional update, not a separate
read-then-write — the read-then-write version has a race where two workers
can both pass a status check before either writes):

```sql
UPDATE job_executions
SET status = 'PROCESSING', started_at = now(), attempt = attempt + 1
WHERE id = ?
  AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
RETURNING *;
```

Zero rows returned → this message is a stray/terminal duplicate → delete it
from SQS and stop, no work done. A row returned → this worker owns it → do
the work.

**Known accepted gap in v1:** this query can't distinguish "another worker
is genuinely still processing this right now" from "the previous claimant
died" — both look like `status = 'processing'`. Two messages for the same
`job_execution_id` (possible via the same pusher-crash race above, now
duplicated) can therefore both pass this claim query if they arrive close
enough together, causing a real double-send. This is accepted as a known,
narrow-window risk for v1 — not fixed by a timestamp heuristic (guessed
staleness thresholds trade a false-steal risk for a false-wait risk, neither
correctly).

**v2 (planned, not v1 — deliberately not skipped): a Redis distributed
lock**, keyed by `job_execution_id`, TTL matching the queue's visibility
timeout (`60s`). A worker must acquire the lock before claiming; failure to
acquire means another worker verifiably holds it right now (not a guess),
so the message can be deleted immediately as a confirmed duplicate — the
lock owner's own message remains in the queue as the retry path if it dies.
Release must be a compare-and-delete (check a unique per-claim token before
deleting, e.g. via a small Lua script) rather than a plain `DEL`, otherwise
a slow worker can delete a lock some other worker has since legitimately
acquired after the first one's TTL expired. A single Redis instance with
this pattern is sufficient here — full multi-node Redlock consensus isn't
needed for what this is protecting. Tracked as the first item to build in
the post-v1 hardening pass.

### Poison messages — `maxReceiveCount` + DLQ

A job that reliably crashes every worker that touches it would otherwise
redeliver forever. SQS's redrive policy handles this natively: after
`maxReceiveCount` deliveries, SQS routes the message to a dead-letter queue
instead of redelivering. Config, not application code — set this up on the
queue from the start.

`job_executions.attempt` should increment on every pickup (not just on
failure), so attempt count reflects redelivery visibility even for jobs that
never fail outright.

**Decided (scoped down for this learning project):** no DLQ consumer. A
message that lands in the DLQ just sits there for manual inspection — we're
deliberately not building the small process that would flip the
corresponding `job_executions` row to `failed` automatically. Known
consequence: a DLQ'd execution's DB row stays in whatever non-terminal state
it was last written to (`processing` or `queued`), so `GET /jobs/:id` won't
reflect the true "gave up after N attempts" outcome — acceptable gap here,
would need closing in a real system.

**Decided:** no custom exponential backoff between retries. Redelivery uses
the queue's plain visibility timeout every time (SQS doesn't do backoff
natively) — a failed message just becomes visible again after the same fixed
timeout, repeatedly, until `maxReceiveCount` sends it to the DLQ. Simpler,
accepted for now.

### Other SQS configs to decide on (coming from Kafka)

The biggest mental-model shift: SQS is a **destructive queue**, not a
replayable log. A message is gone once deleted — no offsets, no replay, no
multiple independent consumer groups reading the same stream. There's also
no partition concept on a standard queue — any number of workers can poll
concurrently with no coordination needed, which is simpler than Kafka's
"partition count bounds consumer parallelism."

Settings still worth deciding explicitly:

- **Standard vs. FIFO queue** — standard gives best-effort ordering and
  higher throughput; FIFO adds ordering + built-in dedup per message group
  but caps throughput (much higher with batching). Nothing here needs strict
  ordering across executions, and we're already handling dedup ourselves via
  the status check above — **lean standard**.
- **Visibility timeout** — must exceed worst-case job processing time, or
  SQS will redeliver a message to a second worker while the first is still
  legitimately working on it (a false-positive "crash"). For jobs with
  variable duration, use `ChangeMessageVisibility` to heartbeat/extend the
  timeout mid-processing rather than just setting one large fixed value.
- **Long polling (`ReceiveMessageWaitTimeSeconds`, up to 20s)** — reduces
  empty-response API calls compared to short polling; SQS bills per request,
  so this is a real cost/latency lever, not just a nicety.
- **Batching** — `ReceiveMessage` supports up to 10 messages per call,
  `SendMessageBatch`/`DeleteMessageBatch` for the generator/watcher and
  worker sides respectively. Worth using given SQS's per-request pricing.
- **Message retention period** (default 4 days, max 14) — irrelevant to
  normal flow since messages are deleted on completion, but caps how long an
  undelivered/poison message can linger before SQS drops it regardless of
  the DLQ config.

**Decided:** using AWS SQS rather than a local emulator (ElasticMQ/LocalStack)
— an AWS account was already available. Long polling (`ReceiveMessageWaitTimeSeconds
= 20`) and batching where practical are the main levers to stay comfortably
inside free usage; at this project's scale neither is a real risk.
Encryption left on the default SSE-SQS (Amazon-owned key) — a customer-managed
KMS key would add separate KMS charges outside the SQS free tier.

**Correction / important caveat:** the account in use is on AWS's promotional
**Free Plan** ($120 credit + a hard 33-day window from account creation), not
the classic indefinite pay-as-you-go free tier. SQS's own 1M-requests/month
free tier is permanent under a standard account, but it sits *under* this
time-boxed plan — free AWS access here ends when either the credit runs out
or the 33 days elapse, whichever comes first, unless upgraded to a paid plan
before then. Check Billing → Free Tier → "days remaining" if this project is
still active as that deadline approaches.

Queues created: `job-executions` (Standard, visibility timeout `60s` — the
default `30s` was too tight for send-email + two DB writes) and
`job-executions-dlq` (Standard, retention `14 days`, receive wait time `20s`).
`job-executions` has its dead-letter queue enabled, pointing at
`job-executions-dlq`, with **maxReceiveCount = 3**. No DLQ consumer — see
"Build philosophy" below; a DLQ'd message just sits there for manual
inspection.

## Watcher (publishing due executions)

Runs inside the API service as a Spring `@Scheduled` task (a separate
deployable was considered and deferred: it would duplicate the repositories
and SQS setup for no v1 benefit).

**Timing.** `fixedDelay` of 1 minute (the next run starts 1 minute after the
previous one *finishes*, so runs never overlap) and a 5-minute lookahead.
Rule: **lookahead must be comfortably longer than the interval** (a job
should be seen by several runs before it's due, so one failed run doesn't make
it late), and **at most 15 minutes** (SQS's max `DelaySeconds`). If the
interval is raised to 3–4 minutes, raise the lookahead too (e.g. 10 min). The
lookahead is one shared setting, also used by the `POST` fast path.

**Multiple instances: `FOR UPDATE SKIP LOCKED`.** Every API instance runs a
watcher. Each batch query locks the rows it returns and skips rows locked by
another instance, so concurrent watchers take disjoint rows. Chosen over
ShedLock (only one instance does the work) because every instance can share
the load. Even a "single-instance" watcher needs this: rolling deploys and
failover briefly run two copies.

**One run:**

1. Loop: in one transaction, select up to 10 due rows (`status = 'PENDING'`
   and `scheduled_at <= now() + lookahead`), `ORDER BY scheduled_at`,
   `FOR UPDATE SKIP LOCKED`.
2. Publish them with one `SendMessageBatch` call (10 = SQS's per-call limit,
   so each transaction holds its locks for exactly one network call).
3. Mark the accepted rows `QUEUED`; rejected rows stay `PENDING`; commit.
4. Stop when the query returns fewer than 10 rows (drained), **or** the SQS
   call throws (roll back), **or** half or more of the batch was rejected
   (configurable threshold).

**No `OFFSET` pagination.** Processing a batch moves its rows out of the
result set (they become `QUEUED`), so the next batch is the same query run
again ("draining" a queue). `OFFSET` counts positions, and positions shift as
rows are queued or new rows arrive, so it would skip or repeat rows.

**Failures.** The message body is only `{"jobExecutionId": ...}`, so a
poison message (one that fails every time) can't occur; any per-message
failure is a transient SQS-side hiccup. A rejected row stays `PENDING` and,
being among the earliest by `scheduled_at`, is re-picked by the next batch,
which is an immediate retry. A real network failure makes the whole call
throw (after the SDK's own retries with backoff): roll back and stop the run.
If half or more of a batch is rejected, SQS looks unhealthy: commit the
accepted rows and stop. The 1-minute delay before the next run is the backoff.

**No per-run cap, and the loop always terminates.** With `fixedDelay` a long
run is harmless, and a cap would slow backlog recovery (e.g. after hours of
downtime). Any batch allowed to continue queued more than half its rows, so
every iteration makes progress; a finite backlog ends in a batch of fewer than
10 rows, which stops the loop (rows that keep failing end up in that last
batch and are retried next run). Rough speed: one batch ≈ 20–50 ms, so 100k
rows ≈ 5–8 minutes on one thread. Each query re-evaluates `now()`, so jobs
that become due mid-drain are included.

**Accepted race.** A `POST` fast path and a watcher run can both see a brand-new
row as `PENDING` and due, producing one duplicate message. Rare, and the
worker's atomic claim makes duplicates harmless (at-least-once delivery).

## Build philosophy: happy path first

Failure handling (worker crash mid-processing, redelivery races, retries,
DLQ wiring) is designed and documented above, but **not implemented yet**.
For the first working version: get one-time and recurring jobs flowing
end-to-end without worrying about intermediate-step failures. `jobs` and
`job_executions` already carry the statuses needed to represent failure
states later — that's why they're there — but the recovery logic itself
(a DLQ consumer, requeue sweeps, etc.) is intentionally deferred to a later
pass once there's a working demo, not before. Keep documenting failure
scenarios as they're identified; fix them later, deliberately, not reactively
mid-build.

## Postgres (local dev)

Docker Compose setup lives in [`docker/postgres/`](../docker/postgres/),
providing an empty database only; the schema is created and upgraded by the
app's Flyway migrations (see `docs/schema.md`). See that folder's README for
usage.

