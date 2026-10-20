# RealWorld backend: Scala 3 + Play + Slick + SQLite

This project implements the [RealWorld backend API](https://realworld-docs.netlify.app/specifications/backend/endpoints/):
JWT authentication, users and profiles, articles, comments, favorites, tags and the feed.

The stack is Scala 3 on Java 25, Play Framework 3 (Pekko HTTP server), Slick, and SQLite. The build tool is sbt.

## Run locally

```sh
PORT=8000 ./start.sh
```

`start.sh` resolves dependencies, builds the staged production distribution with `sbt stage`,
and runs the server in the foreground on `$PORT` (default `8000`).
The database file defaults to `data/realworld.db`. Set `DATABASE_PATH` to change it.

## Run with Docker

```sh
docker build -t realworld-play-scala .
docker run -p 8000:8000 -e PORT=8000 realworld-play-scala
```

The image contains the release build on a JRE base image. On startup the server creates the schema
if the database is empty. The database lives in the `/app/data` volume.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8000` | HTTP port |
| `DATABASE_PATH` | `data/realworld.db` | SQLite database file |
| `JWT_SECRET` | development value | HMAC key for JWT signing |
| `APPLICATION_SECRET` | development value | Play secret key |

Set `JWT_SECRET` and `APPLICATION_SECRET` in any real deployment.

## Concurrency

Pekko's default dispatcher and internal dispatcher use a fork-join pool with parallelism equal to
the number of available CPU cores (`conf/application.conf`). Play's Pekko HTTP server runs on the
default dispatcher. Slick reads use a connection pool and thread pool sized to the core count.

SQLite runs in WAL mode with `busy_timeout=5000` on every connection. SQLite allows only one writer
at a time, so all writes go through a dedicated single-connection Slick database. This serializes
writes inside the JVM and avoids lock contention between connections.

## API tests

The official RealWorld Hurl tests are vendored in `api/hurl` (13 files, 154 requests).
Start the server, then run:

```sh
HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh
```
