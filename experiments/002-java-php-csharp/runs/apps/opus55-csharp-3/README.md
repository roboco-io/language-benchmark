# RealWorld (Conduit) backend: C# / ASP.NET Core / EF Core / SQLite

This repository implements the [RealWorld backend API](https://realworld-docs.netlify.app/specifications/backend/endpoints/) with .NET 10, ASP.NET Core minimal APIs, Entity Framework Core, and SQLite.

## Run

`./start.sh` restores the NuGet packages, publishes a Release build to `out/`, and then runs the server in the foreground. The server listens on the port in the `PORT` environment variable (default `8000`).

```sh
PORT=8000 ./start.sh
```

With Docker, the image contains a Release (optimized) build. The container also reads `PORT`. When the database file is empty or missing, the server creates the schema at startup.

```sh
docker build -t conduit .
docker run -p 8000:8000 -e PORT=8000 -e JWT_SECRET=change-me -v conduit-data:/app/data conduit
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8000` | HTTP port. |
| `DATABASE_PATH` | `data/conduit.db` (`start.sh`), `/app/data/conduit.db` (Docker) | SQLite database file. |
| `JWT_SECRET` | built-in development secret | HMAC key for signing JWTs. Set this in production. |

## Runtime design

Kestrel processes requests on the default .NET thread pool, so the server uses all available CPU cores. SQLite runs in WAL mode, which lets readers continue while a writer commits. Every connection sets `busy_timeout = 5000` ms and `foreign_keys = ON` through an EF Core connection interceptor, so concurrent writers wait for the lock instead of failing immediately, and deleting an article cascades to its tags, favorites, and comments. The startup log prints the effective `journal_mode` and `busy_timeout` values.

## Tests

The official RealWorld Hurl tests (13 files, 154 requests) are vendored in `api/hurl` from [realworld-apps/realworld](https://github.com/realworld-apps/realworld) (`specs/api/hurl`, commit `ebbcdeb`). Start the server, then run:

```sh
HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh
```

## Layout

| Path | Contents |
|---|---|
| `src/Conduit/Program.cs` | Host setup, port, database initialization. |
| `src/Conduit/Data.cs` | Entities, `DbContext`, SQLite pragma interceptor. |
| `src/Conduit/Auth.cs` | JWT issuing/validation and PBKDF2 password hashing. |
| `src/Conduit/Http.cs` | Error responses and request-body parsing helpers. |
| `src/Conduit/UserEndpoints.cs` | Users, authentication, profiles, follows. |
| `src/Conduit/ArticleEndpoints.cs` | Articles, feed, favorites, comments, tags. |
