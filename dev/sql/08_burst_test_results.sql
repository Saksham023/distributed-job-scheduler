-- Results of a burst test (07): drain time and lateness. Read-only.
-- Times shown in IST.

-- 1. Status breakdown (expect all COMPLETED, attempt 1)
SELECT status, attempt, count(*)
FROM job_executions
GROUP BY status, attempt
ORDER BY status, attempt;

-- 2. Drain: first start, last finish, total time from the due instant
SELECT count(*)                                              AS executions,
       min(scheduled_at) AT TIME ZONE 'Asia/Kolkata'         AS due_ist,
       min(started_at)   AT TIME ZONE 'Asia/Kolkata'         AS first_start_ist,
       max(finished_at)  AT TIME ZONE 'Asia/Kolkata'         AS last_finish_ist,
       max(finished_at) - min(started_at)                    AS drain_time,
       max(finished_at) - min(scheduled_at)                  AS due_to_last_finish,
       round(count(*) / extract(epoch FROM max(finished_at) - min(started_at)), 1) AS per_second
FROM job_executions
WHERE status = 'COMPLETED';

-- 3. Lateness of each start (started_at - scheduled_at); negative = early
SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY started_at - scheduled_at) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY started_at - scheduled_at) AS p95,
       max(started_at - scheduled_at)                                          AS max,
       min(started_at - scheduled_at)                                          AS min,
       avg(finished_at - started_at)                                           AS avg_processing
FROM job_executions
WHERE status = 'COMPLETED';

-- 4. Throughput curve: completions per 5-second bucket
SELECT to_timestamp(floor(extract(epoch FROM finished_at) / 5) * 5) AT TIME ZONE 'Asia/Kolkata' AS bucket_ist,
       count(*)
FROM job_executions
WHERE status = 'COMPLETED'
GROUP BY 1
ORDER BY 1;
