-- Creates 200 one-time jobs whose executions are PENDING and due within the
-- next 30 s to 4 min, i.e. inside the watcher's 5-minute lookahead.
-- Bypasses the API, so the fast path never publishes them: only the watcher will.
-- Every row is tagged with params.load_test = true (see 04_cleanup_load_test.sql).
-- Requires the test user from 01_seed_test_user.sql.
WITH new_jobs AS (
    INSERT INTO jobs (user_id, task_type_id, params, schedule_type)
    SELECT u.id,
           tt.id,
           jsonb_build_object('to', 'test.user@example.com',
                              'first_name', 'Load ' || g,
                              'load_test', true,
                              'seq', g),
           'ONE_TIME'
    FROM generate_series(1, 200) AS g,
         (SELECT id FROM users WHERE email = 'test.user@example.com') AS u,
         (SELECT id FROM task_types WHERE name = 'welcome_email') AS tt
    RETURNING id,
              now() + make_interval(secs => 30 + ((params ->> 'seq')::int % 210)) AS scheduled_at
),
schedules AS (
    INSERT INTO one_time_schedules (job_id, scheduled_at)
    SELECT id, scheduled_at FROM new_jobs
)
INSERT INTO job_executions (job_id, scheduled_at)
SELECT id, scheduled_at FROM new_jobs;

-- Status breakdown of the load-test executions (run again after the watcher)
SELECT je.status, count(*)
FROM job_executions je
JOIN jobs j ON j.id = je.job_id
WHERE j.params ->> 'load_test' = 'true'
GROUP BY je.status;
