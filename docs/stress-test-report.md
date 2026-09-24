# Stress and chaos test report

**Run:** 2026-09-24, 22:06–22:26 IST, on a laptop (16 GB RAM, 10 cores),
with Postgres and Mailpit in Docker and real Amazon SQS (`ap-south-1`).

**Numbers at a glance** (per worker, per step, per minute, per burst, per
watcher run): [`performance-report.md`](performance-report.md).

**Verdict:** 125,000 emails, 0 failures, 0 duplicate emails and 0 errors,
while workers were killed with `kill -9` every minute. The system recovered
on its own every time.

## 1. What was tested

**Load: 125,000 executions in 10 minutes** (T = 22:14:00), inserted directly
into the tables:

| Part | Count | Pattern |
|---|---|---|
| Steady one-time jobs | 60,000 | random second between T and T+10 min (~100/s) |
| Burst one-time jobs | 40,000 | 10,000 due at the **same second**, at T+2:30, 4:30, 6:30, 8:30 |
| Recurring jobs | 2,500 jobs × 10 runs = 25,000 | every minute from T; the **generator** created all runs |

**Processes** (JARs, test-only `DEBUG` logging on):

| Service | Instances | Heap | DB pool |
|---|---|---|---|
| Worker | 8 at a time (26 processes in total, because of restarts) | 256 MB | 8 |
| Watcher | 3 (4 in total) | 192 MB | 3 |
| Generator | 1 | 192 MB | 3 |

The pools were reduced from the default 10 because 12 services × 10 would
exceed Postgres's limit of 100 connections.

**Chaos:** every minute from T+1 to T+10, two random workers killed with
`kill -9` (a crash, not a clean shutdown) and two new ones started 10 s later.
That's 18 worker kills. One watcher was also killed at 22:17:11 and replaced
at 22:17:41.

**How it was measured:**
- **Database:** `scheduled_at`, `started_at`, `finished_at`, `attempt`.
- **Worker logs**, one line per execution:
  - `claimed`, with lateness
  - `sent`, written *after* the email and *before* `COMPLETED` is saved
  - `completed`, with time per step
  - `not-claimed`, with the status found
- **Watcher logs**, one line per batch: timings and execution ids.
- **Generator logs**, one line per batch.
- **A sampler every 5 s:** status counts, DB connections, JVM memory, SQS
  depth.
- **GC logs** for every JVM, and **a chaos log** of every kill and restart.

## 2. Correctness

| Check | Result |
|---|---|
| Executions `COMPLETED` / `FAILED` | **125,000 / 0** |
| Jobs `COMPLETED` | 100,000 one-time + 2,500 recurring (all) |
| Duplicate emails | **0**: 125,000 `sent` events for 125,000 distinct executions; Mailpit total 125,000 |
| Executions published twice | 0 (125,000 ids across 12,504 watcher batches) |
| Messages rejected by SQS | 0 |
| `ERROR` / `WARN` lines in any log | 0 |
| Queue after the test | empty |

## 3. Timeliness

**Lateness** = `started_at − scheduled_at`: how long after its due time a job
started. Negative means it started early; SQS delays are whole seconds and we
round down, so runs can start up to ~1 s early.

| Kind | Count | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| Steady | 60,000 | −0.3 s | 9.9 s | 14.9 s | 184 s* |
| Recurring | 25,000 | 1.4 s | 3.9 s | 5.2 s | 184 s* |
| Burst | 40,000 | 7.9 s | 16.1 s | 17.6 s | 18.9 s |
| **All** | 125,000 | 0.8 s | 14.3 s | 16.8 s | 184 s* |

p50 / p95 / p99 = half / 95% / 99% of jobs started within this time.
\* Only executions held by a killed worker; see section 5.

**Bursts (10,000 due in the same second):**

| Due | All done after | p50 done | p99 done | Rate |
|---|---|---|---|---|
| 22:16:30 | 18.5 s | 7.9 s | 17.6 s | 514/s |
| 22:18:30 | 18.0 s | 7.9 s | 17.3 s | 530/s |
| 22:20:30 | 19.0 s | 8.2 s | 17.7 s | 502/s |
| 22:22:30 | 18.7 s | 8.0 s | 18.1 s | 509/s |

**Recurring minute bursts (2,500 each):** p99 done within 3.9–5.8 s every
minute.

**Throughput:** peak **640 emails/s** (1 s window), ~600/s (5 s window).
Overall, the first job started at 22:13:59 and the last finished at 22:25:03.

## 4. Each service

**Worker**, time per execution:

| Step | avg | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| Claim (DB update) | 2.2 ms | 1 ms | 6 ms | 10 ms | 403 ms |
| Send email (SMTP to Mailpit) | 48.4 ms | 46 ms | 92 ms | 110 ms | 373 ms |
| Mark completed (DB transaction) | 0.8 ms | 1 ms | 2 ms | 4 ms | 37 ms |

