# RealWorld backend (Go + Gin + GORM + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API.

- Stack: Go, Gin, GORM, SQLite via the pure-Go `modernc.org/sqlite` driver (through `glebarez/sqlite`).
- Auth: JWT (HS256) sent as `Authorization: Token <jwt>`.
- SQLite runs in WAL mode with `busy_timeout=5000`. `GOMAXPROCS` is set to the CPU core count.

## Run

```sh
PORT=8000 ./start.sh        # installs deps, builds, runs in the foreground
```

Environment variables: `PORT` (default 8000), `DATABASE_PATH` (default `realworld.db`), `JWT_SECRET`.

## Docker

```sh
docker build -t realworld .
docker run -e PORT=8000 -p 8000:8000 realworld
```

The schema is created automatically on startup, so an empty database works.

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154 requests):

```sh
HOST=http://localhost:8000 ./api/run-api-tests-hurl.sh
```

## Update comment

`PUT /api/articles/:slug/comments/:id` with `{"comment":{"body":"<new text>"}}` and an `Authorization: Token <jwt>` header.
Only the comment author may update it. It returns `200` with the updated comment, `401` without a token, `403` for another user,
`404` for an unknown article or comment, and `422` for a blank body. The test is `api/hurl/comments_update.hurl`.
