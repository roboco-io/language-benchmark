# RealWorld backend (C++20, Drogon, SQLite)

An implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
backend API: JWT authentication, users and profiles, articles, comments, favorites, tags and the
personal feed.

## Run

```sh
./start.sh            # installs dependencies if missing, builds a Release binary, runs in the foreground
PORT=3000 ./start.sh  # choose the port (default 8080)
```

`start.sh` uses Homebrew on macOS and `apt-get` on Debian/Ubuntu for dependencies.

| Variable        | Default                          | Meaning                       |
|-----------------|----------------------------------|-------------------------------|
| `PORT`          | `8080`                           | HTTP listen port              |
| `DATABASE_PATH` | `realworld.db`                   | SQLite database file          |
| `JWT_SECRET`    | built-in development secret      | HS256 signing key for tokens  |

Set `JWT_SECRET` in any real deployment.

## Docker

```sh
docker build -t realworld .
docker run -p 8080:8080 -e PORT=8080 -v rwdata:/data realworld
```

The image builds a Release binary on `debian:sid`. The schema is created at startup when the
database file is empty or missing. Drogon may log `SQLITE_CONFIG_MULTITHREAD is not supported!`
on Debian; SQLite then stays in its default serialized mode, which is safe.

## Tests

The official RealWorld Hurl tests (13 files, 154 requests) are vendored in `tests/hurl`
(upstream path `specs/api/hurl`). With a server running and [Hurl](https://hurl.dev) installed:

```sh
HOST=http://localhost:8080 ./run-tests.sh
```

## Design notes

- One Drogon worker thread per available CPU core (`app().setThreadNum`), and one pooled SQLite
  connection per core, all accessed through Drogon's `DbClient` with C++20 coroutines.
- SQLite runs in WAL mode (set once at startup, persistent in the file). Every pooled connection
  uses `busy_timeout=5000` ms and `foreign_keys=ON`. With Drogon 1.9.14+ this is done through
  `Sqlite3Config::initializationQueries`; with older Drogon releases it falls back to a SQLite
  auto-extension.
- Passwords are hashed with PBKDF2-HMAC-SHA256; tokens are HS256 JWTs valid for 7 days.
- Article and comment responses follow the RealWorld spec; list endpoints omit the article `body`.
- `PUT /api/articles/:slug/comments/:id` (extension) updates a comment body. Only the comment author may call it (403 otherwise); it returns 200 with the refreshed comment, 422 for a blank body, and 404 for an unknown article or comment.
