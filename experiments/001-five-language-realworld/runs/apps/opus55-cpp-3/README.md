# RealWorld API: C++20 + Drogon + SQLite

This repository implements the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
backend API. It uses C++20, the Drogon web framework, the Drogon ORM `DbClient` with SQLite, and CMake.

## Run locally

```sh
PORT=8000 ./start.sh
```

`start.sh` installs missing dependencies, builds a release binary in `build/`, and runs the server in
the foreground. On macOS it installs dependencies with Homebrew. On Debian or Ubuntu it installs
dependencies with `apt-get` and builds Drogon from source into `.deps/` the first time it runs.

## Run with Docker

```sh
docker build -t realworld-drogon .
docker run -e PORT=8000 -p 8000:8000 realworld-drogon
```

The image contains an optimized release build. The container stores its database at
`/data/realworld.db`. When the database is empty, the server creates the schema at startup.

## Configuration

| Variable        | Default         | Purpose                                 |
|-----------------|-----------------|-----------------------------------------|
| `PORT`          | `8000`          | HTTP listening port                     |
| `DATABASE_PATH` | `realworld.db`  | SQLite database file                    |
| `JWT_SECRET`    | built-in value  | HMAC key that signs JWTs (set it in production) |

## Runtime design

The server calls `app().setThreadNum()` with `std::thread::hardware_concurrency()`, so it runs one
event-loop thread per available CPU core. SQLite PRAGMAs such as `busy_timeout` apply only to one
connection. For this reason, the server opens one single-connection Drogon `DbClient` per core and
runs these PRAGMAs on each client: `journal_mode = WAL`, `busy_timeout = 5000`, and
`foreign_keys = ON`. Request handlers select a client from this pool in round-robin order.

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154 requests). It comes from
`specs/api/hurl` in https://github.com/realworld-apps/realworld. To run it against a running server:

```sh
HOST=http://localhost:8000 ./api/run-api-tests-hurl.sh
```
