# RealWorld API: Laravel + Octane (FrankenPHP) + SQLite

This repository implements the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API. It covers JWT authentication, users and profiles, article CRUD, comments, favorites, tags, and the feed.

The stack is PHP 8.5, Laravel 13, Eloquent, and SQLite. Laravel Octane serves the app on FrankenPHP with one worker per CPU core.

## Running

```sh
PORT=8000 ./start.sh
```

`start.sh` installs the Composer dependencies with an optimized autoloader. It then creates `.env` and an application key if they are missing, creates the SQLite database, and caches config and routes. It runs migrations and starts Octane in the foreground on `$PORT` (default `8000`). The script uses `nproc` (or `sysctl hw.ncpu` on macOS) to count the CPU cores and starts one worker per core. The `frankenphp` binary must be on `PATH`.

## Docker

```sh
docker build -t realworld-laravel .
docker run -e PORT=8000 -p 8000:8000 realworld-laravel
```

The image is based on `dunglas/frankenphp`. It contains production dependencies only, a classmap-authoritative autoloader, and OPcache with timestamp validation off. At startup, `docker/entrypoint.sh` caches config and routes and runs migrations. Migrations create the schema when the database is empty. The entrypoint then starts Octane on `$PORT` with `nproc` workers. To keep data between runs, mount a volume at `/app/database`. To keep issued tokens valid after a restart, set `APP_KEY` or `JWT_SECRET`.

## SQLite settings

`config/database.php` opens every connection with `journal_mode=WAL`, `busy_timeout=5000` ms, and `synchronous=NORMAL`. You can override them with `DB_JOURNAL_MODE`, `DB_BUSY_TIMEOUT`, and `DB_SYNCHRONOUS`.

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl`. It has 13 files with 154 requests. To run it against a running server:

```sh
HOST=http://localhost:8000 ./api/hurl/run-hurl-tests.sh
```

## Layout

| Path | Purpose |
| --- | --- |
| `routes/api.php` | Endpoint definitions |
| `app/Http/Controllers` | User, profile, article, comment, and tag endpoints |
| `app/Http/Middleware/AuthenticateToken.php` | Parses `Authorization: Token <jwt>`; required or optional per route |
| `app/Support/Jwt.php` | HS256 JWT signing and verification |
| `app/Support/Present.php` | Response serialization |
| `app/Support/ApiError.php` | `{"errors": {...}}` error responses |
| `database/migrations` | Schema |
