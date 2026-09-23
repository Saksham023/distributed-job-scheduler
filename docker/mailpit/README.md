# Mailpit (local dev email)

A fake mail server for local development. The app sends email to it over SMTP
exactly as it would to a real server, but Mailpit **catches** every message
instead of delivering it: nothing leaves your machine.

## Usage

```bash
docker compose up -d
```

- **SMTP** (the app sends here): `localhost:1025`, no username/password
- **Inbox** (open in a browser): http://localhost:8025

Caught emails are stored in the `mailpit-data` volume, so they survive
container restarts. `MP_MAX_MESSAGES: 0` turns off Mailpit's default limit of
500 emails (beyond which it silently deletes the oldest), so load tests can
count every email. To empty the inbox, use the web UI's delete button, or
`docker compose down -v` to remove the volume.
