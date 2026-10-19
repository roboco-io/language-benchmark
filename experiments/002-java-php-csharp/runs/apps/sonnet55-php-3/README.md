# RealWorld backend (PHP 8.5 + Laravel + Octane/FrankenPHP)

A RealWorld ("Conduit") backend API: JWT auth, users/profiles, articles, comments, favorites, tags and feed.
Storage is SQLite (WAL mode, `busy_timeout` 5000 ms). The server is Laravel Octane on FrankenPHP with one worker per CPU core.

## Run

    PORT=8000 ./start.sh

`start.sh` installs Composer dependencies, creates `.env` and the app key, creates the SQLite file, runs migrations, caches config/routes, and starts Octane in the foreground. `PORT` defaults to 8000.

## Docker

    docker build -t realworld-php .
    docker run -p 8000:8000 -e PORT=8000 realworld-php

The container runs migrations at start-up, so an empty database gets its schema automatically.

## Tests

The official RealWorld Hurl tests are vendored in `tests/hurl`. Start the server, then:

    HOST=http://localhost:8000 tests/hurl/run-hurl-tests.sh

All 13 files / 154 requests pass.
