# RealWorld API — Rust / Axum / SQLx / SQLite

Backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
API: JWT authentication, users and profiles, articles, comments, favorites,
tags and the personal feed.

## Run locally

```sh
PORT=8080 ./start.sh
```

`start.sh` fetches dependencies, builds a release binary and runs the server
in the foreground.

## Run with Docker

```sh
docker build -t realworld-rust .
docker run --rm -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-rust
```

The image contains a release build. The server creates its schema on startup
when the database is empty.

## Configuration

| Variable       | Default                    | Purpose                         |
|----------------|----------------------------|---------------------------------|
| `PORT`         | `8080`                     | HTTP listening port             |
| `DATABASE_URL` | `sqlite:data/realworld.db` | SQLite database location        |
| `JWT_SECRET`   | development secret         | HMAC key used to sign JWTs      |
| `RUST_LOG`     | `info`                     | Log filter                      |

## Runtime characteristics

The tokio multi-thread runtime starts one worker thread per available CPU
core (`std::thread::available_parallelism`). Every SQLite connection uses WAL
journal mode and a `busy_timeout` of 5000 ms. The server logs both values at
startup.

## API tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154
requests; upstream commit in `api/UPSTREAM_COMMIT`). Run it against a running
server:

```sh
HOST=http://localhost:8080 ./api/run-api-tests-hurl.sh
```

Additional tests for endpoints beyond the official spec live in `tests/hurl`
(currently `PUT /api/articles/:slug/comments/:id`, update a comment). Run them
together with the official suite:

```sh
HOST=http://localhost:8080 ./api/hurl/run-hurl-tests.sh api/hurl/*.hurl tests/hurl/*.hurl
```
