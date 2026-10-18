# RealWorld Backend (Fastify + Prisma + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API in TypeScript.

## Run

```bash
PORT=8000 ./start.sh
```

`start.sh` installs dependencies, generates the Prisma client, compiles TypeScript, and runs the server in the foreground.

Docker:

```bash
docker build -t realworld .
docker run -e PORT=8000 -p 8000:8000 -v realworld-data:/data realworld
```

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `3000` | Listen port |
| `DATABASE_URL` | `file:./data/realworld.db` (`file:/data/realworld.db` in Docker) | SQLite file |
| `JWT_SECRET` | dev secret | HS256 signing key; set it in production |
| `WORKERS` | CPU core count | Override the worker count |

## Design notes

- The Node `cluster` primary forks one worker per available CPU core. Each worker owns one Prisma connection.
- Every boot creates the schema with idempotent `CREATE ... IF NOT EXISTS` statements (`src/schema.ts`, derived from `prisma/schema.prisma`), so an empty database needs no CLI step.
- SQLite runs in WAL mode with `busy_timeout = 5000` ms.
- Passwords are hashed with scrypt; tokens are HS256 JWTs sent as `Authorization: Token <jwt>`.

## Tests

The official RealWorld Hurl tests are vendored in `api/hurl` (13 files, 154 requests). With a server running:

```bash
HOST=http://localhost:8000 api/run-hurl.sh
```
