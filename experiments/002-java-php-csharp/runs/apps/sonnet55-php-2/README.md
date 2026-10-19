# RealWorld backend (Laravel + Octane/FrankenPHP + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API:
JWT authentication, users/profiles, articles, comments, favorites, tags and the personal feed.

## Run

```sh
PORT=8000 ./start.sh        # installs dependencies, creates the schema, serves in the foreground
```

`bin/serve.sh` creates `.env` and the app key when missing, runs migrations, and starts
`php artisan octane:frankenphp` with one worker per CPU core. SQLite runs in WAL mode with `busy_timeout` 5000 ms
(`config/database.php`).

## Docker

```sh
docker build -t realworld .
docker run -e PORT=8000 -p 8000:8000 realworld
```

The image uses a production build (no dev dependencies, classmap-authoritative autoloader). The schema is created at
container start when the database is empty.

## Tests

The official Hurl suite is vendored in `hurl/` (13 files, 154 requests). With the server running:

```sh
HOST=http://localhost:8000 hurl/run.sh
```
