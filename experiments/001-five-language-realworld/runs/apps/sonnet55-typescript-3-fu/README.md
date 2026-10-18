# RealWorld backend (TypeScript, Fastify, Prisma, SQLite)

Implementation of the RealWorld backend API specification.

## Run

- `./start.sh` installs dependencies, builds, and starts the server in the foreground. The port comes from `PORT` (default 3000). The database path comes from `DATABASE_URL` (default `file:./dev.db`).
- Docker: `docker build -t realworld . && docker run -e PORT=8000 -p 8000:8000 realworld`. The image uses a production build and creates the schema when the database is empty. The default database is `/data/app.db`.

## Design

- The primary process forks one worker per available CPU core (`WORKERS` overrides the count). It restarts workers that exit.
- SQLite runs in WAL mode with `busy_timeout = 5000`. Each worker keeps one pooled connection so the PRAGMAs apply to every query.
- The schema is created at startup with idempotent DDL (`src/schema.ts`, generated from `prisma/schema.prisma`).

## Tests

Start the server, then run the vendored official Hurl tests:

```
HOST=http://localhost:8000 bash api/hurl/run-hurl-tests.sh
```
