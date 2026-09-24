# Performance report: stress and chaos test

**Machine:** laptop, 16 GB RAM, 10 CPU cores. Postgres 18 and Mailpit in Docker; Amazon SQS in `ap-south-1` over home internet.  
**Run:** 2026-09-24, load due 22:14:00–22:24:00 IST. Chaos: every minute from 22:15 to 22:23, 2 random workers killed with `kill -9` (a crash, not a clean shutdown) and 2 new ones started 10 s later (18 kills); 1 watcher killed at 22:17:11 and replaced at 22:17:41.  
**Related:** [`stress-test-report.md`](stress-test-report.md) has the test design and the findings; this page has the numbers.  
**Sources:** service logs (a line per execution, batch and run), a sampler every 5 s, GC logs, and database queries made before the tables were reset.  
**Terms:** *lag* = how late a job started after its due time (negative = up to 1 s early, because SQS delays are whole seconds). p50/p95/p99 = 50% / 95% / 99% of values are at or below.

## 1. Summary

| Metric | Value |
| --- | --- |
| Executions | 125,000 (100,000 one-time + 25,000 from 2,500 recurring jobs) |
| Completed / failed | 125,000 / 0 |
| Duplicate emails | 0 |
| Errors / warnings in logs | 0 |
| Workers running at once | 8 (6 for ~10 s after each kill); 26 worker processes in total |
| Watchers / generators | 3 (4 processes in total) / 1 |
| First job started → last finished | 22:13:59.384 → 22:25:03.036 (664 s) |
| Average throughput | 188 emails/s over the whole run |
| Peak throughput (1 s) | 642 emails/s, all workers together |
| 10,000-job burst | ~580/s for 18–19 s; last job started ≤ 18.9 s late; p50 finished 8 s after the due second |
| 2,500-job recurring minute | p99 finished 3.9–5.8 s after the minute mark |
| Per worker, at full load | ~72 emails/s (peak 109/s in 1 s) |
| Worker time per email | 51 ms avg (claim 2.2 · send 48.4 · complete 0.8) |
| Watcher publishing rate | ~185/s per instance; 3 instances ≈ 550/s |
| Generator | 25,000 runs created in 3.1 s |
| Crash recovery (claimed job) | p50 122 s; 83 jobs; 0 lost |
| Lag, all executions | p50 0.8 s · p95 14.3 s · p99 16.8 s · max 184 s |
| Peak memory / DB connections | 2.6 GB for 12 JVMs / 78 of 100 |

## 2. Worker: time per step (all 125,000 executions)

