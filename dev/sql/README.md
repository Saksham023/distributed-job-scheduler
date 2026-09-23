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
