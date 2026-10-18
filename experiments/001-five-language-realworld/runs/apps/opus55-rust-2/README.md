# RealWorld API — Rust (Axum + SQLx + SQLite)

A backend for the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
spec: JWT authentication, users and profiles, articles, comments, favorites, tags and the feed.

## Run locally

    ./start.sh              # fetches crates, builds in release mode, runs in the foreground
    PORT=3000 ./start.sh    # choose a port (default 8000)

Environment variables:

| Variable       | Default                          | Purpose                     |
|----------------|----------------------------------|-----------------------------|
| `PORT`         | `8000`                           | HTTP listen port            |
| `DATABASE_URL` | `sqlite://realworld.db`          | SQLite database file        |
| `JWT_SECRET`   | a development-only value         | Secret used to sign tokens  |

Set `JWT_SECRET` in any real deployment, because the default value is public.

## Docker

    docker build -t realworld .
    docker run -p 8000:8000 -e PORT=8000 -v rw-data:/data realworld

The image contains a release build. On startup the server creates the schema
if the database (default `/data/realworld.db`) is empty.

## Runtime characteristics

The server builds a tokio multi-thread runtime with one worker thread per available
CPU core (`std::thread::available_parallelism`). SQLite runs in WAL journal mode
with a `busy_timeout` of 5000 ms and foreign keys enabled.

## Tests

The official RealWorld Hurl suite (13 files, 154 requests) is vendored in `api/hurl`
(see `api/hurl/SOURCE.md` for the upstream commit). With the server running:

    HOST=http://localhost:8000 ./api/hurl/run-hurl-tests.sh
