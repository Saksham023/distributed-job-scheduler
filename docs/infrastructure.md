# Infrastructure

## Queue: SQS

Chosen over self-hosting a queue mainly for **per-message delay** — the
watcher pushes an execution to SQS with a `DelaySeconds` matching how far out
its `scheduled_at` is (bounded by the watcher's 5-minute lookahead, well
under SQS's 15-minute max delay), so the message only becomes visible to
workers when it's actually due.

## Worker (executing a message)

SQS's visibility timeout does the job a "find stuck executions" cron would
otherwise do: a worker that crashes never deletes its message, so SQS
redelivers it after the timeout. Delivery is **at-least-once**; duplicates
are made rare, not impossible.

Coordination between workers uses only the `job_executions` row and the SQS
visibility timeout, effectively a **lease**: a worker holds an execution for
as long as its message is invisible (60s); not finished by then → presumed
dead. (A Redis lock was designed and rejected: same guarantee, same
"presumed dead after 60s" assumption, but a second source of truth and a new
failure mode. See backlog.)

### What the worker does for each execution status

| Status on receipt | Meaning | Action |
|---|---|---|
| `QUEUED` | Normal case, or waiting for a retry after a transient failure | Execute |
| `PENDING` | Publisher crashed after sending, before marking `QUEUED` | Execute |
| `PROCESSING` | Another worker has it right now, **or** its worker crashed; can't tell which | Reset to `QUEUED`, leave the message (don't delete) |
| `COMPLETED` / `FAILED` / `CANCELLED` | Terminal; this message is a leftover | Delete the message, do nothing |

`PROCESSING` is never executed directly. Resetting it and leaving the message
answers "is someone working on this?" by waiting and looking again: the
message reappears after the visibility timeout, and by then either the other
worker finished (row `COMPLETED` → message deleted) or it crashed (row still
`QUEUED` → executed).

A `PENDING` row does **not** guarantee a second message: if a worker claims it
before the watcher's next run, the watcher never republishes it. Nothing may
depend on a second message arriving.

### The status updates

**Claim**: one atomic conditional update (never read-then-write, which would
let two workers both pass the check), committed in its own short transaction:

```sql
UPDATE job_executions
SET status = 'PROCESSING', started_at = now(), attempt = attempt + 1
WHERE id = :id AND status IN ('PENDING', 'QUEUED')
RETURNING ...
```

