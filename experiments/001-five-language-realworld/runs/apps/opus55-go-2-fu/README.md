# RealWorld API — Go + Gin + GORM + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) API spec:
JWT authentication, users and profiles, articles, comments, favorites, tags, and the personal feed.

## Stack

- Go, [Gin](https://github.com/gin-gonic/gin) HTTP router, [GORM](https://gorm.io) ORM
- SQLite through the pure-Go `modernc.org/sqlite` driver (no CGO). Every connection runs with
  `journal_mode=WAL` and `busy_timeout=5000`.
- `GOMAXPROCS` is set to the number of available CPU cores at startup.

## Run

```sh
PORT=8000 ./start.sh
```

`start.sh` downloads modules, builds an optimized binary into `bin/`, and runs it in the foreground.

| Variable        | Default                          | Purpose                     |
|-----------------|----------------------------------|-----------------------------|
| `PORT`          | `8000`                           | HTTP listen port            |
| `DATABASE_PATH` | `realworld.db`                   | SQLite database file        |
| `JWT_SECRET`    | built-in development secret      | HMAC key for signing tokens |

The schema is created automatically when the server starts with an empty database.

## Docker

```sh
docker build -t realworld-go .
docker run -e PORT=8000 -p 8000:8000 realworld-go
```

The image stores the database at `/data/realworld.db` (declared as a volume).

## Tests

The official RealWorld Hurl tests are vendored in `api/hurl` (13 files, 154 requests). With the server
running, run:

```sh
HOST=http://localhost:8000 ./api/hurl/run-hurl-tests.sh
```

Beyond the official spec, the API also supports updating a comment with
`PUT /api/articles/:slug/comments/:id` (author only). Its Hurl tests live in `tests/hurl`. To run them
together with the official suite:

```sh
HOST=http://localhost:8000 ./api/hurl/run-hurl-tests.sh api/hurl/*.hurl tests/hurl/*.hurl
```
