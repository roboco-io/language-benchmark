# RealWorld Backend (Go + Gin + GORM + SQLite)

This repository implements the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
("Conduit") backend API. It covers JWT authentication, users and profiles, article CRUD,
comments, favorites, tags, and the personal feed.

## Stack

The server uses Gin for HTTP routing and GORM as the ORM. GORM talks to SQLite through the
pure-Go `modernc.org/sqlite` driver, so the build needs no cgo (`CGO_ENABLED=0`).

## Running

`./start.sh` downloads the Go modules, builds a release binary into `bin/realworld`, and
runs the server in the foreground.

```sh
PORT=8080 ./start.sh
```

With Docker, the image contains a static release binary. When the container starts with an
empty database, the server creates the schema automatically.

```sh
docker build -t realworld-go .
docker run --rm -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-go
```

| Variable        | Default                          | Purpose                         |
|-----------------|----------------------------------|---------------------------------|
| `PORT`          | `8080`                           | HTTP listen port                |
| `DATABASE_PATH` | `realworld.db` (`/data/...` in Docker) | SQLite database file      |
| `JWT_SECRET`    | built-in development value       | HMAC key for signing JWTs. Set this in production. |

## Runtime configuration

At startup, the server sets `GOMAXPROCS` to the number of available CPU cores
(`runtime.NumCPU()`). Every SQLite connection opens with `journal_mode=WAL`,
`busy_timeout=5000`, `foreign_keys=ON`, and `synchronous=NORMAL`. Transactions use
`BEGIN IMMEDIATE`, which prevents lock-upgrade deadlocks between concurrent writers. The
server logs the effective values at startup, for example:

```
sqlite: journal_mode=wal busy_timeout=5000ms max_conns=20
listening on :8080 (GOMAXPROCS=10, db=realworld.db)
```

## API tests

`api/hurl` contains the official RealWorld Hurl suite (13 files, 154 requests). It is
vendored from `specs/api/hurl` in
[realworld-apps/realworld](https://github.com/realworld-apps/realworld) at commit
`ebbcdeb`. To run the suite against a running server:

```sh
scripts/run-hurl.sh http://localhost:8080
```

## Layout

- `main.go`: reads the environment, sets `GOMAXPROCS`, and starts the server.
- `internal/db`: GORM models, SQLite connection settings, and schema migration.
- `internal/api`: routes, JWT authentication, request validation, and handlers.
