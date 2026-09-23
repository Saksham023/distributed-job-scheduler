# Enum vs. lookup table vs. ID: how to represent a value

A quick reference for deciding how a value is stored in the database,
represented in Java, and sent through the API.

## The one question

> **"If I add a new value, do I need to write new code?"**

- **Yes** → it's a **fixed list** (the code defines it).
- **No, it's just new data** → it's a **growing list** (the data defines it).
- **It isn't a list at all, users create it** → it's a **record**.

## The cheat sheet

| | Fixed list | Growing list | Records users create |
|---|---|---|---|
| **Example** | Order status: `PLACED`, `PREPARING`, `DELIVERED` | Cuisine: Italian, Chinese, Thai | An order, a user |
| **Adding a new one means...** | Writing new code (refund logic for `REFUNDED`) | Inserting a row, no code | Normal app usage |
| **Database** | Text column + `CHECK` constraint | Its own table; other tables point to it with a foreign key | Its own table, UUID primary key |
| **Java** | `enum` | Loaded from the table (no enum) | A record/class |
| **API sends** | Enum name: `"DELIVERED"` | Stable code: `"thai"` | The ID: `"01a0cb48-..."` |

## In this project

| Value | Kind | DB | API |
|---|---|---|---|
| `schedule_type` (`ONE_TIME`, `RECURRING`) | Fixed list | `VARCHAR` + `CHECK` | name |
| `jobs.status`, `job_executions.status` | Fixed list | `VARCHAR` + `CHECK` | name |
| Task type (`welcome_email`) | Growing list | `task_types` table, `jobs.task_type_id` FK | `"welcome_email"` |
| Job, user | Record | UUID / ID primary key | the ID |

## Why each choice

**Fixed list → no lookup table.** A new row in a `statuses` table does nothing by
itself; the code still has to be written. The table would just be a copy of
the enum that has to be kept in sync. A `CHECK` constraint already rejects bad
values, and `'DELIVERED'` is readable in queries, while `status_id = 3` isn't.

**Growing list → no enum.** Every new cuisine would need a developer and a
redeploy just to add a word. The table *is* the list.

**API uses the code, not the table's ID.** Auto-numbered IDs (`SERIAL`) depend
on insert order, so Thai can be `7` on your laptop and `4` in production.
`"thai"` is the same everywhere. The server converts code → ID internally.

**Records use their ID in the API.** An order has no natural readable code, and
its UUID is assigned once and never changes, so the ID *is* its stable name.

## Rule for the API identifier

> The API accepts whatever stays **the same in every environment**.

- Fixed list → enum name
- Growing list → a unique code column (`task_types.name UNIQUE`)
- Record → its UUID

## Mistakes to avoid

- **Storing an enum's position number** (`0`, `1`, `2`) in the database.
  Reordering the enum silently changes the meaning of existing rows. Store the
  name (`'DELIVERED'`), like JPA's `@Enumerated(EnumType.STRING)`.
- **Mirroring a growing list as a Java enum.** The two drift apart the first time
  someone adds a row.
- **Exposing auto-numbered IDs of reference data in the API.** They differ
  between environments.
- **Guessing on bad input.** `"Thai"` or `"thai "` must not match `"thai"`.
  Reject with `400`; don't silently "fix" it.
