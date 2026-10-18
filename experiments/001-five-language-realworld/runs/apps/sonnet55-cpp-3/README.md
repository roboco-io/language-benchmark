# RealWorld backend (C++20, Drogon, SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
backend API: JWT authentication, users and profiles, article CRUD, comments, favorites, tags and the
personal feed. Persistence uses SQLite through Drogon's `DbClient` (raw SQL, coroutine handlers).

## Run

```sh
PORT=8080 ./start.sh
```

`start.sh` installs missing libraries (`scripts/install-deps.sh`: Homebrew on macOS, apt plus a Drogon
source build on Linux), builds a Release binary with CMake and runs the server in the foreground.

| Variable     | Default                          | Meaning                            |
|--------------|----------------------------------|------------------------------------|
| `PORT`       | `8080`                           | HTTP listening port                |
| `DB_PATH`    | `realworld.db`                   | SQLite file (created if missing)   |
| `JWT_SECRET` | built-in development secret      | HMAC key for JWT tokens; set it in production |

The server runs one Drogon IO thread and one SQLite connection per CPU core. Each connection uses
WAL mode and `busy_timeout = 5000` ms. The schema is created at start-up when the database is empty.

## Docker

```sh
docker build -t realworld-cpp .
docker run --rm -p 8080:8080 -e PORT=8080 -v realworld-data:/data realworld-cpp
```

## Tests

The official RealWorld Hurl tests (13 files, 154 requests) are vendored in `tests/hurl`.
With the server running:

```sh
PORT=8080 tests/run-hurl.sh
```
