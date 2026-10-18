# RealWorld API — FastAPI + SQLAlchemy + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
(Conduit) API: JWT authentication, users and profiles, articles, comments,
favorites, tags and the personal feed.

## Run locally

```sh
PORT=8000 ./start.sh
```

`start.sh` creates a virtualenv in `.venv`, installs `requirements.txt`,
byte-compiles the app and starts uvicorn in the foreground. It starts one
worker per available CPU core (override with `WORKERS`).

## Run with Docker

```sh
docker build -t realworld-fastapi .
docker run -e PORT=8000 -p 8000:8000 -v realworld-data:/data realworld-fastapi
```

The container creates the database schema on startup when the database is empty.

## Configuration

| Variable        | Default                                         | Purpose                         |
|-----------------|-------------------------------------------------|---------------------------------|
| `PORT`          | `8000`                                          | HTTP port                       |
| `WORKERS`       | number of available CPU cores                   | uvicorn worker processes        |
| `DATABASE_PATH` | `data/realworld.db` (local), `/data/realworld.db` (Docker) | SQLite file          |
| `JWT_SECRET`    | a fixed development secret                      | JWT signing key; set it in production |

SQLite runs in WAL mode with `busy_timeout=5000` ms and foreign keys enabled
on every connection (see `app/db.py`).

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154 requests).

```sh
HOST=http://localhost:8000 api/run-api-tests-hurl.sh
```
