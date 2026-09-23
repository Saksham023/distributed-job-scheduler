-- Read-only checks. Run each statement on its own.

-- Applied Flyway migrations
SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;

-- Seed data
SELECT id, name, service FROM task_types ORDER BY id;
SELECT id, email, name FROM users ORDER BY id;

-- Most recent jobs with their executions
SELECT j.id          AS job_id,
       tt.name       AS task_type,
       j.status      AS job_status,
       je.id         AS execution_id,
       je.scheduled_at,
       je.status     AS execution_status,
       je.attempt,
       j.created_at
FROM jobs j
JOIN task_types tt ON tt.id = j.task_type_id
LEFT JOIN job_executions je ON je.job_id = j.id
ORDER BY j.created_at DESC
LIMIT 20;
