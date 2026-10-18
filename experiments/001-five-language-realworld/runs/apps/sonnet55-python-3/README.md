# RealWorld backend (FastAPI + SQLAlchemy + SQLite)

Implements the full RealWorld backend API spec.

## Run

    PORT=8000 ./start.sh

`start.sh` creates a virtualenv, installs dependencies, byte-compiles the app, creates the schema,
and runs uvicorn in the foreground with one worker per CPU core. SQLite uses WAL mode and
`busy_timeout=5000`. Environment variables: `PORT`, `DATABASE_PATH`, `JWT_SECRET`.

## Docker

    docker build -t realworld .
    docker run -e PORT=8000 -p 8000:8000 realworld

The schema is created at startup when the database is empty.

## Tests

The official Hurl tests are vendored in `tests/hurl`. With the server running:

    HOST=http://localhost:8000 tests/run-hurl-tests.sh
