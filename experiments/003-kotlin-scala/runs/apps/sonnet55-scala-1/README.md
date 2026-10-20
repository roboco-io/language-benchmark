# RealWorld backend (Scala 3 + Play + Slick + SQLite)

Implements the full [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) API:
JWT authentication, users/profiles, articles, comments, favorites, tags and the feed.

## Run

```sh
PORT=8000 ./start.sh
```

`start.sh` builds a staged (release) distribution with `sbt stage` and runs it in the foreground.
`PORT` selects the listen port (default 8000). The database is `data/realworld.db`
(override with `DATABASE_URL`); the schema is created automatically on startup.

## Tests

Start the server, then run the vendored official Hurl tests (`api/hurl`, 13 files, 154 requests):

```sh
HOST=http://localhost:8000 api/run-hurl.sh
```

## Docker

```sh
docker build -t realworld .
docker run --rm -p 8000:8000 -e PORT=8000 -v rw-data:/data realworld
```

## Concurrency and storage

- `CORES` (set from `nproc` by `start.sh` and the Docker entrypoint) sizes Pekko's default dispatcher
  (`parallelism-min/max`) and the Slick/Hikari pools (`numThreads`, `maxConnections`).
- SQLite runs in WAL mode with `busy_timeout=5000` (set via the JDBC URL).
- Passwords are hashed with PBKDF2-HMAC-SHA256; tokens are HS256 JWTs. Set `APPLICATION_SECRET` in production.
