# RealWorld backend (Rust + Axum + SQLx + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API:
JWT authentication, users/profiles, articles, comments, favorites, tags and feed.

## Run

```sh
PORT=8000 ./start.sh
```

`start.sh` fetches dependencies, builds a release binary and runs the server in the foreground.

Environment variables: `PORT` (default 8000), `DATABASE_PATH` (default `realworld.db`), `JWT_SECRET`.

The schema is created automatically on startup. The tokio multi-thread runtime uses one worker
thread per CPU core. SQLite runs in WAL mode with `busy_timeout` of 5000 ms.

## Docker

```sh
docker build -t realworld .
docker run --rm -e PORT=8000 -p 8000:8000 realworld
```

## Tests

The official Hurl tests are vendored in `tests/hurl` (13 files, 154 requests).

```sh
HOST=http://localhost:8000 ./tests/run-api-tests-hurl.sh
```
