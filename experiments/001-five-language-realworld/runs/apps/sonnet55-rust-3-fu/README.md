# RealWorld backend (Rust, Axum, SQLx, SQLite)

Implements the full RealWorld backend API: JWT auth, users and profiles, articles, comments, favorites, tags and feed.

- Run: `./start.sh` (builds a release binary and runs it in the foreground). Port comes from `PORT` (default 8000).
- Config: `DATABASE_URL` (default `sqlite://realworld.db`), `JWT_SECRET`.
- Tests: with the server running, `HOST=http://localhost:8000 ./run-tests.sh` runs the vendored official Hurl tests in `api/hurl`.
- Docker: `docker build -t realworld . && docker run -p 8000:8000 -e PORT=8000 realworld`. The schema is created on start.
- Runtime: tokio multi-thread with one worker per CPU core; SQLite uses WAL and `busy_timeout` of 5000 ms.
