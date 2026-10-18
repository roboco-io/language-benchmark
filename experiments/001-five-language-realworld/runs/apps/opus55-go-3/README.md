# RealWorld API — Go + Gin + GORM + SQLite

A backend for the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
spec: JWT authentication, users and profiles, articles, comments, favorites, tags and the feed.
SQLite runs on the pure-Go `modernc.org/sqlite` driver, so the binary builds with `CGO_ENABLED=0`.

## Run

```sh
PORT=8000 ./start.sh        # downloads modules, builds bin/server, runs it in the foreground
```

```sh
docker build -t realworld-go .
docker run -p 8000:8000 -e PORT=8000 -e JWT_SECRET=change-me -v rw-data:/data realworld-go
```

The server creates the schema on startup when the database is empty.

| Variable        | Default              | Purpose                          |
|-----------------|----------------------|----------------------------------|
| `PORT`          | `8000`               | HTTP listen port                 |
| `DATABASE_PATH` | `data/realworld.db`  | SQLite file (`/data/...` in Docker) |
| `JWT_SECRET`    | insecure dev default | HMAC key for JWT signing         |

## Runtime settings

The server sets `GOMAXPROCS` to `runtime.NumCPU()`, so it uses one OS thread per available core.
Every SQLite connection opens with `journal_mode=WAL` and `busy_timeout=5000`; startup fails if
the pragmas do not take effect. Write transactions use `BEGIN IMMEDIATE` to avoid lock-upgrade deadlocks.

## Tests

The official Hurl suite (13 files, 154 requests) is vendored in `api/hurl`:

```sh
HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh
```
