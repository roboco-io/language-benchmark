# RealWorld Backend (C# / .NET 10 / ASP.NET Core / EF Core / SQLite)

Implementation of the full [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API:
JWT authentication, users/profiles, articles, comments, favorites, tags, and feed.

## Run

```bash
PORT=8000 ./start.sh      # restore, build (Release), run in the foreground
```

Environment variables: `PORT` (default 8000), `DATABASE_PATH` (default `realworld.db`), `JWT_SECRET`.
The schema is created automatically on startup. SQLite runs in WAL mode with a 5000 ms `busy_timeout`.
Kestrel uses the default .NET thread pool, which scales with the CPU core count.

## Docker

```bash
docker build -t realworld .
docker run -p 8000:8000 -e PORT=8000 realworld
```

## Tests

The official Hurl tests are vendored in `tests/hurl` (13 files, 154 requests), plus `update_comment.hurl` (12 requests) for `PUT /api/articles/:slug/comments/:id`.
With the server running:

```bash
HOST=http://localhost:8000 tests/hurl/run-hurl-tests.sh
```