Almost all the time is the email send. At most 69 executions were being
processed at once (the limit is 8 workers × 10).

**Watcher:**
- **Speed:** about **180 executions/s per instance** (3 instances ≈ 540/s).
- **Run length at peak:** 8,000–10,000 executions per run, taking 45–52 s.
- **Per batch of 10:** SQS send 50 ms avg (p99 138 ms), lock 0.5 ms, update
  0.9 ms. The SQS round trip is the whole cost.
- **Headroom:** everything was published about 4 minutes before its due time
  (`PENDING` reached 0 at 22:19:58).

**Generator:**
- **Creating runs:** 25,000 recurring runs in **3.1 s** (25 batches of 100
  schedules, max 411 ms per batch).
- **Completing jobs:** it marked 2,475 recurring jobs `COMPLETED` at 22:23:34,
  and the last 25 within the next 2 minutes, once their retried runs had
  finished. The completion step averages 25 ms.

**Resources at peak:**
- **Memory:** 12 JVMs, **2.6 GB** in total.
- **DB connections:** **78 of 100**.
- **GC:** pauses of at most **29 ms**; 4.9 s in total across all JVMs over
  the whole test.

## 5. Failures and recovery

| Event | Count | What happened |
|---|---|---|
| Executions held (claimed) by a killed worker | 83 | Found `PROCESSING` on redelivery, reset, completed on attempt 2 |
| Messages redelivered for already-completed executions | 110 | A killed worker's batched deletes were lost; each was found `COMPLETED` and deleted |
| Messages fetched but not started by a killed worker | a few | Reappeared after 60 s and ran on attempt 1 (hence attempt-1 max lateness 63 s) |
| Near-duplicate | 1 | See below |

**Recovery time for a claimed execution: ~120 s** (p50 121.8 s), or 184 s when
the second worker was killed too. That's two visibility timeouts, not one;
see section 7.

**The one near-duplicate.** Worker-10 sent an email and committed `COMPLETED`
in the same millisecond it was killed (22:20:02.315), before it could log the
completion. The redelivered message found `COMPLETED`, so no second email was
sent. That's the only point where a duplicate is possible, and it was about
1 ms wide.

## 6. Findings

1. **Self-healing works.** Hard kills of workers and a watcher caused no lost
   jobs, no failures and no duplicate emails.
2. **Crash recovery takes ~120 s** (two visibility timeouts). It can be made
   faster, but that trades against safety for slow-but-alive workers; see
   section 7.
3. **Mailpit, not our code, capped throughput.** Sends took 48 ms under load
   (15 ms when idle), while the DB steps stayed at ~3 ms. So 8 workers reached
   ~510/s rather than ~8 × 125/s. A real email API would give different
   numbers.
4. **Watchers were busy ~45 s of every ~105 s cycle.** Much higher load needs
   more watchers or parallel batches.
5. **Connection budget:** default pools (10 per service) × 12 services would
   exceed Postgres's 100 connections. Size the pools per service, or add
   PgBouncer.
6. **Not covered:** the watcher kill happened *between* runs, so a watcher
   killed mid-batch wasn't exercised here (phase 8 of `test-plan.md` covered
   it earlier).
7. **SQS usage:** roughly 40,000–60,000 requests, a few percent of the monthly
   free tier.

## 7. Why crash recovery takes two visibility timeouts

A worker that receives a message makes it invisible to other workers for 60 s
(the visibility timeout, a lease). The worker rules say an execution found
`PROCESSING` is never run; it's reset to `QUEUED`, and the message is left in
the queue. Timeline when worker A is killed mid-job:

| Time | What happens |
|---|---|
| 0 s | Worker A receives the message (invisible for 60 s), claims the execution (`PROCESSING`), and is killed |
| 60 s | The message becomes visible. Worker B receives it (invisible for another 60 s). The claim fails because the status is `PROCESSING`. B **resets it to `QUEUED`** and doesn't delete the message |
| 120 s | The message becomes visible again. Worker C receives it, the claim succeeds (`QUEUED`), and it runs |

In this test, kills happened every 60 s. So a few times worker B was killed
too, and it took three cycles (184 s).

**Why B doesn't just run it.** B can't tell whether A is dead or only slow,
for example stuck on a slow mail server. If A is alive and B ran the job,
both would send the email. The extra timeout gives a slow A another 60 s to
finish. A's `COMPLETED` is written unconditionally, so worker C would then
find `COMPLETED` and simply delete the message.

**Making it faster** (backlog):
- **Redeliver immediately after the reset** (set the visibility to 0). That
  gives ~60 s recovery, but loses the extra margin for a slow A.
- **Safe version:** combine it with a **heartbeat**, where a working worker
  keeps extending its message's visibility. A worker that stops extending is
  then known to be dead, and the next delivery can take over without waiting.
