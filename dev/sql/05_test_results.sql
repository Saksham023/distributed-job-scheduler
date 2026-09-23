-- Read-only checks for verifying a test run. Run each statement on its own.

-- 1. Executions by status, with job status and attempts
SELECT je.status        AS execution_status,
       j.status         AS job_status,
       je.attempt,
       count(*)
FROM job_executions je
JOIN jobs j ON j.id = je.job_id
GROUP BY je.status, j.status, je.attempt
ORDER BY je.status, je.attempt;

-- 2. Executions with an error recorded (retries, failures)
SELECT je.id, je.status, je.attempt, je.error_message, je.scheduled_at
FROM job_executions je
WHERE je.error_message IS NOT NULL
ORDER BY je.scheduled_at DESC
LIMIT 50;

-- 3. Anything not finished yet, oldest first (should be empty once a test settles)
SELECT je.id, je.status, je.attempt, je.scheduled_at, now() - je.scheduled_at AS overdue_by
FROM job_executions je
WHERE je.status IN ('PENDING', 'QUEUED', 'PROCESSING')
ORDER BY je.scheduled_at
LIMIT 50;

-- 4. Mismatches between an execution and its one-time job (should be empty)
SELECT je.id, je.status AS execution_status, j.status AS job_status
FROM job_executions je
JOIN jobs j ON j.id = je.job_id
WHERE j.schedule_type = 'ONE_TIME'
  AND (   (je.status = 'COMPLETED' AND j.status <> 'COMPLETED')
       OR (je.status = 'FAILED'    AND j.status <> 'FAILED')
       OR (je.status IN ('PENDING', 'QUEUED', 'PROCESSING') AND j.status <> 'ACTIVE'));

-- Emails are counted in Mailpit, not here: http://localhost:8025, or
--   curl -s "http://localhost:8025/api/v1/messages?limit=1" | grep -o '"total":[0-9]*'
-- Expected: one email per COMPLETED execution. More means duplicates.