Steps: **1. claim**: the `UPDATE` that marks the execution `PROCESSING` and returns its template (receiving from SQS isn't timed separately); **2. send** the email (SMTP to Mailpit); **3. complete**: the `COMPLETED` transaction.

| Step | avg (ms) | p50 | p95 | p99 | max |
| --- | --- | --- | --- | --- | --- |
| 1. Claim (DB) | 2.2 | 1 | 6 | 10 | 403 |
| 2. Send email | 48.4 | 46 | 92 | 110 | 373 |
| 3. Complete (DB) | 0.8 | 1 | 2 | 4 | 37 |
| **Total (1+2+3)** | 51.4 | 49 | 95 | 116 | 728 |

Share of the total time: claim 4.3 %, send 94.1 %, complete 1.6 %.

## 3. Each worker

*Avg rate* = completions ÷ seconds between its first and last completion (includes quiet periods). *Full-load rate* = completions per second in the first 15 s of each 10,000 burst the worker was alive for. Step times in ms (avg / p95).

| Worker | Started | Ended | Lifetime (s) | Completed | Avg rate (/s) | Full-load rate (/s) | Peak (/s, 1 s) | Claim avg/p95 | Send avg/p95 | Complete avg/p95 | Lag p50 / max (s) | Attempt 2 | Held at kill* |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| worker-1 | 22:06:32 | killed 22:18:01 | 690 | 6,031 | 25 | 73 | 101 | 2.3 / 6 | 45.4 / 93 | 0.8 / 2 | 0.1 / 18.0 | 0 | 0 |
| worker-2 | 22:06:32 | killed 22:16:01 | 569 | 2,351 | 19 | – | 99 | 3.0 / 7 | 45.0 / 107 | 0.9 / 2 | -0.1 / 5.2 | 0 | 0 |
| worker-3 | 22:06:32 | killed 22:15:01 | 509 | 1,172 | 19 | – | 61 | 5.8 / 8 | 54.2 / 112 | 1.1 / 2 | -0.1 / 5.9 | 0 | 10 |
| worker-4 | 22:06:32 | killed 22:17:01 | 629 | 4,797 | 26 | 71 | 90 | 2.7 / 6 | 49.6 / 98 | 0.9 / 2 | 0.7 / 61.4 | 0 | 5 |
| worker-5 | 22:06:32 | killed 22:23:02 | 991 | 16,109 | 30 | 73 | 107 | 1.9 / 6 | 46.5 / 92 | 0.8 / 2 | 1.0 / 122.4 | 4 | 0 |
| worker-6 | 22:06:32 | killed 22:15:01 | 509 | 1,180 | 19 | – | 70 | 5.1 / 9 | 52.0 / 120 | 1.1 / 2 | -0.1 / 5.6 | 0 | 10 |
| worker-7 | 22:06:32 | killed 22:19:02 | 749 | 8,429 | 28 | 75 | 100 | 2.3 / 6 | 47.2 / 90 | 0.8 / 2 | 1.0 / 61.7 | 0 | 7 |
| worker-8 | 22:06:32 | killed 22:19:01 | 749 | 8,624 | 29 | 73 | 106 | 2.2 / 6 | 46.7 / 89 | 0.8 / 2 | 0.8 / 17.8 | 0 | 0 |
| worker-9 | 22:15:12 | killed 22:16:01 | 49 | 660 | 14 | – | 70 | 4.6 / 8 | 41.0 / 83 | 1.3 / 2 | -0.4 / 1.1 | 0 | 10 |
| worker-10 | 22:15:12 | killed 22:20:02 | 290 | 7,960 | 28 | 76 | 100 | 2.1 / 6 | 46.2 / 86 | 0.8 / 2 | 0.9 / 121.8 | 18 | 5 |
| worker-11 | 22:16:12 | killed 22:17:01 | 49 | 1,890 | 40 | 72 | 80 | 2.1 / 5 | 61.4 / 92 | 0.9 / 2 | 5.5 / 121.2 | 10 | 0 |
| worker-12 | 22:16:12 | stopped 22:25:55 | 583 | 13,754 | 29 | 73 | 107 | 1.9 / 6 | 47.6 / 90 | 0.8 / 2 | 1.4 / 184.2 | 19 | 0 |
| worker-13 | 22:17:12 | killed 22:20:02 | 169 | 4,247 | 25 | 74 | 109 | 2.2 / 6 | 46.8 / 86 | 0.8 / 2 | 0.4 / 184.2 | 1 | 0 |
| worker-14 | 22:17:12 | killed 22:18:01 | 49 | 712 | 15 | – | 74 | 2.9 / 7 | 41.0 / 82 | 1.0 / 2 | -0.2 / 1.7 | 0 | 5 |
| worker-15 | 22:18:13 | stopped 22:25:55 | 462 | 10,119 | 25 | 73 | 94 | 2.0 / 6 | 48.5 / 91 | 0.8 / 2 | 1.3 / 123.0 | 1 | 0 |
| worker-16 | 22:18:13 | killed 22:21:02 | 169 | 5,590 | 33 | 70 | 99 | 2.0 / 5 | 54.5 / 95 | 0.8 / 2 | 2.3 / 18.5 | 0 | 4 |
| worker-17 | 22:19:13 | stopped 22:25:55 | 402 | 7,732 | 27 | 71 | 103 | 2.1 / 6 | 47.2 / 93 | 0.8 / 2 | 0.7 / 122.9 | 15 | 0 |
| worker-18 | 22:19:13 | killed 22:22:02 | 169 | 4,322 | 26 | 69 | 100 | 2.1 / 6 | 49.3 / 96 | 0.8 / 2 | 0.6 / 62.1 | 0 | 0 |
| worker-19 | 22:20:13 | killed 22:21:02 | 49 | 1,988 | 42 | 67 | 80 | 2.1 / 5 | 65.8 / 102 | 0.9 / 2 | 5.0 / 17.6 | 0 | 0 |
| worker-20 | 22:20:13 | killed 22:22:02 | 109 | 3,171 | 29 | 67 | 90 | 2.0 / 5 | 53.9 / 101 | 0.9 / 2 | 1.4 / 122.3 | 4 | 10 |
| worker-21 | 22:21:13 | stopped 22:25:55 | 282 | 4,123 | 18 | 72 | 100 | 2.0 / 6 | 45.5 / 87 | 0.8 / 2 | 0.1 / 123.0 | 1 | 0 |
| worker-22 | 22:21:13 | stopped 22:25:55 | 282 | 4,069 | 18 | 73 | 92 | 2.0 / 6 | 45.6 / 87 | 0.8 / 2 | 0.3 / 123.0 | 2 | 0 |
| worker-23 | 22:22:13 | stopped 22:25:55 | 221 | 2,832 | 17 | 71 | 97 | 2.2 / 6 | 52.0 / 88 | 0.9 / 2 | 1.7 / 123.0 | 4 | 0 |
| worker-24 | 22:22:14 | killed 22:23:02 | 49 | 2,049 | 43 | 72 | 82 | 2.2 / 5 | 61.6 / 90 | 0.9 / 2 | 4.4 / 18.2 | 0 | 10 |
| worker-25 | 22:23:14 | stopped 22:25:55 | 161 | 540 | 5 | – | 27 | 4.6 / 10 | 37.4 / 71 | 1.2 / 2 | -0.4 / 123.0 | 1 | 0 |
| worker-26 | 22:23:14 | stopped 22:25:55 | 161 | 548 | 5 | – | 32 | 4.1 / 10 | 35.7 / 67 | 1.2 / 2 | -0.4 / 123.0 | 3 | 0 |

\* Executions the worker had claimed but not completed when it was killed; each was completed later on attempt 2.

| Per worker | Value |
| --- | --- |
| Full-load rate | avg 72/s, min 67/s, max 77/s (32 worker-bursts) |
| Peak 1-second rate | avg 87/s, max 109/s |
| Most completed by one worker | 16,109 (worker-5, alive 22:06–22:23) |
| Concurrency | 10 messages at a time per worker (Spring Cloud AWS default) |

## 4. Minute by minute (all workers)

| Minute | Completed | Avg (/s) | Peak (/s) | Lag p50 (s) | Lag p95 (s) | Lag max (s) | Workers alive | Kills |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 22:13 | 126 | 2 | 126 | -0.5 | -0.3 | -0.2 | 8–8 | 0 |
| 22:14 | 8,891 | 148 | 509 | -0.2 | 4.4 | 6.1 | 8–8 | 0 |
| 22:15 | 8,359 | 139 | 555 | -0.2 | 3.3 | 4.5 | 6–8 | 2 |
| 22:16 | 18,459 | 308 | 608 | 3.4 | 15.1 | 18.4 | 6–8 | 2 |
| 22:17 | 8,474 | 141 | 603 | -0.2 | 3.0 | 121.2 | 6–9 | 3 |
| 22:18 | 18,544 | 309 | 624 | 3.4 | 15.0 | 121.5 | 6–8 | 2 |
| 22:19 | 8,489 | 141 | 593 | -0.2 | 3.1 | 184.2 | 6–8 | 2 |
| 22:20 | 18,612 | 310 | 601 | 3.6 | 16.0 | 121.8 | 6–8 | 2 |
| 22:21 | 8,505 | 142 | 608 | -0.2 | 2.9 | 122.2 | 6–8 | 2 |
| 22:22 | 18,781 | 313 | 628 | 3.3 | 15.0 | 122.3 | 6–8 | 2 |
| 22:23 | 7,729 | 129 | 642 | -0.2 | 2.7 | 122.4 | 6–8 | 2 |
| 22:24 | 20 | 0 | 20 | 122.9 | 122.9 | 122.9 | 8–8 | 0 |
| 22:25 | 10 | 0 | 10 | 123.0 | 123.0 | 123.0 | 8–8 | 0 |

## 5. Bursts

**One-time bursts (10,000 due at the same second).** *All done* and *p99 done* come from the database; the rate columns come from the logs, over the 20 s after the due second.

| Due | Jobs | All done after (s) | p50 done (s) | p99 done (s) | Avg rate (/s) | Peak rate (/s) | Lag at peak second, max (s) | Max lag in window (s) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 22:16:30 | 10,000 (+~2,000 steady) | 18.49 | 7.87 | 17.62 | 577 | 608 | 12.0 | 18.4 |
| 22:18:30 | 10,000 (+~2,000 steady) | 17.98 | 7.92 | 17.28 | 585 | 624 | 11.9 | 17.9 |
| 22:20:30 | 10,000 (+~2,000 steady) | 18.98 | 8.24 | 17.72 | 554 | 601 | 1.9 | 18.9 |
| 22:22:30 | 10,000 (+~2,000 steady) | 18.71 | 8.01 | 18.06 | 581 | 614 | 14.9 | 18.6 |

**Recurring bursts (2,500 due at each minute mark).** From the database; *all done* includes runs retried after a kill, *p99 done* excludes nothing.

| Minute | Runs | p50 done (s) | p99 done (s) | All done (s) | Peak rate in first 10 s (/s) |
| --- | --- | --- | --- | --- | --- |
| 22:14 | 2,500 | 2.66 | 5.84 | 6.22 | 509 |
| 22:15 | 2,500 | 1.51 | 4.49 | 121.28 | 555 |
| 22:16 | 2,500 | 1.54 | 4.59 | 184.27 | 574 |
| 22:17 | 2,500 | 1.44 | 4.02 | 121.62 | 603 |
| 22:18 | 2,500 | 1.41 | 4.08 | 121.9 | 578 |
| 22:19 | 2,500 | 1.43 | 4.19 | 122.24 | 593 |
| 22:20 | 2,500 | 1.37 | 4.43 | 122.37 | 597 |
| 22:21 | 2,500 | 1.41 | 4.16 | 122.51 | 608 |
| 22:22 | 2,500 | 1.34 | 4.3 | 122.94 | 628 |
| 22:23 | 2,500 | 1.08 | 3.89 | 123.03 | 642 |

**Top 10 seconds by throughput:**

| Second | Completed | Lag of those jobs, avg / max (s) |
| --- | --- | --- |
| 22:23:00 | 642 | 0.4 / 0.9 |
| 22:22:01 | 628 | 1.4 / 1.9 |
| 22:18:41 | 624 | 11.4 / 11.9 |
| 22:22:00 | 617 | 0.4 / 0.9 |
| 22:18:46 | 615 | 12.7 / 16.9 |
| 22:22:44 | 614 | 14.3 / 14.9 |
| 22:18:47 | 612 | 11.8 / 17.9 |
| 22:18:44 | 611 | 14.4 / 14.9 |
| 22:16:41 | 608 | 11.4 / 12.0 |
| 22:21:02 | 608 | 4.7 / 122.2 |

## 6. Lag by job type (database)

| Type | Executions | p50 (s) | p95 (s) | p99 (s) | max (s) |
| --- | --- | --- | --- | --- | --- |
| Steady one-time | 60,000 | -0.26 | 9.93 | 14.89 | 184.22 |
| Burst one-time | 40,000 | 7.94 | 16.05 | 17.61 | 18.93 |
| Recurring | 25,000 | 1.42 | 3.91 | 5.23 | 184.23 |
| **All** | 125,000 | 0.83 | 14.32 | 16.84 | 184.23 |

| Attempt | Executions | Lag p50 (s) | Lag max (s) |
| --- | --- | --- | --- |
| 1 | 124,917 | 0.8 | 62.9 |
| 2 | 83 | 121.8 | 184.2 |

## 7. Watcher

**Per instance** (a batch = up to 10 executions: lock rows → one `SendMessageBatch` → mark `QUEUED`):

| Watcher | Ended | Runs | Published | Batches | Rate (/s) | Lock avg/p95 (ms) | SQS send avg/p95/max (ms) | Update avg/p95 (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| watcher-1 | killed 22:17:11 | 5 | 31,419 | 3,142 | 182 | 0.5 / 1 | 50.7 / 120 / 389 | 1.0 / 2 |
| watcher-2 | stopped | 7 | 42,970 | 4,299 | 185 | 0.5 / 1 | 49.9 / 119 / 420 | 0.9 / 2 |
| watcher-3 | stopped | 7 | 42,852 | 4,287 | 184 | 0.5 / 1 | 50.0 / 119 / 428 | 0.9 / 2 |
| watcher-4 | stopped | 2 | 7,759 | 776 | 184 | 0.4 / 1 | 50.7 / 122 / 391 | 0.5 / 2 |

**All batches:**

| Step | avg (ms) | p50 | p95 | p99 | max |
| --- | --- | --- | --- | --- | --- |
| Lock rows (SELECT … SKIP LOCKED) | 0.5 | 0 | 1 | 2 | 18 |
| SQS SendMessageBatch | 50.2 | 40 | 119 | 138 | 428 |
| Mark QUEUED | 0.9 | 1 | 2 | 3 | 19 |

Total: 12,504 batches, 125,000 executions published, 0 rejected by SQS, 0 published twice. All executions were `QUEUED` by 22:19:58, 4 min before the last due time.

**Every run** (runs start every minute after the previous one ends):

| Watcher | Finished | Published | Batches | Duration (s) | Rate (/s) |
| --- | --- | --- | --- | --- | --- |
| watcher-1 | 22:09:45 | 2,440 | 245 | 13.9 | 175 |
| watcher-2 | 22:09:45 | 2,432 | 244 | 13.8 | 176 |
| watcher-3 | 22:09:45 | 2,270 | 228 | 13.6 | 167 |
| watcher-1 | 22:11:10 | 4,480 | 449 | 24.9 | 180 |
| watcher-2 | 22:11:10 | 4,420 | 443 | 24.8 | 178 |
| watcher-3 | 22:11:10 | 4,471 | 448 | 24.9 | 180 |
| watcher-3 | 22:12:53 | 7,674 | 768 | 42.9 | 179 |
| watcher-1 | 22:12:53 | 7,510 | 752 | 43.0 | 175 |
| watcher-2 | 22:12:53 | 7,530 | 754 | 43.0 | 175 |
| watcher-2 | 22:14:39 | 8,640 | 865 | 46.1 | 187 |
| watcher-1 | 22:14:39 | 8,400 | 841 | 46.1 | 182 |
| watcher-3 | 22:14:39 | 8,618 | 862 | 46.1 | 187 |
| watcher-1 | 22:16:25 | 8,589 | 859 | 45.1 | 190 |
| watcher-3 | 22:16:25 | 8,510 | 852 | 45.3 | 188 |
| watcher-2 | 22:16:25 | 8,603 | 861 | 45.4 | 189 |
| watcher-3 | 22:18:16 | 9,880 | 989 | 51.7 | 191 |
| watcher-4 | 22:18:17 | 6,399 | 640 | 34.6 | 185 |
| watcher-2 | 22:18:17 | 10,045 | 1005 | 52.2 | 192 |
| watcher-2 | 22:19:24 | 1,300 | 131 | 7.1 | 183 |
| watcher-4 | 22:19:24 | 1,360 | 137 | 7.6 | 179 |
| watcher-3 | 22:19:24 | 1,429 | 143 | 7.7 | 186 |

## 8. Generator

| Finished | Schedules topped up | Executions created | Jobs completed | Duration (ms) |
| --- | --- | --- | --- | --- |
| 22:06:34 | 2500 | 25,000 | 0 | 3061 |
| 22:23:34 | 0 | 0 | 2,475 | 54 |
| 22:24:34 | 0 | 0 | 16 | 27 |
| 22:25:34 | 0 | 0 | 9 | 24 |

| Step | Count | avg (ms) | p50 | p95 | p99 | max |
| --- | --- | --- | --- | --- | --- | --- |
| Top-up batch (100 schedules, 1,000 rows) | 25 | 107.8 | 58 | 321 | 411 | 411 |
| Completion step (one UPDATE) | 20 | 24.8 | 26 | 44 | 57 | 57 |

## 9. Kills and recovery

| Time | Killed | Completed before kill | Claimed, not completed (→ attempt 2) | Sent, not logged completed |
| --- | --- | --- | --- | --- |
| 22:15:01 | worker-6 | 1,180 | 10 | 0 |
| 22:15:01 | worker-3 | 1,172 | 10 | 0 |
| 22:16:01 | worker-2 | 2,351 | 0 | 0 |
| 22:16:01 | worker-9 | 660 | 10 | 0 |
| 22:17:01 | worker-4 | 4,797 | 5 | 0 |
| 22:17:01 | worker-11 | 1,890 | 0 | 0 |
| 22:17:11 | watcher-1 | – | – | – (killed between runs: nothing in flight) |
| 22:18:01 | worker-1 | 6,031 | 0 | 0 |
| 22:18:01 | worker-14 | 712 | 5 | 0 |
| 22:19:01 | worker-8 | 8,624 | 0 | 0 |
| 22:19:02 | worker-7 | 8,429 | 7 | 0 |
| 22:20:02 | worker-13 | 4,247 | 0 | 0 |
| 22:20:02 | worker-10 | 7,960 | 5 | 1 |
| 22:21:02 | worker-19 | 1,988 | 0 | 0 |
| 22:21:02 | worker-16 | 5,590 | 4 | 0 |
| 22:22:02 | worker-18 | 4,322 | 0 | 0 |
| 22:22:02 | worker-20 | 3,171 | 10 | 0 |
| 22:23:02 | worker-5 | 16,109 | 0 | 0 |
| 22:23:02 | worker-24 | 2,049 | 10 | 0 |

Redelivered messages found `PROCESSING` → reset (then run on attempt 2): 83. Redelivered messages found `COMPLETED` → deleted (their delete was lost with the killed worker): 110. Recovery of a claimed execution: p50 121.8 s (two visibility timeouts; 184 s when the second worker was killed too).

## 10. Resources

| Metric | Peak | Notes |
| --- | --- | --- |
| JVM memory, all 12 processes (RSS) | 2,606 MB | workers -Xmx256m, others -Xmx192m |
| DB connections | 78 of 100 | pools: workers 8, watchers 3, generator 3 |
| Executions QUEUED (published, not yet due or running) | 72,568 |  |
| Executions PROCESSING at once | 69 | limit 80 (8 workers × 10) |
| SQS messages visible (waiting for a worker) | 9,589 | sampled every 30 s |
| GC pauses, all JVMs | max 28.9 ms | 3,560 pauses, p50 0.9 ms, p99 12.0 ms, 4.9 s total over the run |
| GC pauses, workers | max 20.5 ms | 3,384 pauses, total 4.5 s over 26 processes |

**Sampled every minute:**

| Time | Pending | Queued | Processing | Completed | DB conns | JVM MB | Workers | SQS visible |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 22:06:28 | 100,000 | 0 | 0 | 0 | 1 | 971 | 8 | 0 |
| 22:07:00 | 125,000 | 0 | 0 | 0 | 13 | 2155 | 8 | 0 |
| 22:08:03 | 125,000 | 0 | 0 | 0 | 13 | 2166 | 8 | 0 |
| 22:09:00 | 125,000 | 0 | 0 | 0 | 13 | 2171 | 8 | 0 |
| 22:10:03 | 117,858 | 7,142 | 0 | 0 | 13 | 2255 | 8 | 0 |
| 22:11:00 | 110,128 | 14,872 | 0 | 0 | 13 | 2284 | 8 | 0 |
| 22:12:03 | 104,487 | 20,513 | 0 | 0 | 13 | 2292 | 8 | 0 |
| 22:13:01 | 81,773 | 43,227 | 0 | 0 | 13 | 2298 | 8 | 0 |
| 22:14:03 | 76,453 | 46,577 | 55 | 1,915 | 77 | 2606 | 8 | 0 |
| 22:15:01 | 56,115 | 59,133 | 43 | 9,709 | 61 | 1962 | 6 | 0 |
| 22:16:03 | 42,465 | 63,081 | 47 | 19,407 | 61 | 1900 | 6 | 0 |
| 22:17:01 | 30,413 | 57,920 | 20 | 36,647 | 77 | 2419 | 9 | 0 |
| 22:18:03 | 11,783 | 66,739 | 50 | 46,428 | 61 | 2091 | 6 | 0 |
| 22:19:01 | 4,089 | 57,502 | 13 | 63,396 | 77 | 2544 | 8 | 1417 |
| 22:20:04 | 0 | 51,511 | 4 | 73,485 | 61 | 1875 | 6 | 0 |
| 22:21:01 | 0 | 33,965 | 57 | 90,978 | 77 | 2259 | 8 | 6784 |
| 22:22:04 | 0 | 23,996 | 34 | 100,970 | 61 | 1663 | 6 | 0 |
| 22:23:01 | 0 | 6,577 | 39 | 118,384 | 77 | 2093 | 8 | 4065 |
| 22:24:04 | 0 | 10 | 0 | 124,990 | 77 | 2067 | 8 | 6 |
| 22:25:02 | 0 | 10 | 0 | 124,990 | 78 | 403 | 8 | 0 |

