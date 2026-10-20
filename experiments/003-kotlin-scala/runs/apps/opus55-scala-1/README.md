# RealWorld API: Scala 3 + Play + Slick + SQLite

This project is a backend for the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) API. It covers JWT authentication, users, profiles, articles, comments, favorites, tags and the feed.

It uses Scala 3.3 on Java 25, Play Framework 3 (Pekko HTTP server, compile-time dependency injection), Slick 3.6 and SQLite (`sqlite-jdbc`). The build tool is sbt 1.11.

## Running

`./start.sh` resolves the dependencies, builds the release distribution (`sbt stage`) and runs the server in the foreground. The server listens on `$PORT`. If `PORT` is not set, it uses port 8000.

```sh
PORT=8080 ./start.sh
```

The Docker image uses a multi-stage build. The first stage builds the staged release distribution. The second stage copies it into a JRE image. When the container starts with an empty database, it creates the schema.

```sh
docker build -t realworld-scala .
docker run -e PORT=8080 -p 8080:8080 -v rw-data:/app/data realworld-scala
```

| Variable              | Default               | Purpose                                 |
|-----------------------|-----------------------|-----------------------------------------|
| `PORT`                | `8000`                | HTTP port                               |
| `DATABASE_PATH`       | `data/realworld.db`   | SQLite database file                    |
| `JWT_SECRET`          | development value     | HMAC key for JWT signing (set in prod)  |
| `APPLICATION_SECRET`  | development value     | Play secret key (set in prod)           |
| `PASSWORD_ITERATIONS` | `10000`               | PBKDF2 work factor for password hashes  |

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl`. It has 13 files and 154 requests and comes from `realworld-apps/realworld` at commit `ebbcdeb`. Start the server first, then run the suite against it:

```sh
HOST=http://localhost:8000 ./api/run-api-tests-hurl.sh
```

## Concurrency and storage

At startup, the application loader sizes the Pekko default dispatcher to the number of available CPU cores (`Runtime.availableProcessors()`). This dispatcher runs the Pekko HTTP server and the application code. The loader also sizes the Pekko internal dispatcher, which Pekko IO uses, to the same number.

SQLite permits only one writer at a time. For this reason, all writes go through a single dedicated connection, and each write runs in its own transaction. Reads use a separate pool with one connection and one thread per core. Every connection runs with `journal_mode=WAL`, `busy_timeout=5000`, `foreign_keys=ON` and `synchronous=NORMAL`. The server logs the effective pragma values at startup.

The server creates the schema with idempotent `CREATE TABLE IF NOT EXISTS` statements when it starts.
