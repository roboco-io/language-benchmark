# RealWorld API — ASP.NET Core + EF Core + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
API spec (authentication, users, profiles, articles, comments, favorites, tags and feed) built with
.NET 10, ASP.NET Core minimal APIs, Entity Framework Core and SQLite.

## Running locally

```sh
PORT=8080 ./start.sh
```

`start.sh` restores NuGet packages, builds a Release build and runs the server in the foreground.

| Variable        | Default                        | Purpose                                   |
|-----------------|--------------------------------|-------------------------------------------|
| `PORT`          | `8080`                         | HTTP port (binds `0.0.0.0`)               |
| `DATABASE_PATH` | `./data/realworld.db`          | SQLite database file                      |
| `JWT_SECRET`    | insecure development secret    | HMAC key for signing JWTs (set in prod)   |

The schema is created automatically when the database is empty.

## Docker

```sh
docker build -t realworld-csharp .
docker run -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-csharp
```

The image is built with `dotnet publish -c Release` and runs on the ASP.NET runtime image.
The database lives in `/data/realworld.db`.

## Runtime characteristics

Kestrel serves requests on the default .NET thread pool, which scales with the number of available
CPU cores. Server GC is enabled. SQLite runs in WAL journal mode, and every pooled connection sets
`busy_timeout = 5000` ms through an EF Core connection interceptor. The startup log prints the
effective `journal_mode`, `busy_timeout` and core count.

## API tests

The official RealWorld Hurl suite (13 files, 154 requests) is vendored under `api/hurl`.
Start the server, then run:

```sh
HOST=http://localhost:8080 api/run-api-tests-hurl.sh
```

## Extensions beyond the spec

- `PUT /api/articles/:slug/comments/:id` updates a comment's `body` (author only). It returns `200` with
  the updated comment; `updatedAt` is refreshed while `id` and `createdAt` stay the same. Errors: `401`
  (missing token), `403` (not the author), `404` (unknown article or comment), `422` (blank body).
  Covered by `api/hurl/comments_update.hurl`, which runs with the rest of the suite.

## Layout

- `src/RealWorld/Program.cs` — host setup, database initialisation, error handling.
- `src/RealWorld/Data/AppDb.cs` — entities, EF Core model and SQLite pragma interceptor.
- `src/RealWorld/Infrastructure/` — JWT, request parsing, validation and response DTOs.
- `src/RealWorld/Endpoints/` — users/profiles, articles/favorites/tags/feed, comments.
