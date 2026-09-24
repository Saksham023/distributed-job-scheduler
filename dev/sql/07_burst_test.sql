-- Burst test: N one-time jobs all due at the SAME instant, to measure how long
-- the watcher takes to publish them and the worker(s) take to drain them.
-- Run with psql, passing the due time (with offset) and the count:
--   psql ... -v due_at='2026-09-24T14:30:00+05:30' -v n=2000 -f 07_burst_test.sql
-- Pick due_at >= 8 min ahead: rows sit PENDING until the watcher's 5-minute
-- lookahead reaches them, so one watcher run publishes all of them.
-- Requires the test user (01) and the active welcome_email template.
-- Measure afterwards with 08_burst_test_results.sql.
WITH new_jobs AS (
    INSERT INTO jobs (user_id, task_type_id, template_id, params, schedule_type)
    SELECT u.id,
           tt.id,
           t.id,
           jsonb_build_object('to', 'test.user@example.com',
                              'first_name', 'Burst ' || g,
                              'load_test', true,
                              'seq', g),
           'ONE_TIME'
    FROM generate_series(1, :n) AS g,
         (SELECT id FROM users WHERE email = 'test.user@example.com') AS u,
         (SELECT id FROM task_types WHERE name = 'welcome_email') AS tt,
         (SELECT id FROM templates WHERE task_type_id = (SELECT id FROM task_types WHERE name = 'welcome_email')
                                     AND is_active) AS t
    RETURNING id
),
schedules AS (
    INSERT INTO one_time_schedules (job_id, scheduled_at)
    SELECT id, :'due_at'::timestamptz FROM new_jobs
)
INSERT INTO job_executions (job_id, scheduled_at)
SELECT id, :'due_at'::timestamptz FROM new_jobs;

SELECT status, scheduled_at AT TIME ZONE 'Asia/Kolkata' AS due_ist, count(*)
FROM job_executions
GROUP BY status, scheduled_at;
