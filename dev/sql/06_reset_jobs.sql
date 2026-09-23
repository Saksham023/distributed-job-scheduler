-- Empties all job data for a clean test run. Keeps reference data: task types,
-- templates, users. Local only. Also purge the SQS queue (console: Purge) and
-- clear Mailpit (web UI: Delete all) so every count starts from zero.
TRUNCATE job_executions, one_time_schedules, recurring_schedules, jobs;
