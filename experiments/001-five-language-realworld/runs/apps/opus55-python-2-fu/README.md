# RealWorld API — FastAPI + SQLAlchemy + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
API spec: JWT authentication, users and profiles, articles, comments, favorites, tags, and the feed.

In addition to the spec, `PUT /api/articles/:slug/comments/:id` updates a comment's body
(`{"comment":{"body":"..."}}`). Only the comment author can update it. The response has the same
shape as the create-comment response, and `updatedAt` is refreshed.

## Run locally

```sh
PORT=8000 ./start.sh
```

`start.sh` creates a virtualenv in `.venv`, installs `requirements.txt`, byte-compiles the app, and
runs the server in the foreground. The launcher (`python -m app.serve`) creates the database schema
first. Then it starts uvicorn with one worker per available CPU core.

## Run with Docker

```sh
docker build -t realworld-fastapi .
docker run --rm -e PORT=8000 -p 8000:8000 -v realworld-data:/data realworld-fastapi
```

The container creates the schema itself when it starts with an empty database at `/data`.

## Configuration

| Variable          | Default               | Meaning                                              |
|-------------------|-----------------------|------------------------------------------------------|
| `PORT`            | `8000`                | Listening port                                       |
| `HOST`            | `0.0.0.0`             | Listening address                                    |
| `DATABASE_PATH`   | `data/realworld.db`   | SQLite file (`/data/realworld.db` in Docker)         |
| `JWT_SECRET`      | generated             | HMAC secret for tokens (see below)                   |
| `WEB_CONCURRENCY` | CPU core count        | Number of uvicorn worker processes                   |

If `JWT_SECRET` is not set, the server generates a random secret one time. It stores the secret in
`jwt_secret` next to the database file, so all workers and later restarts use the same secret.

SQLite runs in WAL mode with `busy_timeout=5000` ms. Write requests open their transaction with
`BEGIN IMMEDIATE`. Because of this, concurrent writers in different workers wait for the lock and
do not fail with a lock-upgrade error.

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154 requests). It requires
[hurl](https://hurl.dev):

```sh
HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh
```

Tests for endpoints that are not in the official suite are in `tests/hurl`. To run them together
with the official suite:

```sh
HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh api/hurl/*.hurl tests/hurl/*.hurl
```
