# RealWorld backend (Rust + Axum + SQLx + SQLite)

Implements the full RealWorld backend API: JWT auth, users/profiles, articles, comments, favorites, tags and feed.

## Run

    PORT=8000 ./start.sh        # builds a release binary and runs it in the foreground

Environment variables: `PORT` (default 8000), `DATABASE_URL` (default `sqlite://realworld.db`), `JWT_SECRET`.
The schema (`migrations/schema.sql`) is created automatically on startup.

## Docker

    docker build -t realworld .
    docker run -p 8000:8000 -e PORT=8000 realworld

## Tests

The official Hurl tests are vendored in `api/hurl` (13 files, 154 requests):

    HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh

## Runtime

Tokio multi-thread runtime with one worker per available CPU core. SQLite uses WAL mode and `busy_timeout` of 5000 ms.
