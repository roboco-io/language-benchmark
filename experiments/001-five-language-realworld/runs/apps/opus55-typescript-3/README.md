# RealWorld API: Fastify + Prisma + SQLite

This repository implements the [RealWorld backend API](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
in TypeScript on Node.js. It uses Fastify for HTTP, Prisma as the ORM and SQLite for storage.
It covers JWT authentication, users, profiles, articles, comments, favorites, tags and the feed.

## Run locally

```sh
PORT=8000 ./start.sh
```

`start.sh` installs dependencies (`npm ci`), builds the TypeScript code, applies the database
migrations and starts the server in the foreground. The server listens on `PORT` (default `8000`).
The database file is `data/realworld.db` unless `DATABASE_URL` is set.

## Run with Docker

```sh
docker build -t realworld-ts .
docker run --rm -e PORT=8000 -p 8000:8000 -v realworld-data:/app/data realworld-ts
```

The image contains only production dependencies and the compiled output. On start, the container
runs `prisma migrate deploy`, so it creates the schema when the database is empty.

## Configuration

| Variable          | Default                       | Purpose                                  |
| ----------------- | ----------------------------- | ---------------------------------------- |
| `PORT`            | `8000`                        | HTTP port                                |
| `DATABASE_URL`    | `file:./data/realworld.db`    | SQLite database location                 |
| `JWT_SECRET`      | development value             | Secret used to sign tokens; set it in production |
| `WEB_CONCURRENCY` | number of available CPU cores | Number of cluster worker processes       |
| `LOG_LEVEL`       | unset (logging off)           | Fastify log level, for example `info`    |

## Concurrency and SQLite settings

The primary process forks one Node cluster worker per available CPU core
(`os.availableParallelism()`), and it restarts a worker that exits. Each worker holds one SQLite
connection. Every connection sets `busy_timeout = 5000` ms, so a writer waits for the lock instead of
failing. The database uses WAL journal mode, so readers in other workers do not block the writer.
The primary enables WAL once before it forks the workers, because the switch to WAL needs a short
exclusive lock.

## API tests

The official RealWorld Hurl tests are vendored in `api/hurl` (13 files, 154 requests), copied from
[realworld-apps/realworld](https://github.com/realworld-apps/realworld) (`specs/api`). With the
server running:

```sh
HOST=http://localhost:8000 bash api/run-api-tests-hurl.sh
```
