# RealWorld API — Fastify + Prisma + SQLite

A TypeScript (Node.js) implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API: JWT authentication, users and profiles, articles, comments, favorites, tags and the personal feed.

In addition to the standard endpoints, `PUT /api/articles/:slug/comments/:id` lets a comment's author update its body (`{"comment":{"body":"..."}}`). It returns `200` with the updated comment, `403` for other users, `404` for an unknown article or comment, and `422` for a blank body.

## Running

```bash
PORT=8000 ./start.sh
```

`start.sh` installs dependencies (`npm ci`), generates the Prisma client, compiles TypeScript and then runs the server in the foreground. The server listens on `$PORT` (default `8000`).

### Docker

```bash
docker build -t realworld-ts .
docker run -p 8000:8000 -e PORT=8000 -v realworld-data:/app/data realworld-ts
```

The image is a multi-stage build: the runtime stage contains only production dependencies and the compiled JavaScript. When the container starts with an empty database, it creates the schema itself.

## Configuration

| Variable          | Default                      | Purpose                                                        |
| ----------------- | ---------------------------- | -------------------------------------------------------------- |
| `PORT`            | `8000`                       | HTTP port                                                      |
| `HOST`            | `0.0.0.0`                    | Bind address                                                   |
| `DATABASE_URL`    | `file:<cwd>/data/realworld.db` | SQLite database location                                     |
| `JWT_SECRET`      | random per start             | Secret used to sign tokens; set it so tokens survive restarts  |
| `WEB_CONCURRENCY` | number of CPU cores          | Number of cluster workers                                      |
| `LOG_LEVEL`       | `warn`                       | Fastify log level                                              |

## Architecture

The primary process prepares the database and then forks one worker process per available CPU core (`os.availableParallelism()`) with the Node `cluster` module. Database preparation means that the primary applies the SQL in `prisma/migrations` when the database has no tables yet. The workers share the listening port, and the primary restarts a worker if it exits unexpectedly.

Each worker opens a single SQLite connection through Prisma. On that connection it sets `journal_mode = WAL` and `busy_timeout = 5000`. WAL mode lets readers proceed while one writer is active. The busy timeout makes a writer wait up to 5000 ms for the lock that another worker holds, instead of failing immediately.

Source layout:

- `src/index.ts` — cluster primary and worker startup
- `src/db.ts` — Prisma client creation, SQLite PRAGMAs and schema bootstrap
- `src/app.ts` — Fastify instance, JSON parsing, error handling and CORS
- `src/routes/` — endpoint handlers (users, profiles, articles, comments, tags)
- `src/serializers.ts` — conversion of database rows to API responses

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154 requests). `api/hurl/comments_update.hurl` adds tests for the comment-update endpoint. Run all of them against a running server with:

```bash
HOST=http://localhost:8000 bash api/run-api-tests-hurl.sh
```
