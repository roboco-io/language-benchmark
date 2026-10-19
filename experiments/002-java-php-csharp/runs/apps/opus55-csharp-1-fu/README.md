# RealWorld (Conduit) Backend — C# / ASP.NET Core

This repository implements the [RealWorld backend API](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
with .NET 10, ASP.NET Core minimal APIs, Entity Framework Core and SQLite.

## Running

`./start.sh` restores the NuGet packages, publishes a Release build to `out/` and runs the
server in the foreground. The server listens on the port in the `PORT` environment variable
(default `8000`).

```sh
PORT=8000 ./start.sh
```

With Docker, the image is built from a Release publish. The container also reads `PORT`, and it
creates the schema on startup when the database is empty.

```sh
docker build -t conduit .
docker run -e PORT=8000 -p 8000:8000 conduit
```

| Variable        | Default                                      | Purpose                         |
|-----------------|----------------------------------------------|---------------------------------|
| `PORT`          | `8000`                                       | HTTP listen port                |
| `DATABASE_PATH` | `data/conduit.db` (`/app/data/conduit.db` in Docker) | SQLite database file   |
| `JWT_SECRET`    | a development secret                         | HMAC key for signing JWTs       |

Set `JWT_SECRET` in any real deployment, because the default secret is public.

## Runtime characteristics

Kestrel processes requests on the default .NET thread pool, so the number of worker threads
scales with the available CPU cores. On startup the server switches the SQLite database to WAL
journaling. Every new connection sets `busy_timeout` to 5000 ms and enables foreign keys, so
concurrent writers wait for a lock instead of failing immediately.

## Tests

The official RealWorld Hurl tests are vendored in `api/hurl` (see `api/hurl/SOURCE.txt` for the
upstream commit). Start the server, then run:

```sh
HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh
```

`api/hurl/comments_update.hurl` is not part of the upstream suite. It covers the extra
`PUT /api/articles/:slug/comments/:id` endpoint, which lets the comment author change the comment
body (`401` without a token, `403` for other users, `404` for an unknown article or comment, `422`
for an empty body). The runner script picks it up together with the official files.

## Layout

- `src/Conduit/Program.cs` — host setup, port, SQLite configuration, error handling.
- `src/Conduit/Endpoints.cs` — all API routes.
- `src/Conduit/Data.cs` — EF Core entities, `DbContext` and the SQLite pragma interceptor.
- `src/Conduit/Auth.cs` — JWT creation/validation and password hashing.
- `src/Conduit/Json.cs` — response DTOs and request parsing/validation helpers.
