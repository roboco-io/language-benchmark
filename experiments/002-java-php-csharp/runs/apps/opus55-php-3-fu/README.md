# RealWorld API — Laravel + Octane (FrankenPHP) + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
API spec using PHP 8.5, Laravel, Eloquent and SQLite, served by Laravel Octane on FrankenPHP.

## Running

```sh
PORT=8000 ./start.sh
```

`start.sh` installs Composer dependencies (production, optimized autoloader), creates `.env` with a
random `APP_KEY` if needed, caches config/routes, creates the SQLite schema and starts Octane in the
foreground on `$PORT` (default `8000`).

### Docker

```sh
docker build -t realworld-laravel .
docker run -e PORT=8000 -p 8000:8000 -v realworld-data:/app/data realworld-laravel
```

The container creates its schema on an empty database (`/app/data/database.sqlite`). Pass a fixed
`APP_KEY` (or `JWT_SECRET`) to keep tokens valid across container restarts.

## Runtime configuration

- Octane runs on FrankenPHP with one worker per available CPU core (`nproc`).
- SQLite uses WAL journal mode, `busy_timeout = 5000` ms, `synchronous = NORMAL` and `IMMEDIATE`
  transactions (see `config/database.php`).
- Authentication uses HS256 JWTs sent as `Authorization: Token <jwt>`.

## Extensions to the spec

- `PUT /api/articles/:slug/comments/:id` updates a comment's `body` (author only). It returns
  `200` with the updated comment; `updatedAt` is refreshed while `id` and `createdAt` are kept.
  Errors: `401` (no token), `403` (not the author), `404` (unknown article or comment) and
  `422` (blank body).

## Tests

The official RealWorld Hurl suite is vendored under `tests/hurl`, together with
`comments_update.hurl` for the comment update extension. With the server running:

```sh
HOST=http://localhost:8000 tests/run-hurl.sh
```
