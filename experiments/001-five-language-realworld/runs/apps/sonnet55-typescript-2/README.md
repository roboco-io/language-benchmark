# RealWorld Backend (Fastify + Prisma + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API in TypeScript.

## Run

```sh
PORT=8000 ./start.sh        # installs, builds, and runs in the foreground
```

The server reads `PORT` (default 3000) and `DATABASE_URL` (default `file:./data/app.db`, resolved against the working directory).

## Architecture

- The primary process creates the schema on an empty database (`prisma/schema.sql`, applied idempotently) and enables WAL mode, then forks one Node cluster worker per available CPU core.
- Each worker uses a single SQLite connection with `busy_timeout = 5000`.

## Docker

```sh
docker build -t realworld .
docker run -p 8000:8000 -e PORT=8000 realworld
```

## Tests

The official RealWorld Hurl tests are vendored in `tests/hurl` (13 files, 154 requests). With the server running:

```sh
PORT=8000 tests/run-hurl.sh
```

## Schema changes

After editing `prisma/schema.prisma`, regenerate the bootstrap SQL:

```sh
DATABASE_URL=file:./tmp.db npx prisma migrate diff --from-empty --to-schema-datamodel prisma/schema.prisma --script > prisma/schema.sql
```
