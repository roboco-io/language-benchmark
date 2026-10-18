# RealWorld API — C++20 / Drogon / SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
API spec: JWT authentication, users and profiles, articles, comments,
favorites, tags and the personal feed.

In addition to the spec, `PUT /api/articles/:slug/comments/:id` updates a
comment's body. Only the comment author may update it (others get `403`).

## Stack

- C++20 with Drogon coroutine handlers (`drogon::Task<HttpResponsePtr>`)
- Drogon ORM `DbClient` on SQLite, built with CMake
- JWT (HS256) and PBKDF2-HMAC-SHA256 password hashing via OpenSSL

## Running

```sh
PORT=8080 ./start.sh
```

`start.sh` installs Drogon if CMake cannot find it (Homebrew on macOS; on
Debian/Ubuntu it installs apt packages and builds Drogon into `.deps/`),
builds a Release binary in `build/`, and runs the server in the foreground.

### Docker

```sh
docker build -t realworld-drogon .
docker run -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-drogon
```

The image contains a Release build. On startup the server creates the schema
if the database is empty.

## Configuration

| Variable        | Default                          | Meaning                    |
|-----------------|----------------------------------|----------------------------|
| `PORT`          | `8080`                           | HTTP listen port           |
| `DATABASE_PATH` | `realworld.db` (`/data/...` in Docker) | SQLite database file |
| `JWT_SECRET`    | built-in development secret      | HMAC key for JWT signing; set it in production |

## Runtime behavior

The server calls `app().setThreadNum()` with `std::thread::hardware_concurrency()`,
so it uses one I/O thread per available CPU core. The SQLite client pool has the
same number of connections. Each connection runs `PRAGMA busy_timeout = 5000`,
`PRAGMA journal_mode = WAL` and `PRAGMA foreign_keys = ON` when it opens.

## Tests

The official RealWorld Hurl suite (13 files, 154 requests) is vendored in
`api/hurl`, together with `comments_update.hurl` for the comment update endpoint. With the server running:

```sh
HOST=http://localhost:8080 api/hurl/run-hurl-tests.sh
```
