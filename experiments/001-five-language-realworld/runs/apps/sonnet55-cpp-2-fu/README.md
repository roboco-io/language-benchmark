# RealWorld backend (C++20, Drogon, SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
backend API: JWT authentication, users and profiles, article CRUD, comments,
favorites, tags and the personal feed.

## Run

```sh
PORT=8000 ./start.sh
```

`start.sh` installs missing dependencies (Homebrew on macOS, apt plus a Drogon
source build on Linux), builds a Release binary with CMake, and runs the server
in the foreground.

Environment variables:

| Variable     | Default               | Meaning                                   |
|--------------|-----------------------|-------------------------------------------|
| `PORT`       | `8000`                | Listening port                            |
| `DB_PATH`    | `data/realworld.db`   | SQLite file; schema is created on startup |
| `JWT_SECRET` | random per process    | HS256 signing key; set it to keep tokens valid across restarts |

## Docker

```sh
docker build -t realworld-cpp .
docker run -p 8000:8000 -e PORT=8000 realworld-cpp
```

The image uses a Release build. With an empty database the server creates its
own schema at startup (`DB_PATH` defaults to `/data/realworld.db` in the image).

## Tests

The official RealWorld Hurl API tests (13 files, 154 requests) are vendored in
`tests/hurl`. With the server running:

```sh
PORT=8000 tests/run-hurl.sh
```

## Design notes

- Worker threads equal the CPU core count (`app().setThreadNum`), and the
  SQLite pool has one connection per core.
- Every connection runs `journal_mode=WAL` and `busy_timeout=5000`.
- Handlers are C++20 coroutines over Drogon's `DbClient`. Every write is a
  single SQL statement because Drogon commits a `Transaction` asynchronously
  on destruction, which lets a following read on another connection miss the
  write. For that reason an article's tags are stored as a JSON array in
  `articles.tag_list` and queried with SQLite's `json_each`.
- Passwords use PBKDF2-HMAC-SHA256; tokens are HS256 JWTs valid for 30 days.

## Update comment

`PUT /api/articles/:slug/comments/:id` with `{"comment":{"body":"..."}}` updates a
comment's body and refreshes `updatedAt`. Only the comment author may update it
(`403` otherwise). Errors: `401` missing token, `404` unknown article or comment,
`422` blank body. Covered by `tests/hurl/update_comment.hurl`.
