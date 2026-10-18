# RealWorld API — Fastify + Prisma + SQLite

This project implements the backend of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) specification in TypeScript on Node.js. It covers JWT authentication, users and profiles, follow, article CRUD, comments, favorites, tags, and the personal feed.

In addition to the specification, the API supports updating a comment with `PUT /api/articles/:slug/comments/:id` and the body `{"comment":{"body":"..."}}`. Only the comment author can update a comment. The endpoint returns `200` with the updated comment, `401` without a token, `403` for other users, `404` for an unknown article or comment, and `422` for a blank body.

## Running

`./start.sh` installs dependencies with `npm ci`, generates the Prisma client, compiles TypeScript, and then starts the server in the foreground. The server listens on the port in the `PORT` environment variable (default `3000`).

```sh
PORT=8000 ./start.sh
```

The Docker image uses a multi-stage build. The runtime stage contains only production dependencies and the compiled output.

```sh
docker build -t realworld-fastify .
docker run -e PORT=8000 -p 8000:8000 -v realworld-data:/app/data realworld-fastify
```

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `3000` | HTTP port. |
| `HOST` | `0.0.0.0` | Bind address. |
| `DATABASE_URL` | `file:./data/realworld.db` (`file:/app/data/realworld.db` in Docker) | Location of the SQLite file. |
| `JWT_SECRET` | random value per start | HMAC key for tokens. Set it to keep tokens valid across restarts. |
| `WEB_CONCURRENCY` | number of CPU cores | Number of cluster workers. |
| `LOG_LEVEL` | unset (logging off) | Fastify/pino log level, for example `info`. |

## Process and database model

The primary process opens the SQLite database before it starts any worker. It switches the database to WAL mode and applies every pending migration in `prisma/migrations`. Because of this step, a server that starts with an empty database creates its own schema, and the Prisma CLI is not needed at runtime. Applied migrations are recorded in the `_app_migrations` table.

After this step, the primary forks one worker per available CPU core (`os.availableParallelism()`) with the Node `cluster` module. The workers share the listening port. If a worker exits unexpectedly, the primary starts a replacement. Each worker uses Prisma with the `better-sqlite3` driver adapter. Each worker connection sets `busy_timeout = 5000` ms, so a writer waits for a lock held by another worker instead of failing at once.

## API tests

The official Hurl suite from [realworld-apps/realworld](https://github.com/realworld-apps/realworld) (`specs/api/hurl`, 13 files, 154 requests) is vendored in `api/hurl`. The extra file `api/hurl/comments_update.hurl` (12 requests) tests the comment update endpoint, and the runner executes it together with the official files. To run it against a running server, use this command:

```sh
HOST=http://localhost:8000 npm run test:api
```

## Layout

- `src/index.ts`: cluster primary and worker startup.
- `src/migrate.ts`: WAL setup and schema migrations at startup.
- `src/app.ts`: Fastify instance, JSON parsing, and error format.
- `src/routes/`: endpoint handlers.
- `prisma/schema.prisma`: data model. `prisma/migrations/` holds the SQL that `prisma migrate diff` generated from the data model.