An **allow-list** (`IN` the claimable statuses), not a deny-list (`NOT IN`
the terminal ones): if a status is added later (e.g. `PAUSED`) and this query
isn't updated, the job is safely *not* executed (visibly stuck, fixable)
instead of wrongly executed (an email that can't be unsent). If no row is
returned, a follow-up read decides between resetting (`PROCESSING`) and
deleting the message (terminal or unknown). `attempt` counts claims.

**Rule: the update that records success is unconditional; every other update
must never overwrite a success.**

| Update | Condition | Why |
|---|---|---|
| Complete → `COMPLETED` | none (`WHERE id = :id`) | The email was sent, so `COMPLETED` is the truth whatever the row said (including after another worker reset it to `QUEUED`) |
| Reset `PROCESSING` → `QUEUED` | `status = 'PROCESSING'` (compare-and-set) | Must not turn a `COMPLETED` row back into `QUEUED` |
| Transient failure → `QUEUED` | `status IN ('PROCESSING', 'QUEUED')` | Allow-list: never overwrites `COMPLETED` (another worker's success), `FAILED` or `CANCELLED` |
| Permanent / last-attempt failure → `FAILED` | `status IN ('PROCESSING', 'QUEUED')`; the one-time job only `WHERE status = 'ACTIVE'` | Same |

### Receiving messages: `@SqsListener` (Spring Cloud AWS 4.x)

The SQS counterpart of `@KafkaListener`; chosen over a hand-written polling
loop. Spring Cloud AWS 4.x targets Spring Boot 4 and handles long polling,
batching, concurrency, back-pressure and JSON → `JobExecutionMessage`
conversion.

- **Manual acknowledgement mode is required.** The default mode deletes the
  message whenever the listener method returns normally, which would break
  the "leave the message" cases (`PROCESSING` reset, transient failure, last
  attempt). In manual mode the method gets an `Acknowledgement`: calling
  `acknowledge()` deletes the message; not calling it leaves it to reappear
  after the visibility timeout (like Spring Kafka's `AckMode.MANUAL`).
- `ApproximateReceiveCount` is read from the message headers.
- It uses its own `SqsAsyncClient` (configured via `spring.cloud.aws.*`); our
  `SqsClient` in `SqsConfig` stays for publishing (API, watcher).
- The listener must run only in the worker process: disabled for the API and
  watcher roles by configuration.

### Steps (ack last)

1. **Receive** the message, with its `ApproximateReceiveCount`.
2. **Claim and load** in one statement (`UPDATE … FROM jobs JOIN templates …
   RETURNING`): the claim also returns `params` and the **pinned** template
   (by `jobs.template_id`, not the currently active one). Not claimed → read
   the status: `PROCESSING` → reset to `QUEUED` and leave the message;
   terminal/unknown → delete the message. Stop.
3. (merged into 2)
4. **Execute**: render subject and body with Mustache (strict; see
   `schema.md` → "Templates and `params` validation"), send via `EmailSender`.
   No DB transaction is held during this external call.
5. **Complete**, in one transaction: execution `COMPLETED` + `finished_at`;
   the one-time job `COMPLETED`.
6. **Delete the message** (ack), only after step 5 commits.

| Crash point | Result |
|---|---|
| Before the claim commits | Redelivered, executed normally |
| After claim, before the email is sent | Redelivered → sees `PROCESSING` → reset → redelivered again → executed (one extra minute) |
| After the email is sent, before step 5 | Eventually executed again → **duplicate email** (accepted at-least-once cost) |
| After step 5, before the message is deleted | Redelivered → `COMPLETED` → message deleted. No duplicate |

### Failures

- **Permanent** (retrying can't help: template missing, `params` lacks a
  recipient, invalid address, a placeholder with no value in `params` (strict
  Mustache rendering)) → execution `FAILED` with `error_message`, one-time job
  `FAILED`, delete the message.
- **Transient** (mail server unreachable, network timeout, DB briefly down) →
  execution back to `QUEUED` with `error_message`; don't delete the message.
  SQS redelivers it after the visibility timeout. `QUEUED` + an error reads
  "failed once, waiting for retry".
- **Transient on the last attempt** (`ApproximateReceiveCount` ≥
  `app.worker.max-receive-count`) → execution and one-time job `FAILED`;
  don't delete the message, so SQS moves it to the DLQ for human inspection.

**`maxReceiveCount = 5`** (redrive policy; `app.worker.max-receive-count`
must match). Raised from 3 because a delivery that only resets `PROCESSING`
still counts as a receive: with 3, one crash plus one transient failure would
send a healthy job to `FAILED`.

`jobs.status` gains `FAILED` (migration + `JobStatus` enum): for a one-time
job it mirrors its single execution's terminal outcome. (For recurring jobs,
one failed execution won't fail the job.)

No exponential backoff between retries: redelivery uses the plain visibility
timeout each time (SQS doesn't back off natively). Accepted for now.

Jobs that could exceed 60s would need a heartbeat extending the visibility
timeout (`ChangeMessageVisibility`): deferred (see backlog).

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
`job-executions-dlq`, with **maxReceiveCount = 5** (raised from 3; see "Worker" → Failures). No DLQ consumer — see
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

## Email

The worker depends on an `EmailSender` interface; one implementation is
created per environment via `app.email.provider` (`@ConditionalOnProperty`):

- **Local: `smtp` → Mailpit** ([`docker/mailpit/`](../docker/mailpit/)), a fake
  SMTP server that catches every email (inbox at http://localhost:8025).
  Chosen over writing emails to files because the app runs the same SMTP
  sending code locally as against a real server.
- **Production: `ses`** (AWS SES), added later. UAT options (hosted catcher
  such as Mailtrap, SES left in sandbox, a recipient allow-list decorator,
  anonymized data) are noted but out of scope.

No default provider (`matchIfMissing` not used): a missing or misspelled
`app.email.provider` fails startup instead of silently sending nothing.

## Postgres (local dev)

Docker Compose setup lives in [`docker/postgres/`](../docker/postgres/),
providing an empty database only; the schema is created and upgraded by the
app's Flyway migrations (see `docs/schema.md`). See that folder's README for
usage.

