-- Job Scheduler — final schema
-- Target: PostgreSQL (JSONB, gen_random_uuid() from pgcrypto/pgcrypto-free in PG13+)

-- ============================================================
-- 1. USERS
-- Owns jobs. Kept minimal — this project isn't building auth,
-- just enough to scope/authorize job ownership.
-- ============================================================
CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) UNIQUE NOT NULL,
    name        VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- 2. TASK_TYPES
-- Extensible catalog of job kinds. New service (sms/push) or
-- new mail type (welcome/goodbye/reminder) = a new row, no
-- code-level enum change needed for the schema itself.
-- ============================================================
CREATE TABLE task_types (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) UNIQUE NOT NULL,  -- 'welcome_email', 'reminder_email'
    service     VARCHAR(50) NOT NULL DEFAULT 'email',  -- 'email' | 'sms' | 'push'
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- 3. TEMPLATES
-- Content per task type. Multiple versions allowed; only one
-- active at a time is resolved at send-time.
-- ============================================================
CREATE TABLE templates (
    id            SERIAL PRIMARY KEY,
    task_type_id  INTEGER NOT NULL REFERENCES task_types(id),
    subject       TEXT,                  -- "Welcome, {{first_name}}!"
    body          TEXT NOT NULL,         -- template body with {{placeholders}}
    version       INTEGER NOT NULL DEFAULT 1,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_templates_active_lookup
    ON templates (task_type_id)
    WHERE is_active = true;

-- ============================================================
-- 4. JOBS
-- The intent/definition. One row per user request, whether
-- one-time or recurring. Actual fire times live in the
-- schedule satellite tables + job_executions.
-- ============================================================
CREATE TABLE jobs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        BIGINT NOT NULL REFERENCES users(id),
    task_type_id   INTEGER NOT NULL REFERENCES task_types(id),
    params         JSONB NOT NULL DEFAULT '{}',  -- recipient, subject vars, etc.
    schedule_type  VARCHAR(20) NOT NULL CHECK (schedule_type IN ('one_time', 'recurring')),
    status         VARCHAR(20) NOT NULL DEFAULT 'active'
                   CHECK (status IN ('active', 'paused', 'completed', 'cancelled')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_user_id ON jobs (user_id);

-- ------------------------------------------------------------
-- 4a. ONE_TIME_SCHEDULES — 1:1 with jobs where schedule_type='one_time'
-- ------------------------------------------------------------
CREATE TABLE one_time_schedules (
    job_id        UUID PRIMARY KEY REFERENCES jobs(id),
    scheduled_at  TIMESTAMPTZ NOT NULL
);

-- ------------------------------------------------------------
-- 4b. RECURRING_SCHEDULES — 1:1 with jobs where schedule_type='recurring'
-- generated_until is the watermark the generator advances.
-- ------------------------------------------------------------
CREATE TABLE recurring_schedules (
    job_id            UUID PRIMARY KEY REFERENCES jobs(id),
    cron_expression   VARCHAR(100) NOT NULL,
    starts_at         TIMESTAMPTZ NOT NULL,
    ends_at           TIMESTAMPTZ,
    max_occurrences   INTEGER,
    generated_until   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_recurring_schedules_generation
    ON recurring_schedules (generated_until);

-- ============================================================
-- 5. JOB_EXECUTIONS
-- Concrete, timestamped instances to run. This is what the
-- watcher/poller scans and pushes to the queue. One row per
-- fire time — for one-time jobs there's exactly one; for
-- recurring jobs the generator tops these up ahead of time.
-- ============================================================
CREATE TABLE job_executions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id        UUID NOT NULL REFERENCES jobs(id),
    scheduled_at  TIMESTAMPTZ NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'queued', 'processing', 'completed', 'failed', 'cancelled')),
    attempt       INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (job_id, scheduled_at)  -- generator idempotency guard
);

-- what the poller scans every tick
CREATE INDEX idx_executions_pending_schedule
    ON job_executions (scheduled_at)
    WHERE status = 'pending';

CREATE INDEX idx_executions_job_id ON job_executions (job_id);
