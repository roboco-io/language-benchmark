# RealWorld API — C++20 / Drogon / SQLite

This repository implements the backend API of the
[RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
specification. The server is written in C++20 with the
[Drogon](https://github.com/drogonframework/drogon) web framework. It stores
data in SQLite through the Drogon ORM `DbClient`, and it is built with CMake.

The API covers JWT authentication, users, profiles and follows, article CRUD,
comments, favorites, tags and the personal feed.

In addition to the specification, the API has one extra endpoint,
`PUT /api/articles/:slug/comments/:id`. It accepts
`{"comment":{"body":"..."}}` and lets the comment author change the body of a
comment. The endpoint keeps `id` and `createdAt`, refreshes `updatedAt`, and
returns `200` with the comment. It returns `401` when the token is missing,
`403` when the user is not the comment author, `422` when the body is blank,
and `404` when the article or the comment does not exist.

## Running

### `start.sh`

```sh
PORT=8080 ./start.sh
```

The script does three things and then keeps the server in the foreground:

1. It checks whether CMake can find Drogon. If Drogon is missing, the script
   installs it: on macOS with `brew install drogon`; on Debian/Ubuntu it
   installs the build packages with `apt-get` and builds Drogon v1.9.14 from
   source into `.deps/`.
2. It builds the server in Release mode into `build/`.
3. It starts `build/realworld`.

### Docker

```sh
docker build -t realworld-drogon .
docker run --rm -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-drogon
```

The image is built in two stages. The build stage compiles Drogon and the
server in Release mode. The runtime stage contains only the stripped binary
and its shared libraries. When the database file is empty or missing, the
server creates the schema at startup.

## Configuration

| Variable        | Default                                     | Purpose                                    |
|-----------------|---------------------------------------------|--------------------------------------------|
| `PORT`          | `8080`                                      | TCP port the server listens on             |
| `DATABASE_PATH` | `realworld.db` (`data/realworld.db` with `start.sh`, `/data/realworld.db` in Docker) | SQLite database file |
| `JWT_SECRET`    | random per process                          | HMAC key for HS256 tokens                  |

If `JWT_SECRET` is not set, the server generates a random secret at startup.
Tokens issued by one process are then rejected after a restart, so set
`JWT_SECRET` in any persistent deployment.

## Runtime behavior

- **Threads:** the server calls `app().setThreadNum()` with
  `std::thread::hardware_concurrency()`, so it runs one I/O thread per
  available CPU core. The SQLite pool opens the same number of connections.
- **SQLite:** every pooled connection runs `PRAGMA busy_timeout = 5000`,
  `PRAGMA journal_mode = WAL`, `PRAGMA foreign_keys = ON` and
  `PRAGMA synchronous = NORMAL` before it is used. The WAL switch and the
  schema creation first run on a single bootstrap connection, so the pooled
  connections do not compete for the lock that the WAL switch needs. The
  server logs the effective `journal_mode` and `busy_timeout` at startup.
- **Passwords** are stored as PBKDF2-HMAC-SHA256 hashes with a random salt.
  New passwords must have at least 8 characters.
- **Slugs** are derived from the title and get a random 8-character hex
  suffix. The suffix keeps slugs unique for duplicate titles and prevents a
  slug from colliding with the `/api/articles/feed` path.

## Tests

The official RealWorld Hurl suite (13 files, 154 requests) is vendored in
`tests/hurl`. It comes from
[realworld-apps/realworld](https://github.com/realworld-apps/realworld)
(`specs/api/hurl`, commit `ebbcdeb`) under the license in
`tests/hurl/LICENSE-realworld`.

With the server running, run:

```sh
HOST=http://localhost:8080 tests/hurl/run-hurl-tests.sh
```

The tests for the extra update-comment endpoint are in `tests/extra`. To run
them together with the official suite, run:

```sh
HOST=http://localhost:8080 tests/hurl/run-hurl-tests.sh tests/hurl/*.hurl tests/extra/*.hurl
```

## Layout

| Path               | Content                                                   |
|--------------------|-----------------------------------------------------------|
| `src/main.cpp`     | Startup: schema, SQLite pool, thread count, listener      |
| `src/auth.cpp`     | JWT (HS256) creation and checks, password hashing         |
| `src/users.cpp`    | `/api/users`, `/api/user`, `/api/profiles` handlers       |
| `src/articles.cpp` | Articles, feed, favorites, comments and tags handlers     |
| `src/util.cpp`     | JSON responses, error format, timestamps, slugs           |
