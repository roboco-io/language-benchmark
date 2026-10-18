# RealWorld backend (FastAPI + SQLAlchemy + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API:
JWT auth, users/profiles, articles, comments, favorites, tags and feed.

## Run

```sh
PORT=8000 ./start.sh      # creates .venv, installs dependencies, runs in the foreground
```

Docker:

```sh
docker build -t realworld-py .
docker run -e PORT=8000 -p 8000:8000 -v realworld-data:/data realworld-py
```

- `PORT`: listen port (default 8000).
- `DATABASE_PATH`: SQLite file (default `data/realworld.db`, `/data/realworld.db` in Docker). The schema is created on startup.
- `JWT_SECRET`: token signing secret (set it in production).
- `WORKERS`: overrides the worker count; by default uvicorn runs one worker per available CPU core.
- SQLite runs in WAL mode with `busy_timeout=5000`.

## Tests

The official RealWorld Hurl tests are vendored in `tests/hurl` (13 files, 154 requests), plus `comments_update.hurl` for `PUT /api/articles/:slug/comments/:id` (12 requests). With the server running:

```sh
HOST=http://localhost:8000 tests/run-api-tests-hurl.sh
```
