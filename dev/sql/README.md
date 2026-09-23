# Dev SQL scripts

Scripts to run **by hand** against the **local** database (e.g. from DataGrip).

- Never run these against production.
- These are not migrations: schema changes and reference data every
  environment needs (like task types) go in Flyway migrations under
  `job-scheduler-service/src/main/resources/db/migration/`.
- Scripts are safe to re-run.

## Local connection

| Setting  | Value           |
|----------|-----------------|
| Host     | `localhost`     |
| Port     | `5432`          |
| Database | `job_scheduler` |
| User     | `job_scheduler` |
| Password | `job_scheduler` |

## Scripts

| File | Purpose |
|------|---------|
| `01_seed_test_user.sql` | Creates the local test user (`userId` for API requests) |
| `02_inspect_state.sql`  | Read-only queries to check migrations, seed data, jobs and executions |
| `03_load_test_pending_executions.sql` | Inserts N `PENDING` executions due within 4 min (bypasses the API fast path), each with a unique `first_name` for duplicate detection |
| `04_cleanup_load_test.sql` | Deletes the rows created by `03` (purge the SQS queue separately) |
| `05_test_results.sql` | Read-only checks after a test: statuses, attempts, errors, unfinished work, execution/job mismatches |
| `06_reset_jobs.sql` | Empties all job data (keeps task types, templates, users) for a clean test run |
