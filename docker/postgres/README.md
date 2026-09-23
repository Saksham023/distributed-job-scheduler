# Postgres (local dev)

Spins up an empty Postgres 18 database (18+ is required for the native
`uuidv7()` used as the default for UUID primary keys; note 18+ images mount
the volume at `/var/lib/postgresql`, not `/var/lib/postgresql/data`). The schema is not created here: the
app's Flyway migrations create and upgrade it at startup
(`job-scheduler-service/src/main/resources/db/migration/`).

## Usage

```bash
docker compose up -d
```

Connection details:

- Host: `localhost`
- Port: `5432`
- User: `job_scheduler`
- Password: `job_scheduler`
- Database: `job_scheduler`

```
postgresql://job_scheduler:job_scheduler@localhost:5432/job_scheduler
```

## Notes

- Data persists in the named volume `pgdata` across restarts.
- To start over with an empty database: `docker compose down -v`, then
  `docker compose up -d`. The next app start re-applies all migrations.
