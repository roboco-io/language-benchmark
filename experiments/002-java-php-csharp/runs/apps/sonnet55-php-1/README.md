# RealWorld backend: PHP 8.5 + Laravel + Octane (FrankenPHP)

An implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
backend API. It uses Eloquent with SQLite (WAL mode, `busy_timeout` 5000 ms) and JWT authentication
(`Authorization: Token <jwt>`).

## Run

```sh
PORT=8000 ./start.sh
```

`start.sh` installs dependencies, creates `.env` and the app key, creates the schema, and runs
`octane:start --server=frankenphp` in the foreground with one worker per CPU core (override with `WORKERS`).

## Docker

```sh
docker build -t realworld-php .
docker run -p 8000:8000 -e PORT=8000 realworld-php
```

The container creates its schema on start when the database is empty.

## Tests

The official Hurl tests are vendored in `api/hurl`. With the server running:

```sh
hurl --test --jobs 1 --variable host=http://localhost:8000 --variable uid=$(date +%s) api/hurl/*.hurl
```
