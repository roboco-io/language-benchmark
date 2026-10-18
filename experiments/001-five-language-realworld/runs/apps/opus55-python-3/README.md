# RealWorld API — FastAPI + SQLAlchemy + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
(Conduit) API: JWT authentication, users, profiles, articles, comments,
favorites, tags, and the personal feed.

## Run locally

```sh
PORT=8000 ./start.sh
```

`start.sh` creates `.venv`, installs `requirements.txt`, byte-compiles the
app, and runs the server in the foreground. `app/serve.py` creates the schema
first, then starts uvicorn with one worker per available CPU core.

## Run with Docker

```sh
docker build -t realworld-fastapi .
docker run -e PORT=8000 -p 8000:8000 -v realworld-data:/data realworld-fastapi
```

The container creates its schema on start when the database is empty.

## Configuration

| Variable        | Default               | Purpose                                  |
|-----------------|-----------------------|------------------------------------------|
| `PORT`          | `8000`                | HTTP listen port                         |
| `DATABASE_PATH` | `data/realworld.db`   | SQLite file (`/data/realworld.db` in Docker) |
| `JWT_SECRET`    | generated             | Token signing key. If unset, a random key is created once and stored as `.jwt_secret` next to the database, so all workers share it. |

SQLite connections use `journal_mode=WAL` and `busy_timeout=5000`.

## API tests

The official Hurl suite is vendored in `api/hurl` (from
`realworld-apps/realworld`, `specs/api/hurl`). With the server running:

```sh
./run-api-tests.sh http://localhost:8000
```
