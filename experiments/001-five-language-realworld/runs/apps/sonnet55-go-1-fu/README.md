# RealWorld backend (Go + Gin + GORM + SQLite)

A backend implementation of the [RealWorld API spec](https://realworld-docs.netlify.app/specifications/backend/endpoints/).
It covers JWT authentication, users and profiles, articles, comments, favorites, tags, and the feed.

## Run

```sh
./start.sh            # installs dependencies, builds, and runs in the foreground
PORT=3000 ./start.sh  # choose the port (default 8000)
```

Environment variables: `PORT` (default `8000`), `DB_PATH` (default `realworld.db`), `JWT_SECRET`.

## Docker

```sh
docker build -t realworld .
docker run -e PORT=8000 -p 8000:8000 realworld
```

The image uses an optimized static build. The schema is created on startup, so an empty database works.

## Runtime settings

- `GOMAXPROCS` is set to the number of CPU cores at startup.
- SQLite runs in WAL mode with `busy_timeout=5000` (pure-Go `modernc.org/sqlite` through `glebarez/sqlite`).

## Tests

The official RealWorld Hurl tests are vendored in `tests/hurl` (13 files, 154 requests; upstream commit `ebbcdeb`).
Start the server, then run:

```sh
HOST=http://localhost:8000 tests/run-hurl.sh
```

`tests/run-hurl.sh` also runs the project's own tests in `tests/custom` (for example `update_comment.hurl`, which covers `PUT /api/articles/:slug/comments/:id`).

This needs the [`hurl`](https://hurl.dev) CLI.
