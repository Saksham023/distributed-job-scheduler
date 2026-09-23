-- Removes everything created by 03_load_test_pending_executions.sql.
-- Children first: job_executions and one_time_schedules reference jobs.
-- Purge the SQS queue separately (console: job-executions -> Purge).
DELETE FROM job_executions
WHERE job_id IN (SELECT id FROM jobs WHERE params ->> 'load_test' = 'true');

DELETE FROM one_time_schedules
WHERE job_id IN (SELECT id FROM jobs WHERE params ->> 'load_test' = 'true');

DELETE FROM jobs
WHERE params ->> 'load_test' = 'true';
