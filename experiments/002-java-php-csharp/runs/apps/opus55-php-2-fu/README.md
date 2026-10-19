# RealWorld API — Laravel + Octane (FrankenPHP) + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
API spec: JWT authentication, users and profiles, articles, comments, favorites, tags and the feed.

Stack: PHP 8.5, Laravel 13, Eloquent, SQLite, served by Laravel Octane on FrankenPHP.

## Running locally

```sh
PORT=8000 ./start.sh
```

`start.sh` installs Composer dependencies, creates `.env` and an `APP_KEY` when they are missing,
creates the SQLite database and schema, caches the configuration, and then runs the server in the
foreground. It starts one Octane worker per CPU core; set `WORKERS` to override the count.

## Running with Docker

```sh
docker build -t realworld-laravel .
docker run --rm -e PORT=8080 -p 8080:8080 realworld-laravel
```

The image installs dependencies without dev packages, uses an optimized class map and runs OPcache
without timestamp checks. At start-up the container creates the schema in `/data/database.sqlite`
when the database is empty. Mount a volume on `/data` to keep the data.

When `APP_KEY` is not passed, the container generates a key at start-up, so tokens become invalid
after a restart. Pass `APP_KEY` (or `JWT_SECRET`) to keep tokens valid across restarts.

## Runtime configuration

| Setting | Value |
|---|---|
| Octane workers | one per CPU core (`nproc`), passed as `--workers` |
| SQLite journal mode | WAL |
| SQLite `busy_timeout` | 5000 ms |
| SQLite transactions | `BEGIN IMMEDIATE`, which avoids lock upgrade deadlocks between workers |

The SQLite settings are in `config/database.php`.

## API tests

The official RealWorld Hurl tests are vendored in `tests/api/hurl` (from
[realworld-apps/realworld](https://github.com/realworld-apps/realworld), `specs/api/hurl`).
Run them against a running server:

```sh
HOST=http://localhost:8000 tests/api/hurl/run-hurl-tests.sh
```

Besides the spec, the API supports updating a comment with `PUT /api/articles/:slug/comments/:id`
(body `{"comment":{"body":"..."}}`, author only). Its tests live in `tests/api/custom`. Run them
together with the official tests:

```sh
HOST=http://localhost:8000 tests/api/hurl/run-hurl-tests.sh tests/api/hurl/*.hurl tests/api/custom/*.hurl
```

## Implementation notes

Tokens are sent as `Authorization: Token <jwt>`; a `Bearer` prefix is also accepted. Errors use the
RealWorld body shape `{"errors": {"field": ["message"]}}`: 401 for a missing or invalid token,
403 when a user changes another user's article or comment, 404 for unknown resources, 409 for a
username or email that is already taken, and 422 for validation errors.

Timestamps are stored with millisecond precision and returned in ISO 8601 UTC format. Tags keep the
order in which they were given. Article lists are ordered by newest first and do not include `body`.
