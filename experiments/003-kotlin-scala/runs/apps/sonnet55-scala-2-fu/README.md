# RealWorld backend (Scala 3 + Play + Slick + SQLite)

An implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API:
JWT authentication, users/profiles, article CRUD, comments, favorites, tags and the personal feed.

## Run

```sh
PORT=8000 ./start.sh
```

`start.sh` resolves dependencies, builds a staged release distribution with `sbt stage`, and starts the server in the
foreground. The port comes from the `PORT` environment variable (default 8000). The SQLite file is `realworld.db` in the
working directory unless `DB_PATH` is set; the schema is created automatically when the database is empty.

## Docker

```sh
docker build -t realworld-scala .
docker run -e PORT=8000 -p 8000:8000 -v rw-data:/data realworld-scala
```

The image is built from the staged (production) distribution and stores the database in `/data/realworld.db`.

## Tests

The official Hurl suite (13 files, 154 requests) is vendored in `api/hurl`. With the server running:

```sh
HOST=http://localhost:8000 ./api/run-api-tests-hurl.sh
```

## Design notes

- **Concurrency:** Play runs on Pekko HTTP. The default dispatcher uses `parallelism-factor = 1.0`, so its pool equals the
  number of available cores; the Slick executor and the HikariCP pool are sized with
  `Runtime.availableProcessors` as well.
- **SQLite:** every pooled connection is opened with `journal_mode=WAL`, `busy_timeout=5000`, `foreign_keys=ON` and
  `transaction_mode=IMMEDIATE` (write transactions take the lock up front and wait on the busy timeout).
- **Queries:** Slick plain-SQL interpolation (`sql"..."`) is used for all queries.
- **Security:** passwords are hashed with PBKDF2-HMAC-SHA256; tokens are HS256 JWTs signed with the `APPLICATION_SECRET`
  environment variable (a development default is used when it is unset — set it in production).
- **Errors:** responses follow the `{"errors": {"field": ["message"]}}` format (401/403/404/409/422).

## Update comment

`PUT /api/articles/:slug/comments/:id` with `{"comment":{"body":"..."}}` and `Authorization: Token <jwt>`.
Returns `200` with the updated comment (`updatedAt` refreshed). Only the comment author may update it (`403` otherwise).
Other errors: `401` missing token, `422` blank body, `404` unknown article or comment.
Tests: `api/hurl/comments_update.hurl`.
