# RealWorld API — Rust / Axum / SQLx / SQLite

This repository implements the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API. It covers JWT authentication, users and profiles, article CRUD, comments, favorites, tags, and the personal feed.

## Running

`./start.sh` fetches the dependencies, builds an optimized release binary, and runs the server in the foreground. The server reads its configuration from these environment variables:

| Variable       | Default                          | Purpose                          |
|----------------|----------------------------------|----------------------------------|
| `PORT`         | `8000`                           | HTTP listen port                 |
| `DATABASE_URL` | `sqlite://realworld.db`          | SQLite database file             |
| `JWT_SECRET`   | `realworld-development-secret`   | HMAC key for signing JWTs (set it in production) |

To run with Docker, build the image and start a container. The container creates the schema itself when it starts with an empty database at `/data/realworld.db`.

```sh
docker build -t realworld-axum .
docker run --rm -e PORT=8080 -p 8080:8080 realworld-axum
```

## Runtime configuration

The server builds a tokio multi-thread runtime with one worker thread per available CPU core (`std::thread::available_parallelism`). Every SQLite connection uses WAL journal mode, a `busy_timeout` of 5000 ms, and enforced foreign keys. The schema in `src/schema.sql` uses `CREATE TABLE IF NOT EXISTS`, so the server applies it safely on every startup.

## Tests

The official RealWorld Hurl suite (13 files, 154 requests) is vendored in `api/hurl` from `realworld-apps/realworld` (`specs/api`, commit `ebbcdeb`). Start the server and run the suite with [Hurl](https://hurl.dev):

```sh
HOST=http://localhost:8000 ./api/run-api-tests-hurl.sh
```
