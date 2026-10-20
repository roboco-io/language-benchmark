# RealWorld backend (Scala 3 + Play + Slick + SQLite)

A backend implementation of the [RealWorld API spec](https://realworld-docs.netlify.app/specifications/backend/endpoints/):
JWT authentication, users and profiles, articles, comments, favorites, tags and the personal feed.

## Run

```sh
PORT=8000 ./start.sh
```

`start.sh` resolves dependencies, builds a staged (release) distribution with `sbt stage`, and starts the server in the foreground.
The server listens on `PORT` (default 8000 in `start.sh`). Settings can be overridden with environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8000` | HTTP port |
| `DB_PATH` | `data/realworld.db` | SQLite database file, created with its schema when missing |
| `JWT_SECRET` | built-in dev value | HS256 signing key for tokens |
| `APPLICATION_SECRET` | built-in dev value | Play application secret |

## Docker

```sh
docker build -t realworld .
docker run -p 8000:8000 -e PORT=8000 -v realworld-data:/data realworld
```

The image is built from `sbt stage` output and runs on a JRE. An empty database is initialised automatically at startup.

## Concurrency and storage

- The Pekko HTTP server and Play's default dispatcher use a fork-join pool of exactly one thread per available CPU core
  (`parallelism-factor = 1.0`); Pekko's TCP selector count and the Slick executor and Hikari pool are also sized to the core count.
- SQLite runs in WAL mode with `busy_timeout=5000` ms, foreign keys on, and `IMMEDIATE` write transactions.

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154 requests). With the server running:

```sh
HOST=http://localhost:8000 ./api/hurl/run-hurl-tests.sh
```

## Update comment

`PUT /api/articles/:slug/comments/:id` with `{"comment":{"body":"..."}}` and `Authorization: Token <jwt>` updates a comment body (author only). Errors: 401 missing token, 403 not the author, 404 unknown article or comment, 422 blank body. Covered by `api/hurl/comment_update.hurl`.
