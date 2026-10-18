# RealWorld backend (FastAPI + SQLAlchemy + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API.

## Run

```
PORT=8000 ./start.sh
```

`start.sh` creates `.venv`, installs `requirements.txt`, and runs uvicorn in the foreground
with one worker per CPU core. The SQLite file path comes from `DATABASE_PATH` (default `realworld.db`).
The database uses WAL mode with `busy_timeout=5000`, and the schema is created at startup.

## Docker

```
docker build -t realworld .
docker run -p 8000:8000 -e PORT=8000 realworld
```

## Tests

The official Hurl suite is vendored in `api/hurl`. With the server running:

```
HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh
```
