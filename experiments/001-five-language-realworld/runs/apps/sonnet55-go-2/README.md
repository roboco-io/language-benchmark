# RealWorld backend (Go + Gin + GORM + SQLite)

An implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API.

- Go, Gin, GORM, and the pure-Go `modernc.org/sqlite` driver (via `github.com/glebarez/sqlite`). No cgo.
- JWT authentication (HS256, `Authorization: Token <jwt>`).
- SQLite runs in WAL mode with `busy_timeout=5000`. `GOMAXPROCS` equals the CPU core count.
- The schema is created automatically on start-up when the database is empty.

## Run

```sh
PORT=8000 ./start.sh        # installs dependencies, builds, runs in the foreground
```

| Variable     | Default                          | Meaning                   |
|--------------|----------------------------------|---------------------------|
| `PORT`       | `8000`                           | HTTP listen port          |
| `DB_PATH`    | `realworld.db`                   | SQLite database file      |
| `JWT_SECRET` | built-in development secret      | HMAC key for JWT signing  |

Set `JWT_SECRET` in any real deployment.

## Docker

```sh
docker build -t realworld .
docker run -e PORT=8000 -p 8000:8000 realworld
```

## Tests

The official Hurl tests (`api/hurl` from realworld-apps/realworld, 13 files, 154 requests) are vendored in `tests/hurl`.
Start the server, then run:

```sh
HOST=http://localhost:8000 tests/run-hurl.sh
```
