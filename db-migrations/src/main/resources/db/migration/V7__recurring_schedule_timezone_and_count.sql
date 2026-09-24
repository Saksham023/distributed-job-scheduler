-- Time zone the cron expression is evaluated in (IANA name, e.g. 'Asia/Kolkata').
-- Added with a default so the migration works on existing rows, then the
-- default is dropped: every new schedule must state its zone explicitly.
ALTER TABLE recurring_schedules
    ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';
ALTER TABLE recurring_schedules
    ALTER COLUMN timezone DROP DEFAULT;

-- How many executions have been generated so far; enforces max_occurrences.
-- Skipped (missed) runs are never generated, so they don't count.
ALTER TABLE recurring_schedules
    ADD COLUMN occurrences_generated INTEGER NOT NULL DEFAULT 0;

ALTER TABLE recurring_schedules
    ADD CONSTRAINT chk_recurring_max_occurrences_positive
        CHECK (max_occurrences IS NULL OR max_occurrences > 0),
    ADD CONSTRAINT chk_recurring_ends_after_start
        CHECK (ends_at IS NULL OR ends_at > starts_at);