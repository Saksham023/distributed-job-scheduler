-- jobs.schedule_type
ALTER TABLE jobs DROP CONSTRAINT jobs_schedule_type_check;
UPDATE jobs SET schedule_type = upper(schedule_type);
ALTER TABLE jobs ADD CONSTRAINT jobs_schedule_type_check
    CHECK (schedule_type IN ('ONE_TIME', 'RECURRING'));

-- jobs.status
ALTER TABLE jobs DROP CONSTRAINT jobs_status_check;
UPDATE jobs SET status = upper(status);
ALTER TABLE jobs ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE jobs ADD CONSTRAINT jobs_status_check
    CHECK (status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED'));

-- job_executions.status
ALTER TABLE job_executions DROP CONSTRAINT job_executions_status_check;
UPDATE job_executions SET status = upper(status);
ALTER TABLE job_executions ALTER COLUMN status SET DEFAULT 'PENDING';
ALTER TABLE job_executions ADD CONSTRAINT job_executions_status_check
    CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'));

-- The watcher's partial index filters on the status value, so it must follow
DROP INDEX idx_executions_pending_schedule;
CREATE INDEX idx_executions_pending_schedule
    ON job_executions (scheduled_at)
    WHERE status = 'PENDING';

-- task_types.service is also a fixed list (each service needs its own sending code)
UPDATE task_types SET service = upper(service);
ALTER TABLE task_types ALTER COLUMN service SET DEFAULT 'EMAIL';
ALTER TABLE task_types ADD CONSTRAINT task_types_service_check
    CHECK (service IN ('EMAIL'));