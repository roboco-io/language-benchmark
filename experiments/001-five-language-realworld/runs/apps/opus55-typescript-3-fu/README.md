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

## Extension: update a comment

Besides the RealWorld spec, the API supports `PUT /api/articles/:slug/comments/:id` with the body
`{"comment":{"body":"<new text>"}}`. Authentication is required. Only the comment author can update
the comment. The response is `200` with `{"comment":{...}}` in the same shape as the create-comment
response. Only `body` changes, and the server refreshes `updatedAt`. Errors use the RealWorld format:

| Status | Condition                        | Response body                              |
| ------ | -------------------------------- | ------------------------------------------ |
| `401`  | No token                         | `{"errors":{"token":["is missing"]}}`      |
| `422`  | Empty `body`                     | `{"errors":{"body":["can't be blank"]}}`   |
| `404`  | Unknown article slug             | `{"errors":{"article":["not found"]}}`     |
| `404`  | Unknown comment id on the article | `{"errors":{"comment":["not found"]}}`    |
| `403`  | The user is not the comment author | `{"errors":{"comment":["forbidden"]}}`   |

The tests for this endpoint are in `api/hurl-extra`, separate from the vendored official tests.
To run all tests together:

```sh
HOST=http://localhost:8000 bash api/run-api-tests-hurl.sh api/hurl/*.hurl api/hurl-extra/*.hurl
```
