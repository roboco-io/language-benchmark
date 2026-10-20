# RealWorld API — Scala 3 + Play Framework + Slick + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
API: JWT authentication, users and profiles, articles, comments, favorites, tags and the personal feed.

Stack: Scala 3.3, Java 25, Play Framework 3.0 (Pekko HTTP server), Slick 3.6 with HikariCP, SQLite (sqlite-jdbc), sbt 1.11.

## Running

```sh
PORT=8080 ./start.sh
```

`start.sh` resolves dependencies, builds a production release with `sbt stage`, and then runs the server in the
foreground. The server listens on `$PORT` (default `8080`). The database file is `data/realworld.db` by default;
set `DATABASE_PATH` to change it.

### Docker

```sh
docker build -t realworld-play .
docker run --rm -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-play
```

The image is built from the staged production release and runs on a JRE-only base image. The database lives at
`/data/realworld.db`. When the server starts with an empty database, it creates the schema itself
(`CREATE TABLE IF NOT EXISTS ...` on every startup).

### Configuration

| Variable             | Default                    | Purpose                               |
|----------------------|----------------------------|---------------------------------------|
| `PORT`               | `8080`                     | HTTP port                             |
| `DATABASE_PATH`      | `data/realworld.db`        | SQLite database file                  |
| `JWT_SECRET`         | built-in development value | HMAC-SHA256 key that signs the tokens |
| `APPLICATION_SECRET` | built-in development value | Play application secret               |

Set `JWT_SECRET` and `APPLICATION_SECRET` in any real deployment.

## Concurrency and SQLite settings

The Pekko default dispatcher, on which Play's Pekko HTTP server handles requests and runs Futures, and the Pekko
internal dispatcher both use a fork-join pool with `parallelism-factor = 1.0`, `parallelism-min = 1` and a high
`parallelism-max`. The pool size is therefore exactly the number of available CPU cores
(`conf/application.conf`). Slick's JDBC thread pool and the HikariCP connection pool are also sized to the core
count (`app/realworld/db/DatabaseProvider.scala`). The startup log reports the effective values.

Every SQLite connection is opened with `journal_mode=WAL`, `busy_timeout=5000` (milliseconds), `synchronous=NORMAL`
and `foreign_keys=ON`. Transactions start with `BEGIN IMMEDIATE`. This makes a writer wait on `busy_timeout` for
the write lock instead of failing with `SQLITE_BUSY` when a read-then-write transaction races another writer.

## API tests

The official RealWorld Hurl suite (13 files, 154 requests) is vendored in `api/hurl`, copied from
[realworld-apps/realworld](https://github.com/realworld-apps/realworld) (`specs/api/hurl`). Run it against a
running server:

```sh
HOST=http://localhost:8080 ./api/run-api-tests-hurl.sh
```

## Project layout

- `app/realworld/AppLoader.scala`: compile-time dependency injection and the JSON error handler.
- `app/realworld/db/`: Slick table definitions, schema DDL and database setup.
- `app/realworld/auth/`: HS256 JWT and PBKDF2 password hashing.
- `app/realworld/services/`: user, profile, article, comment, favorite and tag logic.
- `app/realworld/controllers/`: HTTP endpoints, request validation and JSON rendering.
- `conf/routes`, `conf/application.conf`: routes and runtime configuration.
